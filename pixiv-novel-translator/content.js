// ============================================================
// Pixiv Novel Translator — Content Script
// ============================================================

// ─── State ───────────────────────────────────────────────────

let state = {
  novelId: null,
  translating: false,
  translationPanel: null
};

// ─── Detect Novel ID from URL ───────────────────────────────

function getNovelIdFromUrl() {
  const url = window.location.href;

  // Format: /novel/show.php?id=123456
  const match1 = url.match(/[?&]id=(\d+)/);
  if (match1) return match1[1];

  // Format: /novel/123456
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

// ─── HTML → Plain Text (strip <br> to newlines) ─────────────

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

// ─── Text to Paragraphs ─────────────────────────────────────

function textToParagraphs(text) {
  return text
    .split(/\n{2,}/)
    .map(p => p.trim())
    .filter(p => p.length > 0);
}

// ─── Create UI Elements ─────────────────────────────────────

function createButton() {
  const btn = document.createElement('button');
  btn.id = 'pnt-translate-btn';
  btn.textContent = '🌐 翻译';
  btn.style.cssText = `
    position: fixed;
    bottom: 24px;
    right: 24px;
    z-index: 99999;
    padding: 10px 20px;
    background: #1a73e8;
    color: #fff;
    border: none;
    border-radius: 24px;
    font-size: 14px;
    cursor: pointer;
    box-shadow: 0 2px 12px rgba(0,0,0,0.2);
    transition: all 0.2s;
  `;
  btn.onmouseenter = () => { btn.style.background = '#1557b0'; };
  btn.onmouseleave = () => { btn.style.background = '#1a73e8'; };
  btn.onclick = handleTranslate;
  return btn;
}

function createLoadingIndicator() {
  const el = document.createElement('div');
  el.id = 'pnt-loading';
  el.textContent = '⏳ 翻译中...';
  el.style.cssText = `
    position: fixed;
    bottom: 24px;
    right: 120px;
    z-index: 99999;
    padding: 10px 20px;
    background: #fff;
    color: #333;
    border: 1px solid #ddd;
    border-radius: 24px;
    font-size: 14px;
    box-shadow: 0 2px 12px rgba(0,0,0,0.15);
  `;
  return el;
}

// ─── Render Translation Panel ───────────────────────────────

function renderTranslationPanel(data) {
  // Remove existing panel if any
  const existing = document.getElementById('pnt-panel');
  if (existing) existing.remove();

  const panel = document.createElement('div');
  panel.id = 'pnt-panel';

  // Header
  const header = document.createElement('div');
  header.className = 'pnt-header';
  header.innerHTML = `
    <div class="pnt-header-left">
      <strong>📖 翻译结果</strong>
      <span class="pnt-title">${escapeHtml(data.title)}</span>
      <span class="pnt-meta">作者: ${escapeHtml(data.author)} · 字符数: ${data.characterCount || '?'}</span>
    </div>
    <button class="pnt-close-btn" title="关闭">✕</button>
  `;
  header.querySelector('.pnt-close-btn').onclick = () => panel.remove();

  // Content area
  const content = document.createElement('div');
  content.className = 'pnt-content';

  // Original text (collapsible)
  const originalSection = document.createElement('div');
  originalSection.className = 'pnt-section';
  originalSection.innerHTML = `
    <div class="pnt-section-title" style="cursor:pointer;">
      <span class="pnt-toggle">▼</span> 原文 (${data.title})
    </div>
    <div class="pnt-section-body">
      ${textToParagraphs(htmlToText(data.originalContent))
        .map(p => `<p class="pnt-original-p">${escapeHtml(p)}</p>`)
        .join('')}
    </div>
  `;
  originalSection.querySelector('.pnt-section-title').onclick = () => {
    const body = originalSection.querySelector('.pnt-section-body');
    const toggle = originalSection.querySelector('.pnt-toggle');
    body.style.display = body.style.display === 'none' ? 'block' : 'none';
    toggle.textContent = body.style.display === 'none' ? '▶' : '▼';
  };

  // Translation
  const translationSection = document.createElement('div');
  translationSection.className = 'pnt-section';
  translationSection.innerHTML = `
    <div class="pnt-section-title">
      <span class="pnt-toggle">▼</span> 中文翻译
    </div>
    <div class="pnt-section-body">
      ${textToParagraphs(data.translatedContent)
        .map(p => `<p class="pnt-translation-p">${escapeHtml(p)}</p>`)
        .join('')}
    </div>
  `;

  content.appendChild(originalSection);
  content.appendChild(translationSection);

  panel.appendChild(header);
  panel.appendChild(content);
  document.body.appendChild(panel);
}

// ─── Escape HTML ────────────────────────────────────────────

function escapeHtml(str) {
  const div = document.createElement('div');
  div.textContent = str;
  return div.innerHTML;
}

// ─── Main Translation Handler ───────────────────────────────

async function handleTranslate() {
  if (state.translating) return;

  const novelId = getNovelIdFromUrl();
  if (!novelId) {
    showToast('未检测到小说 ID');
    return;
  }

  state.novelId = novelId;
  state.translating = true;

  // Show loading
  const loading = createLoadingIndicator();
  document.body.appendChild(loading);
  const btn = document.getElementById('pnt-translate-btn');
  if (btn) btn.disabled = true;

  try {
    const result = await sendToBackground('TRANSLATE_NOVEL', {
      novelId,
      targetLang: 'zh'
    });
    renderTranslationPanel(result);
  } catch (error) {
    showToast('翻译失败: ' + error.message);
    console.error('[PNT] Translate error:', error);
  } finally {
    state.translating = false;
    loading.remove();
    if (btn) btn.disabled = false;
  }
}

// ─── Toast Notification ─────────────────────────────────────

function showToast(message) {
  const toast = document.createElement('div');
  toast.className = 'pnt-toast';
  toast.textContent = message;
  document.body.appendChild(toast);
  setTimeout(() => toast.remove(), 4000);
}

// ─── Listen for Popup Messages ──────────────────────────────

chrome.runtime.onMessage.addListener((message, sender, sendResponse) => {
  if (message.type === 'MANUAL_TRANSLATE') {
    handleTranslate();
  }
  if (message.type === 'PING') {
    sendResponse({ pong: true });
  }
});

// ─── Initialize on Page Load ────────────────────────────────

function init() {
  const novelId = getNovelIdFromUrl();
  if (!novelId) return;

  state.novelId = novelId;

  // Add translate button
  document.body.appendChild(createButton());

  // Auto-translate check
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
