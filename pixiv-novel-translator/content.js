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
  novelAuthor: '',
  pagedRequest: false,    // true when background uses the paged-novel flow
  fullMode: false,        // true: translate whole novel once (inline-full)
  fullTranslations: {},   // global paragraph id -> translated text
  pageStartIds: [],       // pageStartIds[p-1] = first global id of page p
  pageFlipObserver: null,  // MutationObserver for page flips (fullMode)
  autoStarted: false,      // translation was started by autoTranslate
  firstTokenReceived: false // true once the first SSE token arrived
};

// ─── Current Page (paged novels) ─────────────────────────────

// Pixiv renders only the current page of a paged novel into the DOM.
// Read the GTM marker's data-current-page so the background script can
// translate just this page (the full text stays as context only).
function getCurrentNovelPage() {
  const el = document.querySelector('#gtm-novel-work-scroll-begin-reading');
  if (!el) return 0;
  const page = parseInt(el.getAttribute('data-current-page') || '0', 10);
  return page > 0 ? page : 0;
}

// Split novel text into pages ([newpage]) then into paragraphs (\n\n),
// exactly like buildFullSource() in background.js. Returns the first
// global paragraph id of each page (1-based).
function computePageStartIds(originalContent) {
  const pages = String(originalContent || '')
    .split(/\[newpage\]/i)
    .map((p) => p.trim())
    .filter((p) => p.length > 0);
  const starts = [];
  let id = 1;
  for (const page of pages) {
    starts.push(id);
    const count = page
      .split(/\n{2,}/)
      .map((p) => p.trim())
      .filter((p) => p.length > 0).length;
    id += count;
  }
  return starts;
}

// Global paragraph id of the k-th paragraph (0-based) on page `page`.
// pageStartIds is 1-based per page; falls back to k+1 when unknown.
function globalParagraphId(page, k) {
  const start = state.pageStartIds[page - 1];
  return start ? start + k : k + 1;
}

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
  // Remove any stale button left by a previous (invalidated) content
  // script instance — otherwise clicks hit dead handlers.
  const stale = document.getElementById('pnt-mini-btn');
  if (stale) stale.remove();

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
      if (state.autoStarted) {
        // autoTranslate fired on page load without the user asking; a
        // click now means "I want to translate" — take over by cancelling
        // the automatic run and starting a fresh manual one.
        cancelTranslation();
        handleTranslate();
      } else {
        cancelTranslation();
      }
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

// Normalize whitespace so text matching tolerates DOM reflow, <br> and
// the <span class="text-count"> splitting Pixiv applies to each line.
function normalizeText(s) {
  return (s || '').replace(/\s+/g, ' ').trim();
}

// Collect the paragraph run that starts at `startEl`: consecutive <p>
// siblings (Pixiv's new page renders the whole novel body as one run of
// <p> elements). Stops at the first non-paragraph block element.
function collectParagraphRun(startEl) {
  const paras = [];
  let el = startEl;
  while (el) {
    if (el.tagName === 'P') {
      if (el.textContent.trim().length > 0) paras.push(el);
    } else if (el.tagName !== 'DIV' && el.tagName !== 'BR' && el.tagName !== 'SPAN') {
      // A non-paragraph block element breaks the run (ads, footer, …)
      break;
    }
    el = el.nextElementSibling;
  }
  return paras;
}

// Locate the novel body paragraphs in the current DOM.
// Returns an array of <p> elements, or null if they are not rendered yet
// (the new Pixiv page renders the body client-side after page load).
function findNovelParagraphs(originalContent) {
  // 1. New Pixiv page: stable GTM marker div, immediately followed by the
  //    body <p> run. `id="gtm-novel-work-scroll-begin-reading"` is a GTM
  //    instrumentation id, not a styled-components hash — stable across
  //    builds. The div itself is self-closing; the <p> are its siblings.
  const gtm = document.querySelector('#gtm-novel-work-scroll-begin-reading');
  if (gtm) {
    const paras = collectParagraphRun(gtm.nextElementSibling);
    if (paras.length) return paras;
  }

  // 2. New Pixiv page: stable business class used for text counting.
  //    Every line of the body is wrapped in <span class="text-count">.
  const textCount = document.querySelector('span.text-count');
  if (textCount) {
    const firstPara = textCount.closest('p');
    if (firstPara) {
      const paras = collectParagraphRun(firstPara);
      if (paras.length) return paras;
    }
  }

  // 3. Known Pixiv selectors (older page layouts)
  for (const sel of PIXIV_CONTAINER_SELECTORS) {
    const el = document.querySelector(sel);
    if (el && el.textContent.trim().length > 0) {
      // Prefer <p> children; fall back to non-empty direct children so
      // older layouts that render paragraphs as divs still work.
      const paras = Array.from(el.querySelectorAll('p'))
        .filter(p => p.textContent.trim().length > 0);
      if (paras.length) return paras;
      const children = Array.from(el.children)
        .filter(c => c.textContent.trim().length > 0);
      if (children.length) return children;
    }
  }

  // 4. Text-anchor fallback: locate the first paragraph of the novel body
  //    by content. Unlike the old code (which took 40 chars across
  //    paragraph boundaries and compared with startsWith), we use the
  //    first paragraph only, normalize whitespace, and match any text node
  //    that contains the anchor.
  if (originalContent) {
    const firstPara = textToParagraphs(htmlToText(originalContent))[0];
    const anchor = firstPara ? normalizeText(firstPara).slice(0, 20) : '';
    if (anchor.length >= 10) {
      const walker = document.createTreeWalker(
        document.body,
        NodeFilter.SHOW_TEXT
      );
      while (walker.nextNode()) {
        const node = walker.currentNode;
        const t = normalizeText(node.textContent);
        if (t && (t.includes(anchor) || anchor.includes(t.slice(0, 8)))) {
          let el = node.parentElement;
          // Climb up to the paragraph that holds this line
          while (el && el !== document.body && el.tagName !== 'P') {
            el = el.parentElement;
          }
          if (el && el !== document.body && el.textContent.trim().length > 10) {
            const paras = collectParagraphRun(el);
            if (paras.length) return paras;
          }
        }
      }
    }
  }
  return null;
}

function buildInlineParagraphs(originalContent) {
  // Idempotent: if we already built the inline pairs for the CURRENT
  // (still-connected) DOM, do nothing. When the user flips to another
  // page, Pixiv re-renders the body and our old elements get detached —
  // isConnected detects that and we rebuild from scratch.
  const active = state.inlineContainer && state.inlineContainer.isConnected;
  if (active && state.inlineTransEls && state.inlineTransEls.length) return state.inlineContainer;
  if (state.inlineTransEls && state.inlineTransEls.length) {
    restoreOriginalHtml(); // clear stale pairs from the previous page
  }

  // Never rebuild the Pixiv DOM (clearing innerHTML could destroy the page).
  // Instead, find the paragraph elements Pixiv rendered and insert a
  // translation div right after each one — CàiYún-style inline pairs.
  let paraEls = findNovelParagraphs(originalContent);
  if (!paraEls || paraEls.length === 0) return null;

  state.originalHtml = paraEls[0].parentElement?.innerHTML || ''; // backup for restore

  // Insert one translation div after each paragraph element
  const transEls = [];
  const currentPage = getCurrentNovelPage() || 1;
  paraEls.forEach((p, k) => {
    const trans = document.createElement('div');
    trans.className = 'pnt-inline-trans';
    // Remember which global paragraph id this div corresponds to, so a
    // full-novel stream can refill it after a page flip.
    trans.dataset.pid = String(globalParagraphId(currentPage, k));
    p.insertAdjacentElement('afterend', trans);
    transEls.push(trans);
  });

  state.inlineContainer = paraEls[0].parentElement;
  state.inlineTransEls = transEls;
  return state.inlineContainer;
}

function restoreOriginalHtml() {
  // Remove only the translation divs we inserted; leave Pixiv DOM intact
  document.querySelectorAll('.pnt-inline-trans').forEach(el => el.remove());
  document.querySelectorAll('.pnt-inline-orig').forEach(el => {
    const parent = el.parentNode;
    if (parent) {
      parent.replaceChild(document.createTextNode(el.textContent), el);
    }
  });
  state.inlineContainer = null;
  state.inlineTransEls = [];
}

// ─── Streaming Rendering ────────────────────────────────────

function appendToken(token) {
  state.streamingText += token;

  if (state.transBody) {
    if (state.mode === 'paged') {
      renderPagedStreaming();
    } else {
      state.transBody.textContent = state.streamingText;
    }
  }
  if (state.mode === 'inline' && state.inlineTransEls && state.inlineTransEls.length) {
    renderInlineStreaming();
  }
}

// Paged mode: keep Pixiv's [newpage] page breaks, render each page
// as its own block with a visible separator.
function renderPagedStreaming() {
  if (!state.transBody) return;
  const pages = state.streamingText.split(/\[newpage\]/i);
  state.transBody.innerHTML = pages
    .map(p => {
      const text = p.trim();
      if (!text) return '';
      return `<div class="pnt-page">${escapeHtml(text)}</div>`;
    })
    .join('<div class="pnt-page-break">—— [newpage] ——</div>');
}

// Try to parse the accumulated stream as JSON Lines produced by the
// paged-novel flow: one {"id":N,"text":"..."} object per line. Returns
// an array of {id, text} entries, or [] if the text is not (yet) JSON
// Lines (e.g. the model ignored the instruction, or a line is still
// streaming and incomplete).
// Progressive: a line that is still streaming (unclosed JSON) is parsed
// best-effort so the id and the text prefix received so far can render
// immediately — this keeps the typewriter effect instead of waiting for
// the whole line to arrive.
function parseJsonLines(streamText) {
  const entries = [];
  const lines = streamText.split('\n');
  for (const raw of lines) {
    const line = raw.trim();
    if (!line.startsWith('{')) continue;

    // 1) Complete JSON line — strict parse.
    if (line.endsWith('}')) {
      try {
        const obj = JSON.parse(line);
        if (obj && typeof obj.id === 'number' && typeof obj.text === 'string') {
          entries.push({ id: obj.id, text: obj.text });
          continue;
        }
      } catch (e) {
        // fall through to progressive parse
      }
    }

    // 2) Line still streaming (or malformed): extract the id and the
    //    text prefix emitted so far. Unescape \n etc. so multi-line
    //    paragraphs keep their structure as the rest of the line arrives.
    const idMatch = line.match(/"id"\s*:\s*(\d+)/);
    if (!idMatch) continue;
    const id = parseInt(idMatch[1], 10);
    const textMatch = line.match(/"text"\s*:\s*"((?:[^"\\]|\\.)*)/);
    if (textMatch) {
      let text = textMatch[1];
      try {
        text = JSON.parse('"' + text + '"');
      } catch (e) {
        // keep raw prefix
      }
      entries.push({ id, text });
    }
  }
  return entries;
}

// Fill the translation divs from JSON Lines entries, mapped by id.
function renderInlineJsonLines(entries) {
  const transEls = state.inlineTransEls || [];
  if (!transEls.length || !entries.length) return;

  // Reset every div first so a remapped stream (e.g. after the model
  // re-emits an earlier line) does not leave stale text behind.
  transEls.forEach((el) => { if (el) el.textContent = ''; });

  entries.forEach((entry) => {
    const el = transEls[entry.id - 1]; // ids are 1-based paragraph numbers
    if (el) el.textContent = entry.text;
  });
}

// Full-novel mode: refill every currently-visible translation div from
// the accumulated global map. Called on every stream token and again
// after a page flip (new page paragraphs get their matching texts).
function refillInlineFromMap() {
  const transEls = state.inlineTransEls || [];
  transEls.forEach((el) => {
    if (!el || !el.dataset || !el.dataset.pid) return;
    el.textContent = state.fullTranslations[el.dataset.pid] || '';
  });
}

// Inline mode: split accumulated translation into paragraphs and fill
// each translation div. Extra paragraphs merge into the last div instead
// of overwriting earlier ones (fixes misaligned paragraph mapping).
function renderInlineStreaming() {
  const transEls = state.inlineTransEls || [];
  if (!transEls.length) return;

  // Preferred path: paged novels stream JSON Lines. If we can parse any
  // entries, render by id — this stays aligned even if the model merges
  // or drops paragraphs (the numbered input forces explicit ids).
  if (state.pagedRequest) {
    const jsonEntries = parseJsonLines(state.streamingText);
    if (jsonEntries.length) {
      if (state.fullMode) {
        // Full-novel stream: accumulate into the global map, then refill
        // whatever page is currently visible (typing effect preserved).
        jsonEntries.forEach((e) => { state.fullTranslations[e.id] = e.text; });
        refillInlineFromMap();
      } else {
        renderInlineJsonLines(jsonEntries);
      }
      return;
    }
  }

  // Fallback (non-JSON stream): plain paragraph splitting, old behavior.
  // Strip Pixiv page-break markers the model may have kept verbatim
  const clean = state.streamingText
    .replace(/\[newpage\]/gi, '')
    .replace(/^\s+|\s+$/g, '');

  const paragraphs = clean.split(/\n{2,}/).map(p => p.trim()).filter(p => p.length > 0);
  const last = transEls.length - 1;

  paragraphs.forEach((para, idx) => {
    const el = transEls[Math.min(idx, last)];
    if (!el) return;
    if (idx > last) {
      el.textContent += '\n' + para; // merge overflow instead of overwrite
    } else {
      el.textContent = para;
    }
  });
}

// ─── Translation Flow ───────────────────────────────────────

async function handleTranslate() {
  // Bail out if this content script belongs to a dead extension instance
  // (extension was reloaded) — chrome.* calls would throw
  // "Extension context invalidated" otherwise.
  try {
    if (!chrome.runtime?.id) return;
  } catch (e) {
    showToast('扩展已更新，请刷新页面后重试');
    return;
  }

  if (state.translating) return;

  const novelId = getNovelIdFromUrl();
  if (!novelId) {
    showToast('未检测到小说 ID');
    return;
  }

  // Load settings
  const settings = await new Promise((resolve) => {
    try {
      chrome.storage.sync.get(['targetLang', 'displayMode', 'selectedPresets', 'customPrompt'], (items) => {
        resolve({
          targetLang: items.targetLang || 'zh',
          displayMode: items.displayMode || 'panel',
          selectedPresets: Array.isArray(items.selectedPresets) ? items.selectedPresets : [],
          customPrompt: items.customPrompt || ''
        });
      });
    } catch (e) {
      // Extension context invalidated — surface a friendly message.
      showToast('扩展已更新，请刷新页面后重试');
      resolve({
        targetLang: 'zh',
        displayMode: 'panel',
        selectedPresets: [],
        customPrompt: ''
      });
    }
  });

  state.novelId = novelId;
  state.targetLang = settings.targetLang;
  // inline-full: translate the whole novel once, keep refilling each
  // page as the user flips to it. Internally it renders like 'inline'.
  state.fullMode = settings.displayMode === 'inline-full';
  state.mode = state.fullMode ? 'inline' : settings.displayMode;
  state.translating = true;
  // Manual invocations (button / popup) take over from autoTranslate.
  state.autoStarted = false;
  state.firstTokenReceived = false;
  state.streamingText = '';
  state.paraTranslations = [];
  // Drop any previous full-novel watcher/state before starting fresh.
  if (state.pageFlipObserver) {
    state.pageFlipObserver.disconnect();
    state.pageFlipObserver = null;
  }
  state.fullTranslations = {};
  state.pageStartIds = [];
  // Long texts (paged Pixiv novels) can take DeepSeek a while to pre-fill;
  // if no token arrives within a few seconds, tell the user we are working
  // instead of leaving the UI looking stuck.
  if (state.firstTokenTimer) clearTimeout(state.firstTokenTimer);
  state.firstTokenTimer = setTimeout(() => {
    if (state.translating) {
      showToast('正在等待 AI 响应，长文可能需要几分钟，请稍候…');
    }
  }, 8000);
  // While DeepSeek pre-fills (can take minutes for full-novel mode),
  // show the elapsed wait on the button so it never looks frozen.
  state.waitStart = Date.now();
  if (state.waitTick) clearInterval(state.waitTick);
  state.waitTick = setInterval(() => {
    if (!state.translating) { clearInterval(state.waitTick); state.waitTick = null; return; }
    const secs = Math.round((Date.now() - state.waitStart) / 1000);
    const btn = state.miniBtn;
    if (btn) {
      btn.textContent = '等待 AI 响应 ' + secs + 's…';
      btn.style.background = '#e03131';
      btn.style.borderColor = '#e03131';
    }
  }, 1000);

  // Prepare UI: show floating window in panel & paged modes;
  // inline mode renders directly under the original paragraphs.
  if (state.mode === 'panel' || state.mode === 'paged') {
    openWindow();
  }
  updateTranslateButton('preparing');
  if (state.cancelBtn) state.cancelBtn.style.display = 'inline-block';

  // Send stream request to background; tokens arrive via onMessage
  try {
    await sendToBackground('TRANSLATE_NOVEL_STREAM', {
      novelId,
      // fullMode: background translates the whole novel (global ids);
      // otherwise translate only the page the user is reading.
      currentPage: state.fullMode ? 0 : getCurrentNovelPage(),
      fullMode: state.fullMode,
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
  state.pagedRequest = !!data.pagedRequest;
  state.fullMode = !!data.fullMode;
  if (state.fullMode) {
    state.pageStartIds = computePageStartIds(data.originalContent);
  }

  fillWindowFromNovel(data);

  // Paged mode: open the floating window (like panel) so the
  // [newpage]-preserved translation has a place to render.
  if (state.mode === 'paged') {
    openWindow();
    state.mode = 'paged';
  }

  // Inline mode: also build paragraph pairs in the page.
  // Pixiv's new page renders the novel body client-side after page load,
  // so the paragraphs may not exist yet — wait for them with a
  // MutationObserver instead of a short poll, and only fall back to the
  // floating window after a generous timeout.
  if (state.mode === 'inline') {
    waitForInlineContainer(data);
  }
  // The button stays 'preparing' (red) until the first SSE token
  // arrives — only then do we really know the AI is producing output.
  updateTranslateButton('preparing');
}

function fillWindowFromNovel(data) {
  if (!state.windowEl) return;
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

const INLINE_WAIT_TIMEOUT_MS = 20000; // generous: body renders client-side

// Full-novel mode: Pixiv re-renders the body whenever the user flips a
// page, detaching our translation divs. Watch the body and rebuild the
// current page's inline pairs + refill from the accumulated map whenever
// that happens — the SSE stream itself keeps running (never interrupted
// by page flips).
function watchPageFlips(originalContent) {
  if (state.pageFlipObserver) return; // already watching
  let timer = null;
  let lastPage = getCurrentNovelPage() || 1;
  state.pageFlipObserver = new MutationObserver(() => {
    // Debounce: Pixiv re-renders in a burst on page flip.
    if (timer) clearTimeout(timer);
    timer = setTimeout(() => {
      const page = getCurrentNovelPage() || 1;
      const active = state.inlineContainer && state.inlineContainer.isConnected;
      const pageChanged = page !== lastPage;
      lastPage = page;
      const stale = state.inlineTransEls && state.inlineTransEls.length && (!active || pageChanged);
      if (!stale) return;

      if (state.fullMode) {
        // Full-novel mode: rebuild the new page's pairs and refill from
        // the accumulated map — translations follow the user across pages.
        const wrapper = buildInlineParagraphs(originalContent);
        if (wrapper) {
          refillInlineFromMap();
        }
      } else {
        // Per-page mode: Pixiv swapped the paragraphs but kept our stale
        // translation divs (the container itself stays connected). Clear
        // them so the new page starts clean; the user translates it on
        // demand. Never let previous-page translations pile up on top.
        restoreOriginalHtml();
        state.streamingText = '';
      }
    }, 300);
  });
  // Watch both node replacement (page flip) and the data-current-page
  // attribute (in-place re-render keeps the container connected).
  state.pageFlipObserver.observe(document.body, {
    childList: true,
    subtree: true,
    attributes: true,
    attributeFilter: ['data-current-page']
  });
}

// Wait for the novel body paragraphs to appear, then build the inline
// pairs. Falls back to the floating window only after the timeout, so a
// slow page never makes inline mode silently degrade to the panel.
function waitForInlineContainer(data) {
  let observer = null;
  let timer = null;
  const deadline = Date.now() + INLINE_WAIT_TIMEOUT_MS;

  const cleanup = () => {
    if (observer) observer.disconnect();
    if (timer) clearTimeout(timer);
    observer = null;
    timer = null;
  };

  const fallbackToPanel = () => {
    console.warn('[PNT] inline container not found; showing in window instead');
    openWindow();
    state.mode = 'panel'; // render into the window from now on
    fillWindowFromNovel(data);
    showToast('未找到原文容器，已改用侧边面板显示');
    updateTranslateButton('preparing');
  };

  const tryBuild = () => {
    if (buildInlineParagraphs(data.originalContent)) {
      cleanup();
      // In full-novel mode keep watching for page flips so translations
      // appear on every page without re-translating.
      // All inline modes watch for page flips: Pixiv reuses the body
      // container and only swaps the <p>s, so our stale translation
      // divs would otherwise pile up at the top of the new page.
      watchPageFlips(data.originalContent);
      if (state.fullMode) {
        refillInlineFromMap();
      }
      updateTranslateButton('preparing');
      return true;
    }
    if (Date.now() > deadline) {
      cleanup();
      fallbackToPanel();
      return true;
    }
    return false;
  };

  // Try once immediately, then keep watching for the body to render.
  if (tryBuild()) return;
  observer = new MutationObserver(() => {
    // Debounce: body rendering fires many mutations in a burst.
    if (timer) clearTimeout(timer);
    timer = setTimeout(tryBuild, 250);
  });
  observer.observe(document.body, { childList: true, subtree: true });
  timer = setTimeout(tryBuild, 250);
}

function onStreamToken(token) {
  if (!state.translating) return; // cancelled — ignore late tokens
  // First token means the AI is really streaming — flip the button.
  if (!state.firstTokenReceived) {
    state.firstTokenReceived = true;
    if (state.waitTick) { clearInterval(state.waitTick); state.waitTick = null; }
    updateTranslateButton('ai-processing');
  }
  if (state.firstTokenTimer) {
    clearTimeout(state.firstTokenTimer);
    state.firstTokenTimer = null;
  }
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
  if (state.waitTick) { clearInterval(state.waitTick); state.waitTick = null; }
  updateTranslateButton('idle');

  if (state.cancelBtn) state.cancelBtn.style.display = 'none';

  if (success) {
    showToast('翻译完成');
  } else {
    // Treat both the raw AbortError and the background's friendly
    // "翻译已取消" as a user cancellation, not a failure.
    if (errorMsg && (errorMsg.includes('abort') || errorMsg.includes('取消'))) {
      showToast('已取消翻译');
    } else {
      // Keep whatever was already rendered: a transient network error
      // must not wipe the translations the user already received.
      showToast('翻译中断: ' + (errorMsg || '未知错误') + '（已显示的译文保留）');
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
      // Popup explicit trigger: if an auto-translate is already running,
      // cancel it first, then start fresh with current settings.
      (async () => {
        if (state.translating) {
          await cancelTranslation();
        }
        await handleTranslate();
      })();
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
  // Bail out if this content script belongs to a dead extension
  // instance (extension was reloaded) — avoid duplicate UI.
  try {
    if (!chrome.runtime?.id) return;
  } catch (e) {
    return;
  }

  const novelId = getNovelIdFromUrl();
  if (!novelId) return;

  state.novelId = novelId;
  createMiniButton();

  chrome.storage.sync.get(['autoTranslate'], (items) => {
    if (items.autoTranslate !== false) {
      state.autoStarted = true;
      setTimeout(handleTranslate, 500);
    }
  });
}

if (document.readyState === 'loading') {
  document.addEventListener('DOMContentLoaded', init);
} else {
  init();
}
