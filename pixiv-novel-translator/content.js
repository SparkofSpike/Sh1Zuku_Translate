// ============================================================
// Pixiv Novel Translator — Content Script
// ============================================================

// ─── State ───────────────────────────────────────────────────

let state = {
  novelId: null,
  translating: false,
  mode: 'panel',          // 'panel' | 'inline'
  targetLang: 'zh',
  streamingText: '',
  currentParaIndex: 0,    // current paragraph being translated
  paraTranslations: [],   // per-paragraph translations
  inlineContainer: null,  // inline mode: paragraph wrapper
  panel: null,
  panelBody: null,
  cancelBtn: null,
  originalHtml: null,     // saved original container HTML
  novelTitle: '',
  novelAuthor: ''
};

// ─── Detect Novel ID from URL ───────────────────────────────

function getNovelIdFromUrl() {
  const url = window.location.href;
  const match1 = url.match(/[?&]id=(\d+)/);
  if (match1) return match1[1];
  const match2 = url.match(/\/novel\/(\d+)/);
  if (match2) return match2[1];
  return null;
}

// ─── Send Message to Background ─────────────────────────────

function sendToBackground(type, payload) {
  return new Promise((resolve, reject) => {
    chrome.runtime.sendMessage({ type, ...payload }, (response) => {
      if (chrome.runtime.lastError) {
        reject(new Error(chrome.runtime.lastError.message));
      } else if (response && response.success) {
        resolve(response.data);
      } else {
        reject(new Error(response?.error || '未知错误'));
      }
    });
  });
}

// ─── HTML → Plain Text ──────────────────────────────────────

function htmlToText(html) {
  return html
    .replace(/<br\s*\/?>/gi, '\n')
    .replace(/<[^>]+>/g, '')
    .replace(/&nbsp;/g, ' ')
    .replace(/&amp;/g, '&')
    .replace(/&lt;/g, '<')
    .replace(/&gt;/g, '>')
    .replace(/&quot;/g, '"')
    .replace(/&#39;/g, "'")
    .trim();
}

function textToParagraphs(text) {
  return text
    .split(/\n{2,}/)
    .map(p => p.trim())
    .filter(p => p.length > 0);
}

// ─── Escape HTML ────────────────────────────────────────────

function escapeHtml(str) {
  const div = document.createElement('div');
  div.textContent = str;
  return div.innerHTML;
}

// ─── Create Floating Button ─────────────────────────────────

function createButton() {
  const btn = document.createElement('button');
  btn.id = 'pnt-translate-btn';
  btn.textContent = '翻译';
  btn.style.cssText = `
    position: fixed;
    bottom: 24px;
    right: 24px;
    z-index: 99999;
    padding: 10px 20px;
    background: #1a1a1a;
    color: #fff;
    border: none;
    border-radius: 24px;
    font-size: 14px;
    cursor: pointer;
    box-shadow: 0 2px 12px rgba(0,0,0,0.2);
    transition: background 0.2s;
  `;
  btn.onmouseenter = () => { btn.style.background = '#444'; };
  btn.onmouseleave = () => { btn.style.background = '#1a1a1a'; };
  btn.onclick = handleTranslate;
  return btn;
}

// ─── Loading / Status Indicator ─────────────────────────────

function showStatus(text, { error = false } = {}) {
  let el = document.getElementById('pnt-status');
  if (!el) {
    el = document.createElement('div');
    el.id = 'pnt-status';
    el.style.cssText = `
      position: fixed;
      bottom: 24px;
      right: 130px;
      z-index: 99999;
      padding: 10px 20px;
      background: #fff;
      color: #333;
      border: 1px solid #ddd;
      border-radius: 24px;
      font-size: 14px;
      box-shadow: 0 2px 12px rgba(0,0,0,0.15);
    `;
    document.body.appendChild(el);
  }
  el.textContent = text;
  el.style.color = error ? '#e03131' : '#333';
}

function hideStatus() {
  const el = document.getElementById('pnt-status');
  if (el) el.remove();
}

// ─── Panel Mode: Render Slide-in Panel ──────────────────────

function createPanel() {
  removePanel();
  const panel = document.createElement('div');
  panel.id = 'pnt-panel';
  panel.innerHTML = `
    <div class="pnt-header">
      <div class="pnt-header-left">
        <strong>翻译结果</strong>
        <span class="pnt-title"></span>
        <span class="pnt-meta"></span>
      </div>
      <button class="pnt-close-btn" title="关闭">×</button>
    </div>
    <div class="pnt-toolbar">
      <button class="pnt-cancel-btn" style="display:none;">取消翻译</button>
    </div>
    <div class="pnt-content">
      <div class="pnt-section">
        <div class="pnt-section-title">
          <span class="pnt-toggle">▼</span> 中文翻译
        </div>
        <div class="pnt-section-body pnt-trans-body"></div>
      </div>
      <div class="pnt-section">
        <div class="pnt-section-title" style="cursor:pointer;">
          <span class="pnt-toggle">▼</span> 原文
        </div>
        <div class="pnt-section-body pnt-orig-body"></div>
      </div>
    </div>
  `;

  // Toggle original section
  const origTitle = panel.querySelectorAll('.pnt-section-title')[1];
  origTitle.addEventListener('click', () => {
    const body = panel.querySelector('.pnt-orig-body');
    const toggle = origTitle.querySelector('.pnt-toggle');
    const hidden = body.style.display === 'none';
    body.style.display = hidden ? 'block' : 'none';
    toggle.textContent = hidden ? '▼' : '▶';
  });

  panel.querySelector('.pnt-close-btn').onclick = () => removePanel();

  // Cancel button
  const cancelBtn = panel.querySelector('.pnt-cancel-btn');
  cancelBtn.addEventListener('click', cancelTranslation);
  state.cancelBtn = cancelBtn;

  document.body.appendChild(panel);
  state.panel = panel;
  state.panelBody = panel.querySelector('.pnt-trans-body');
  return panel;
}

function removePanel() {
  const existing = document.getElementById('pnt-panel');
  if (existing) existing.remove();
  state.panel = null;
  state.panelBody = null;
  state.cancelBtn = null;
}

// ─── Inline Mode: Rewrite Novel Container with Paragraphs ───

const PIXIV_CONTAINER_SELECTORS = [
  '.novel_view',
  '#novel-body',
  '.novel-p5',
  'section[data-novel]',
  '.novel-body',
  '.js-novel-container'
];

function findNovelContainer() {
  for (const sel of PIXIV_CONTAINER_SELECTORS) {
    const el = document.querySelector(sel);
    if (el && el.textContent.trim().length > 0) return el;
  }
  // Fallback: largest text block
  const candidates = document.querySelectorAll('section, article, main, div');
  let best = null;
  let bestLen = 0;
  candidates.forEach((el) => {
    const len = el.textContent?.trim().length || 0;
    if (len > bestLen && len < 200000) {
      bestLen = len;
      best = el;
    }
  });
  return best;
}

function buildInlineParagraphs(originalContent) {
  // Save original HTML for restore
  const container = findNovelContainer();
  if (!container) return null;

  state.originalHtml = container.innerHTML;

  // Clear and rebuild with paragraph pairs
  const paragraphs = textToParagraphs(htmlToText(originalContent));
  if (paragraphs.length === 0) return null;

  container.innerHTML = '';
  container.style.whiteSpace = 'normal';

  const wrapper = document.createElement('div');
  wrapper.id = 'pnt-inline-wrapper';

  paragraphs.forEach((para, idx) => {
    const block = document.createElement('div');
    block.className = 'pnt-inline-block';
    block.dataset.index = idx;

    const origP = document.createElement('p');
    origP.className = 'pnt-inline-orig';
    origP.textContent = para;

    const transP = document.createElement('p');
    transP.className = 'pnt-inline-trans';
    transP.textContent = '';

    block.appendChild(origP);
    block.appendChild(transP);
    wrapper.appendChild(block);
  });

  container.appendChild(wrapper);
  state.inlineContainer = wrapper;
  return wrapper;
}

function restoreOriginalHtml() {
  const container = findNovelContainer();
  if (container && state.originalHtml) {
    container.innerHTML = state.originalHtml;
    container.style.whiteSpace = '';
  }
  state.inlineContainer = null;
}

// ─── Streaming Rendering ────────────────────────────────────

function appendToken(token) {
  state.streamingText += token;

  if (state.mode === 'panel' && state.panelBody) {
    state.panelBody.textContent = state.streamingText;
  } else if (state.mode === 'inline' && state.inlineContainer) {
    const blocks = state.inlineContainer.querySelectorAll('.pnt-inline-block');
    const block = blocks[state.currentParaIndex];
    if (block) {
      const transP = block.querySelector('.pnt-inline-trans');
      if (transP) transP.textContent = state.streamingText;
    }
  }
}

// ─── Translation Flow ───────────────────────────────────────

async function handleTranslate() {
  if (state.translating) return;

  const novelId = getNovelIdFromUrl();
  if (!novelId) {
    showToast('未检测到小说 ID');
    return;
  }

  // Load settings
  const settings = await new Promise((resolve) => {
    chrome.storage.sync.get(['targetLang', 'displayMode'], (items) => {
      resolve({
        targetLang: items.targetLang || 'zh',
        displayMode: items.displayMode || 'panel'
      });
    });
  });

  state.novelId = novelId;
  state.targetLang = settings.targetLang;
  state.mode = settings.displayMode;
  state.translating = true;
  state.streamingText = '';
  state.currentParaIndex = 0;
  state.paraTranslations = [];

  // Prepare UI
  showStatus('正在获取原文...');
  const btn = document.getElementById('pnt-translate-btn');
  if (btn) btn.disabled = true;

  // Show cancel button (panel mode)
  if (state.mode === 'panel') {
    createPanel();
    state.cancelBtn.style.display = 'inline-block';
  }

  // Send stream request to background; tokens arrive via onMessage
  const result = await sendToBackground('TRANSLATE_NOVEL_STREAM', {
    novelId,
    targetLang: state.targetLang
  });

  if (!result) {
    finishTranslate(false, '无法启动翻译');
  }
}

function onNovelLoaded(data) {
  state.novelTitle = data.title || '';
  state.novelAuthor = data.author || '';

  if (state.mode === 'panel') {
    if (state.panel) {
      state.panel.querySelector('.pnt-title').textContent = data.title || '';
      state.panel.querySelector('.pnt-meta').textContent =
        `作者: ${data.author || ''} · 字符数: ${data.characterCount || '?'}`;
    }
    // Show original content in panel
    const origBody = state.panel?.querySelector('.pnt-orig-body');
    if (origBody) {
      origBody.innerHTML = textToParagraphs(htmlToText(data.originalContent))
        .map(p => `<p class="pnt-original-p">${escapeHtml(p)}</p>`)
        .join('');
    }
  } else {
    // inline mode: build paragraph pairs
    const wrapper = buildInlineParagraphs(data.originalContent);
    if (!wrapper) {
      // Fallback to panel mode
      state.mode = 'panel';
      createPanel();
      state.cancelBtn.style.display = 'inline-block';
      onNovelLoaded(data);
      return;
    }
  }

  showStatus('正在翻译...');
}

function onStreamToken(token) {
  appendToken(token);
}

function onStreamDone(data) {
  finishTranslate(true, null, data);
}

function onStreamError(error) {
  finishTranslate(false, error);
}

function finishTranslate(success, errorMsg, data) {
  state.translating = false;

  // Keep current translation text
  const finalText = state.streamingText;

  if (state.cancelBtn) state.cancelBtn.style.display = 'none';
  hideStatus();
  const btn = document.getElementById('pnt-translate-btn');
  if (btn) btn.disabled = false;

  if (success) {
    if (state.mode === 'panel') {
      // done; translation already streamed into panel
      showToast('翻译完成');
    } else {
      // inline done
      showToast('翻译完成');
    }
  } else {
    if (errorMsg && errorMsg.includes('abort')) {
      showToast('已取消翻译');
    } else {
      showToast('翻译失败: ' + (errorMsg || '未知错误'));
      if (state.mode === 'inline') restoreOriginalHtml();
    }
  }
}

// ─── Cancel ─────────────────────────────────────────────────

async function cancelTranslation() {
  if (!state.translating) return;
  try {
    await sendToBackground('CANCEL_TRANSLATE');
    state.translating = false;
    hideStatus();
    if (state.cancelBtn) state.cancelBtn.style.display = 'none';
    const btn = document.getElementById('pnt-translate-btn');
    if (btn) btn.disabled = false;
    showToast('已取消翻译');
  } catch (e) {
    showToast('取消失败: ' + e.message);
  }
}

// ─── Toast ──────────────────────────────────────────────────

function showToast(message) {
  const toast = document.createElement('div');
  toast.className = 'pnt-toast';
  toast.textContent = message;
  document.body.appendChild(toast);
  setTimeout(() => toast.remove(), 4000);
}

// ─── Background Messages ────────────────────────────────────

chrome.runtime.onMessage.addListener((message, sender, sendResponse) => {
  switch (message.type) {
    case 'SSE_NOVEL_LOADED':
      onNovelLoaded(message.data);
      sendResponse({ ok: true });
      break;
    case 'SSE_TOKEN':
      onStreamToken(message.token);
      sendResponse({ ok: true });
      break;
    case 'SSE_DONE':
      onStreamDone(message.data);
      sendResponse({ ok: true });
      break;
    case 'SSE_ERROR':
      onStreamError(message.error);
      sendResponse({ ok: true });
      break;
    case 'MANUAL_TRANSLATE':
      handleTranslate();
      sendResponse({ ok: true });
      break;
    case 'PING':
      sendResponse({ pong: true });
      break;
    default:
      sendResponse({ ok: false });
  }
  return true;
});

// ─── Initialize ─────────────────────────────────────────────

function init() {
  const novelId = getNovelIdFromUrl();
  if (!novelId) return;

  state.novelId = novelId;
  document.body.appendChild(createButton());

  chrome.storage.sync.get(['autoTranslate'], (items) => {
    if (items.autoTranslate !== false) {
      setTimeout(handleTranslate, 500);
    }
  });
}

if (document.readyState === 'loading') {
  document.addEventListener('DOMContentLoaded', init);
} else {
  init();
}
