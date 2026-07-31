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
  paraTranslations: [],
  inlineContainer: null,  // inline mode: paragraph wrapper
  windowEl: null,         // floating window (expanded)
  miniBtn: null,          // bottom-right pill (minimized)
  transBody: null,        // translation output element
  cancelBtn: null,
  originalHtml: null,     // saved original container HTML (inline)
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
    try {
      chrome.runtime.sendMessage({ type, ...payload }, (response) => {
        if (chrome.runtime.lastError) {
          const msg = chrome.runtime.lastError.message || '';
          if (msg.includes('context invalidated') || msg.includes('Extension context')) {
            reject(new Error('扩展已更新，请刷新页面后重试'));
          } else {
            reject(new Error(msg));
          }
        } else if (response && response.success) {
          resolve(response.data);
        } else {
          reject(new Error(response?.error || '未知错误'));
        }
      });
    } catch (e) {
      const msg = String(e?.message || e);
      if (msg.includes('context invalidated') || msg.includes('Extension context')) {
        reject(new Error('扩展已更新，请刷新页面后重试'));
      } else {
        reject(e);
      }
    }
  });
}

// ─── Text helpers ───────────────────────────────────────────

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

function escapeHtml(str) {
  const div = document.createElement('div');
  div.textContent = str;
  return div.innerHTML;
}

// ─── Mini Button (bottom-right, minimized state) ────────────

function createMiniButton() {
  const btn = document.createElement('button');
  btn.id = 'pnt-mini-btn';
  btn.textContent = '翻译';
  btn.style.cssText = `
    position: fixed;
    bottom: 24px;
    right: 24px;
    z-index: 999999;
    padding: 10px 20px;
    background: #1a1a1a;
    color: #fff;
    border: none;
    border-radius: 24px;
    font-size: 14px;
    font-weight: 600;
    cursor: pointer;
    box-shadow: 0 2px 12px rgba(0,0,0,0.25);
    transition: background 0.2s, transform 0.2s;
  `;
  btn.onmouseenter = () => { if (!state.translating) btn.style.background = '#444'; };
  btn.onmouseleave = () => { if (!state.translating) btn.style.background = '#1a1a1a'; };
  btn.onclick = () => {
    if (state.translating) {
      cancelTranslation();
    } else {
      // handleTranslate decides whether to open the window
      // (panel mode only; inline mode renders under the original text)
      handleTranslate();
    }
  };
  document.body.appendChild(btn);
  state.miniBtn = btn;
  return btn;
}

// ─── Floating Window (expanded state) ───────────────────────

function createWindow() {
  removeWindow();
  const w = document.createElement('div');
  w.id = 'pnt-window';
  w.innerHTML = `
    <div class="pnt-header">
      <div class="pnt-header-left">
        <strong>翻译</strong>
        <span class="pnt-title"></span>
        <span class="pnt-meta"></span>
      </div>
      <div class="pnt-header-actions">
        <span class="pnt-version"></span>
        <button class="pnt-min-btn" title="缩小">–</button>
        <button class="pnt-close-btn" title="关闭">×</button>
      </div>
    </div>
    <div class="pnt-toolbar">
      <button class="pnt-cancel-btn" style="display:none;">取消翻译</button>
    </div>
    <div class="pnt-content">
      <div class="pnt-section">
        <div class="pnt-section-title">
          <span class="pnt-toggle">▼</span> 译文
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
  const origTitle = w.querySelectorAll('.pnt-section-title')[1];
  origTitle.addEventListener('click', () => {
    const body = w.querySelector('.pnt-orig-body');
    const toggle = origTitle.querySelector('.pnt-toggle');
    const hidden = body.style.display === 'none';
    body.style.display = hidden ? 'block' : 'none';
    toggle.textContent = hidden ? '▼' : '▶';
  });

  w.querySelector('.pnt-close-btn').onclick = () => removeWindow();
  w.querySelector('.pnt-min-btn').onclick = () => minimizeWindow();

  // Cancel button
  const cancelBtn = w.querySelector('.pnt-cancel-btn');
  cancelBtn.addEventListener('click', cancelTranslation);
  state.cancelBtn = cancelBtn;

  // Version badge
  const verEl = w.querySelector('.pnt-version');
  verEl.textContent = typeof EXTENSION_VERSION !== 'undefined' ? EXTENSION_VERSION : '';

  // Make draggable via header
  makeDraggable(w);

  document.body.appendChild(w);
  state.windowEl = w;
  state.transBody = w.querySelector('.pnt-trans-body');
  return w;
}

function removeWindow() {
  const existing = document.getElementById('pnt-window');
  if (existing) existing.remove();
  state.windowEl = null;
  state.transBody = null;
  state.cancelBtn = null;
}

function openWindow() {
  if (!state.windowEl) createWindow();
  state.windowEl.style.display = 'flex';
  if (state.miniBtn) state.miniBtn.style.display = 'none';
}

function minimizeWindow() {
  if (state.windowEl) state.windowEl.style.display = 'none';
  if (state.miniBtn) state.miniBtn.style.display = '';
}

function makeDraggable(w) {
  const header = w.querySelector('.pnt-header');
  let startX = 0, startY = 0, drag = false;

  header.addEventListener('mousedown', (e) => {
    if (e.target.closest('button')) return; // don't drag from buttons
    drag = true;
    startX = e.clientX - w.offsetLeft;
    startY = e.clientY - w.offsetTop;
    e.preventDefault();
  });
  document.addEventListener('mousemove', (e) => {
    if (!drag) return;
    w.style.left = Math.max(0, e.clientX - startX) + 'px';
    w.style.top = Math.max(0, e.clientY - startY) + 'px';
    w.style.right = 'auto';
  });
  document.addEventListener('mouseup', () => { drag = false; });
}

// ─── 3-State Button (black → red → blue, mirrors website) ───

function updateTranslateButton(status) {
  const set = (el, text, bg) => {
    if (!el) return;
    el.textContent = text;
    el.style.background = bg;
    el.style.borderColor = bg;
  };

  if (status === 'preparing') {
    set(state.miniBtn, '网页处理中... 点击取消', '#e03131');
  } else if (status === 'ai-processing') {
    set(state.miniBtn, 'AI 处理中... 点击取消', '#1971c2');
  } else {
    set(state.miniBtn, '翻译', '#1a1a1a');
  }
}

// ─── Inline Mode: Rewrite Novel Container with Paragraphs ───

const PIXIV_CONTAINER_SELECTORS = [
  '.novel_view',
  '#novel-body',
  '.novel-p5',
  'section[data-novel]',
  '.novel-body',
  '.js-novel-container',
  '.novel-body__content',
  '.novel-view',
  'article[data-novel]'
];

function findNovelContainer(originalContent) {
  // 1. Known Pixiv selectors (old + new page layouts)
  for (const sel of PIXIV_CONTAINER_SELECTORS) {
    const el = document.querySelector(sel);
    if (el && el.textContent.trim().length > 0) return el;
  }

  // 2. Safe anchor-based fallback: locate the element that contains the
  //    start of the novel text we got from the Pixiv API. This only
  //    matches text nodes deep inside the page — it can never select
  //    the whole page, so clearing its innerHTML is safe.
  if (originalContent) {
    const anchor = htmlToText(originalContent).slice(0, 40).trim();
    if (anchor.length >= 10) {
      const walker = document.createTreeWalker(
        document.body,
        NodeFilter.SHOW_TEXT
      );
      while (walker.nextNode()) {
        const node = walker.currentNode;
        const t = (node.textContent || '').trim();
        if (t && (t.startsWith(anchor) || t.includes(anchor.slice(0, 20)))) {
          let el = node.parentElement;
          // Climb up until the element is large enough to be the whole body text
          while (el && el !== document.body && el.textContent.trim().length < 40) {
            el = el.parentElement;
          }
          return el && el !== document.body ? el : node.parentElement;
        }
      }
    }
  }
  return null;
}

function buildInlineParagraphs(originalContent) {
  const container = findNovelContainer(originalContent);
  if (!container) return null;

  state.originalHtml = container.innerHTML;

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
  const container = findNovelContainer(state.originalHtml);
  if (container && state.originalHtml) {
    container.innerHTML = state.originalHtml;
    container.style.whiteSpace = '';
  }
  state.inlineContainer = null;
}

// ─── Streaming Rendering ────────────────────────────────────

function appendToken(token) {
  state.streamingText += token;

  if (state.transBody) {
    state.transBody.textContent = state.streamingText;
  }
  if (state.mode === 'inline' && state.inlineContainer) {
    renderInlineStreaming();
  }
}

// Inline mode: split the accumulated translation into paragraphs and
// render each paragraph into its matching original-text block.
function renderInlineStreaming() {
  const blocks = state.inlineContainer.querySelectorAll('.pnt-inline-block');
  if (!blocks.length) return;

  const paragraphs = textToParagraphs(state.streamingText);
  const last = blocks.length - 1;

  paragraphs.forEach((para, idx) => {
    const block = blocks[Math.min(idx, last)];
    if (!block) return;
    const transP = block.querySelector('.pnt-inline-trans');
    if (transP) transP.textContent = para;
  });
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
    chrome.storage.sync.get(['targetLang', 'displayMode', 'selectedPresets', 'customPrompt'], (items) => {
      resolve({
        targetLang: items.targetLang || 'zh',
        displayMode: items.displayMode || 'panel',
        selectedPresets: Array.isArray(items.selectedPresets) ? items.selectedPresets : [],
        customPrompt: items.customPrompt || ''
      });
    });
  });

  state.novelId = novelId;
  state.targetLang = settings.targetLang;
  state.mode = settings.displayMode;
  state.translating = true;
  state.streamingText = '';
  state.paraTranslations = [];

  // Prepare UI: only show floating window in panel mode;
  // inline mode renders directly under the original paragraphs.
  if (state.mode === 'panel') {
    openWindow();
  }
  updateTranslateButton('preparing');
  if (state.cancelBtn) state.cancelBtn.style.display = 'inline-block';

  // Send stream request to background; tokens arrive via onMessage
  try {
    await sendToBackground('TRANSLATE_NOVEL_STREAM', {
      novelId,
      targetLang: state.targetLang,
      selectedPresets: settings.selectedPresets,
      customPrompt: settings.customPrompt
    });
  } catch (e) {
    // Cancellation makes background reject with abort error — expected.
    if (state.translating) {
      finishTranslate(false, e.message);
    }
  }
}

function onNovelLoaded(data) {
  state.novelTitle = data.title || '';
  state.novelAuthor = data.author || '';

  if (state.windowEl) {
    state.windowEl.querySelector('.pnt-title').textContent = data.title || '';
    state.windowEl.querySelector('.pnt-meta').textContent =
      `作者: ${data.author || ''} · 字符数: ${data.characterCount || '?'}`;
    const origBody = state.windowEl.querySelector('.pnt-orig-body');
    if (origBody) {
      origBody.innerHTML = textToParagraphs(htmlToText(data.originalContent))
        .map(p => `<p class="pnt-original-p">${escapeHtml(p)}</p>`)
        .join('');
    }
  }

  // Inline mode: also build paragraph pairs in the page.
  // Pixiv renders the novel body via AJAX after page load, so the
  // container may not exist yet — poll briefly for it.
  if (state.mode === 'inline') {
    const tryInline = (attempt) => {
      const wrapper = buildInlineParagraphs(data.originalContent);
      if (wrapper) {
        updateTranslateButton('ai-processing');
        return;
      }
      if (attempt < 10) {
        setTimeout(() => tryInline(attempt + 1), 400);
      } else {
        // Container never appeared — fall back to the floating window
        // so the translation is still visible (never dead-end silently).
        console.warn('[PNT] inline container not found; showing in window instead');
        openWindow();
        state.mode = 'panel'; // render into the window from now on
        if (state.windowEl) {
          state.windowEl.querySelector('.pnt-title').textContent = data.title || '';
          state.windowEl.querySelector('.pnt-meta').textContent =
            `作者: ${data.author || ''} · 字符数: ${data.characterCount || '?'}`;
          const origBody = state.windowEl.querySelector('.pnt-orig-body');
          if (origBody) {
            origBody.innerHTML = textToParagraphs(htmlToText(data.originalContent))
              .map(p => `<p class="pnt-original-p">${escapeHtml(p)}</p>`)
              .join('');
          }
        }
        showToast('未找到原文容器，已改用侧边面板显示');
        updateTranslateButton('ai-processing');
      }
    };
    tryInline(0);
  } else {
    updateTranslateButton('ai-processing');
  }
}

function onStreamToken(token) {
  if (!state.translating) return; // cancelled — ignore late tokens
  appendToken(token);
}

function onStreamDone(data) {
  if (!state.translating) return; // cancelled — ignore
  finishTranslate(true, null, data);
}

function onStreamError(error) {
  if (!state.translating) return; // cancelled — ignore
  finishTranslate(false, error);
}

function finishTranslate(success, errorMsg, data) {
  state.translating = false;
  updateTranslateButton('idle');

  if (state.cancelBtn) state.cancelBtn.style.display = 'none';

  if (success) {
    showToast('翻译完成');
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
  } catch (e) {
    console.warn('[PNT] cancel ack failed:', e.message);
  }
  state.translating = false;
  updateTranslateButton('idle');
  if (state.cancelBtn) state.cancelBtn.style.display = 'none';
  showToast('已取消翻译');
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
  createMiniButton();

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
