// ============================================================
// Pixiv Novel Translator — Content Script
// ============================================================

// ─── State ───────────────────────────────────────────────────

let state = {
  novelId: null,
  translating: false,
  mode: 'inline',         // 'inline' | 'paged'（'panel' 仅作为 inline 找不到原文容器时的降级态）
  targetLang: 'zh',
  streamingText: '',
  paraTranslations: [],
  inlineContainer: null,  // inline mode: paragraph wrapper
  windowEl: null,         // floating window (expanded)
  miniBtn: null,          // bottom-right pill (minimized)
  transBody: null,        // translation output element
  cancelBtn: null,
  translationVisible: true, // false when the user hid the translation display
  originalContent: '',    // raw novel text, for rebuilding inline pairs
  novelTitle: '',
  novelAuthor: '',
  numberedRequest: false, // true when the response is numbered JSON Lines
  fullMode: false,        // true: translate whole novel once
  inlineSeparator: 'p',   // effective separator: 'p' or 'p-br' (see resolveInlineSeparator)
  requestedSeparator: 'p', // the user's stored choice before the text-aware fallback
  fullTranslations: {},   // global paragraph id -> translated text
  fullEntryMeta: {},      // global paragraph id -> latest JSON line metadata
  pageStartIds: [],       // pageStartIds[p-1] = first global id of page p
  pageFlipObserver: null,  // MutationObserver for page flips (fullMode)
  inlineTransEls: [],      // translation elements inserted after Pixiv paragraphs
  inlineWaitCleanup: null, // cancels a pending inline-container wait
  firstTokenTimer: null,   // pre-fill feedback timer
  waitTick: null,          // elapsed-time display timer
  watchdogTimer: null,     // liveness probe timer (see startTranslateWatchdog)
  watchdogMisses: 0,       // consecutive probes that found no live stream
  watchdogEpoch: 0,        // bumped per translation; stale probes must not count
  runId: 0,                // bumped whenever a translation (or repair) starts
  activeRequestId: null,   // requestId of the background stream this page tracks
  repairBackup: null,      // {streamText, missing} saved before a repair run
  autoStarted: false,      // translation was started by autoTranslate
  firstTokenReceived: false, // true once the first SSE token arrived
  aiConnected: false,       // upstream model response has connected
  // 一次翻译要依次经过三段不同的等待，按钮文案按它区分：
  // fetching(抓 Pixiv 原文) → connecting(等后端响应) → prefill(模型预填充) → reasoning(吐字中)
  phase: 'idle',
  expectedParagraphCount: 0, // number of numbered paragraphs in this request
  missingParagraphIds: [], // ids the model did not return at completion
  truncated: false,        // model hit its output cap; the text is a prefix of the translation
  repairMode: false,
  settingsSnapshot: null
};

// ─── Current Page (paged novels) ─────────────────────────────

// Pixiv renders only the current page of a paged novel into the DOM.
// Read the GTM marker's data-current-page so the background script can
// translate just this page (the full text stays as context only).
function getCurrentNovelPage() {
  const el = document.querySelector('#gtm-novel-work-scroll-begin-reading');
  if (!el) return 0;
  const candidates = [
    el.getAttribute('data-current-page'),
    el.getAttribute('data-page'),
    el.closest('[data-current-page]')?.getAttribute('data-current-page')
  ];
  for (const value of candidates) {
    const page = Number.parseInt(value || '', 10);
    if (page > 0) return page;
  }
  return 0;
}

const PAGE_WAIT_TIMEOUT_MS = 15000; // Pixiv's client-side body render varies a lot (observed 6-20s)

// Paged novels: the current page only exists once Pixiv has rendered the body
// (client-side). background.js asks for it before numbering a request that
// started while the marker was still missing — poll briefly so the request
// numbers the actual page instead of the whole novel. Resolves to 0 when the
// marker never shows up; the caller keeps the old whole-novel behaviour then.
function waitForCurrentPage(timeoutMs = PAGE_WAIT_TIMEOUT_MS) {
  const immediate = getCurrentNovelPage();
  if (immediate > 0) return Promise.resolve(immediate);
  return new Promise((resolve) => {
    const deadline = Date.now() + timeoutMs;
    const timer = setInterval(() => {
      const page = getCurrentNovelPage();
      if (page > 0 || Date.now() > deadline) {
        clearInterval(timer);
        resolve(page > 0 ? page : 0);
      }
    }, 250);
  });
}

// Split novel text into pages ([newpage]) then into paragraphs (\n\n),
// exactly like buildFullSource() in background.js. Returns the first
// global paragraph id of each page (1-based).
function computePageStartIds(originalContent, separator = state.inlineSeparator) {
  const pages = String(originalContent || '')
    .split(/\[newpage\]/i)
    .map((p) => p.trim())
    .filter((p) => p.length > 0);
  const starts = [];
  let id = 1;
  for (const page of pages) {
    starts.push(id);
    id += splitInlineUnits(page, separator).length;
  }
  return starts;
}

// Global paragraph id of the k-th paragraph (0-based) on page `page`.
// pageStartIds is 1-based per page; falls back to k+1 when unknown.
function globalParagraphId(page, k) {
  const start = state.pageStartIds[page - 1];
  return start ? start + k : k + 1;
}

function pageSourceFor(originalContent, page) {
  const pages = String(originalContent || '')
    .split(/\[newpage\]/i)
    .map(p => p.trim())
    .filter(Boolean);
  return pages[page - 1] || '';
}

// Ordered source paragraphs of the page currently displayed in the DOM,
// each with the paragraph id the background script assigned to it
// (buildFullSource / buildPageSource / numberAllParagraphs) and the
// tag-stripped match key used to align it against the DOM. Splitting
// mirrors background.js exactly: pages on [newpage], paragraphs on \n\n.
function sourceParagraphsForPage(originalContent, currentPage, separator = state.inlineSeparator) {
  const pages = String(originalContent || '')
    .split(/\[newpage\]/i)
    .map(p => p.trim())
    .filter(p => p.length > 0);
  const paged = currentPage > 0 && pages.length > 1;
  const pageNo = paged ? Math.min(currentPage, pages.length) : 1;
  const pageText = paged ? pages[pageNo - 1] || '' : String(originalContent || '');
  return splitInlineUnits(pageText, separator)
    .map((block, k) => ({
      id: globalParagraphId(pageNo, k),
      key: paraMatchKey(block)
    }));
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

// Background acks TRANSLATE_NOVEL_STREAM immediately, so the timeout is
// a very generous ceiling — it only fires if the service worker died
// without responding (MV3 reclaim) and the UI would otherwise hang.
function sendToBackground(type, payload, timeoutMs = 20000) {
  return new Promise((resolve, reject) => {
    let settled = false;
    const timer = setTimeout(() => {
      if (settled) return;
      settled = true;
      reject(new Error('扩展服务无响应，请刷新页面后重试'));
    }, timeoutMs);

    const finish = (fn, arg) => {
      if (settled) return;
      settled = true;
      clearTimeout(timer);
      fn(arg);
    };

    try {
      chrome.runtime.sendMessage({ type, ...payload }, (response) => {
        if (chrome.runtime.lastError) {
          const msg = chrome.runtime.lastError.message || '';
          if (msg.includes('context invalidated') || msg.includes('Extension context')) {
            finish(reject, new Error('扩展已更新，请刷新页面后重试'));
          } else {
            finish(reject, new Error(msg));
          }
        } else if (response && response.success) {
          finish(resolve, response.data);
        } else {
          finish(reject, new Error(response?.error || '未知错误'));
        }
      });
    } catch (e) {
      const msg = String(e?.message || e);
      if (msg.includes('context invalidated') || msg.includes('Extension context')) {
        finish(reject, new Error('扩展已更新，请刷新页面后重试'));
      } else {
        finish(reject, e);
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

// Numbered requests must be checked against the same page boundaries used
// by background.js. A blank result is not a successful translation: it is a
// missing paragraph that needs to remain visible to the user.
function expectedParagraphCount(originalContent, fullMode, currentPage, separator = state.inlineSeparator) {
  const pages = String(originalContent || '')
    .split(/\[newpage\]/i)
    .map(p => p.trim())
    .filter(p => p.length > 0);

  if (fullMode) {
    return pages.reduce((total, page) => total + splitInlineUnits(page, separator).length, 0);
  }

  if (currentPage > 0 && pages.length > 1) {
    const page = pages[Math.min(currentPage - 1, pages.length - 1)] || '';
    return splitInlineUnits(page, separator).length;
  }
  return splitInlineUnits(originalContent || '', separator).length;
}

// 'p' treats only blank lines (\n\n) as paragraph breaks. Much of the Pixiv
// catalogue is laid out line by line (single \n, no blank lines); splitting
// that by blank lines collapses the novel into one unit, the request carries a
// single [1] marker, and the model — told to emit one JSON line per marker but
// seeing dozens of visual lines — answers with one line per visual line. The
// reply then fails completion validation (“段落编号重复或无效”) and the whole
// translation is discarded (observed 2026-09-29 on id 29254523 with the default
// 'p' setting). When there is no blank line to split on, the visual lines ARE
// the paragraphs, so fall back to per-line splitting. background.js runs the
// same function over the same novel text; the two must stay in sync or ids and
// DOM slots drift apart.
function resolveInlineSeparator(text, requested) {
  if (requested === 'p-br') return 'p-br';
  const t = String(text || '');
  if (/\n\s*\n/.test(t)) return 'p';
  return /\n/.test(t.trim()) ? 'p-br' : 'p';
}

function splitInlineUnits(text, separator = state.inlineSeparator) {
  return String(text || '')
    .split(separator === 'p-br' ? /\n+/ : /\n{2,}/)
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
        beginTranslation();
      } else {
        cancelTranslation();
      }
    } else if (hasTranslationContent()) {
      // A translation is already rendered: toggle its display
      // (关闭翻译 ↔ 显示翻译) instead of starting a new one.
      if (state.translationVisible) {
        hideTranslationDisplay();
      } else {
        showTranslationDisplay();
      }
    } else {
      // begins a translation: opens the window in window modes, renders
      // under the original paragraphs in inline mode.
      beginTranslation();
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
      <button class="pnt-toggle-trans-btn" title="隐藏/显示译文">关闭翻译</button>
      <button class="pnt-retranslate-btn" style="display:none;" title="用当前设置重新翻译">重新翻译</button>
      <button class="pnt-repair-btn" style="display:none;" title="只补译缺失段落">补译缺失段落</button>
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

  w.querySelector('.pnt-close-btn').onclick = () => {
    removeWindow();
    // Closing the window hides the display; the mini pill becomes 显示翻译.
    if (hasTranslationContent()) {
      state.translationVisible = false;
      syncToggleLabels();
    }
  };
  w.querySelector('.pnt-min-btn').onclick = () => {
    minimizeWindow();
    // Minimizing hides the display the same way 关闭翻译 does.
    if (hasTranslationContent()) {
      state.translationVisible = false;
      syncToggleLabels();
    }
  };

  // Cancel button
  const cancelBtn = w.querySelector('.pnt-cancel-btn');
  cancelBtn.addEventListener('click', cancelTranslation);
  state.cancelBtn = cancelBtn;

  // Retranslate button: starts a fresh translation with current settings.
  // Visible whenever a translation is not in flight (finished or failed),
  // so re-running is one click away.
  const repairBtn = w.querySelector('.pnt-repair-btn');
  repairBtn.addEventListener('click', () => repairMissingParagraphs());
  state.repairBtn = repairBtn;

  const retranslateBtn = w.querySelector('.pnt-retranslate-btn');
  retranslateBtn.addEventListener('click', () => {
    // 统一入口：它内部会把正在跑的翻译取消后再开新的，且不可重入。
    beginTranslation(true);
  });
  state.retranslateBtn = retranslateBtn;

  // 这两个按钮在 HTML 里默认 display:none，此前只有 finishTranslate 会同步
  // 它们。但窗口也可能在一次翻译结束之后才被创建（inline 定位失败降级到这里，
  // 或用户手动展开），那时没人同步 —— 「补译缺失段落」与「重新翻译」就永远
  // 不可见（003 review 3.5 的同源问题：补译入口不可达）。创建时按当前状态
  // 初始化一次。
  retranslateBtn.style.display = hasTranslationContent() ? '' : 'none';
  repairBtn.style.display = state.missingParagraphIds.length ? '' : 'none';

  // Toggle the whole translation display (关闭翻译 ↔ 显示翻译): hides it
  // so the page returns to its normal state, or brings it back.
  const toggleBtn = w.querySelector('.pnt-toggle-trans-btn');
  toggleBtn.addEventListener('click', () => {
    if (state.translationVisible) {
      hideTranslationDisplay();
    } else {
      showTranslationDisplay();
    }
  });

  // Version badge
  const verEl = w.querySelector('.pnt-version');
  verEl.textContent = typeof EXTENSION_VERSION !== 'undefined' ? EXTENSION_VERSION : '';

  // Make draggable via header
  makeDraggable(w);

  document.body.appendChild(w);
  state.windowEl = w;
  state.transBody = w.querySelector('.pnt-trans-body');
  syncToggleLabels();
  return w;
}

function removeWindow() {
  const existing = document.getElementById('pnt-window');
  if (existing) existing.remove();
  state.windowEl = null;
  state.transBody = null;
  state.cancelBtn = null;
  state.retranslateBtn = null;
  state.repairBtn = null;
}

// ─── Toggle Translation Display (关闭翻译 ↔ 显示翻译) ────────

// True when a rendered translation exists that can be hidden/shown.
function hasTranslationContent() {
  return state.streamingText.length > 0
    || Object.keys(state.fullTranslations).length > 0;
}

// Hide the whole translation display so the page returns to its normal
// (pre-translation) state:
// - inline modes: remove the inserted translation divs
// - panel/paged: hide the floating window (mini pill reappears)
function hideTranslationDisplay() {
  if (state.mode === 'inline') {
    restoreOriginalHtml();
  } else {
    minimizeWindow();
  }
  state.translationVisible = false;
  syncToggleLabels();
}

// Bring the translation display back:
// - inline modes: rebuild the paragraph pairs and refill from the
//   accumulated stream / global id map
// - panel/paged: reopen the window and re-render the accumulated text
function showTranslationDisplay() {
  state.translationVisible = true;
  if (state.mode === 'inline' && state.originalContent) {
    buildInlineParagraphs(state.originalContent);
    if (state.fullMode) {
      refillInlineFromMap();
    } else {
      renderInlineStreaming();
    }
  } else {
    const hadWindow = !!state.windowEl;
    openWindow();
    // Window was recreated (was closed with ×): repaint the content.
    if (!hadWindow && state.transBody) {
      if (state.numberedRequest) {
        renderPanelFromJsonLines();
      } else if (state.mode === 'paged') {
        renderPagedStreaming();
      } else {
        state.transBody.textContent = state.streamingText;
      }
    }
  }
  syncToggleLabels();
}

// Keep the mini pill + toolbar button labels in sync with the state:
// 关闭翻译 when translations are shown, 显示翻译 when hidden.
function syncToggleLabels() {
  const label = state.translationVisible ? '关闭翻译' : '显示翻译';
  if (state.miniBtn) {
    state.miniBtn.textContent = label;
    state.miniBtn.style.background = '#1a1a1a';
    state.miniBtn.style.borderColor = '#1a1a1a';
  }
  if (state.windowEl) {
    const btn = state.windowEl.querySelector('.pnt-toggle-trans-btn');
    if (btn) btn.textContent = label;
  }
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

// 三段等待的文案与配色。以前它们共用「网络连接中」，既看不出卡在哪，也会
// 把模型预填充误读成网络问题。
const PHASE_TEXT = {
  fetching: '正在获取原文',
  waitingBody: '正在等待页面正文渲染',
  connecting: '正在连接服务器',
  prefill: 'AI 预填充中',
  reasoning: 'AI 推理中'
};
const PHASE_COLOR = {
  fetching: '#e03131',
  waitingBody: '#e03131',
  connecting: '#e03131',
  prefill: '#1971c2',
  reasoning: '#1971c2'
};

function phaseText(phase) {
  return PHASE_TEXT[phase] || PHASE_TEXT.connecting;
}

function phaseColor(phase) {
  return PHASE_COLOR[phase] || '#e03131';
}

// 状态直接写在按钮上（与右下角胶囊同一套）。窗口模式下那个胶囊会被
// openWindow() 隐藏，所以同一份文字与配色也写到窗口里的「取消翻译」按钮上
// —— 它在翻译期间是唯一可见的按钮，点击即取消。译文区只放译文。
function updateTranslateButton(status) {
  const set = (el, text, bg) => {
    if (!el) return;
    el.textContent = text;
    el.style.background = bg;
    el.style.borderColor = bg;
  };

  if (status === 'idle') {
    state.phase = 'idle';
    // 把取消按钮交回样式表（黑底「取消翻译」），下次翻译开始时再接管。
    if (state.cancelBtn) {
      state.cancelBtn.textContent = '取消翻译';
      state.cancelBtn.style.background = '';
      state.cancelBtn.style.borderColor = '';
    }
    // idle: with a rendered translation the pill toggles its display
    // (关闭翻译/显示翻译); otherwise it starts a new translation.
    if (hasTranslationContent()) {
      syncToggleLabels();
    } else {
      set(state.miniBtn, '翻译', '#1a1a1a');
    }
    return;
  }

  // 非 idle：文案与配色完全由当前阶段决定（status 参数只有 'idle' 这个值
  // 需要区分，其余调用点统一走阶段文案）。
  const phase = state.phase || 'connecting';
  const label = phaseText(phase) + '...';
  set(state.miniBtn, label, phaseColor(phase));
  set(state.cancelBtn, label, phaseColor(phase));
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

// Pixiv special tags never appear as literal text in the rendered DOM:
// [chapter:X]/[b:X]/[i:X]/[e:X] render as their inner text X, ruby
// [[rb:base> reading]] keeps only the base text, and control tags
// ([newpage], [jump:N], [uploadedimage:N], [[jumpuri:…]]…) render as
// nothing. Convert them the same way before matching source text against
// the DOM, or tag-led novels (bold/italics formatting update) can never be
// anchored and every paragraph id would shift by the number of tag
// paragraphs.
//
// The double-bracket ruby form is Pixiv's actual syntax and must be handled
// FIRST: the generic `[rb:…]` rule below would otherwise match from the
// second "[" and leave a stray "[" plus the reading behind (observed:
// `[[rb:山咲> やまさき]]もも` became `[山咲> やまさき]もも`, which then never
// matched the DOM's `山咲もも`).
function pixivTagText(s) {
  return (s || '')
    .replace(/\[\[rb:([^>＞,\]]*)(?:[>＞,][^\]]*)?\]\]/gi, '$1')
    .replace(/\[rb:([^,\]]*)[^\]]*\]/gi, '$1')
    .replace(/\[\[[^\]]*\]\]/g, '')
    .replace(/\[(?:jump|newpage|[a-z]*image)[^\]]*\]/gi, '')
    .replace(/\[([a-z]+):([^\]]*)\]/gi, '$2');
}

// Match key for source-vs-DOM paragraph comparison: strip Pixiv tags and
// ALL whitespace. Pixiv may join per-line <span>s without spaces while
// the source separates lines with \n — both sides must squash to the
// same key or a perfectly aligned paragraph would fail to match.
function paraMatchKey(s) {
  return pixivTagText(s || '').replace(/\s+/g, '');
}

// Text of a DOM element used for paragraph matching: ruby readings (<rt>/
// <rp>) are skipped so the DOM side keeps only the base text, mirroring the
// source side where [[rb:base> reading]] normalises to `base`. Without
// this, `[[rb:山咲> やまさき]]もも` can never match — its textContent is
// `山咲やまさきもも`. Plain paragraphs are unchanged (no rt/rp inside).
function domMatchText(el) {
  if (!el) return '';
  let out = '';
  const walker = document.createTreeWalker(el, NodeFilter.SHOW_TEXT);
  while (walker.nextNode()) {
    const node = walker.currentNode;
    const parent = node.parentElement;
    if (parent && (parent.tagName === 'RT' || parent.tagName === 'RP')) continue;
    out += node.nodeValue || '';
  }
  return out;
}

// Order-preserving source-vs-DOM paragraph match: exact equality always
// wins (even for one-character [b:壱] paragraphs); otherwise the shorter
// key must be a prefix of the longer one by a safe margin (handles ruby
// readings appended to the DOM text and partially rendered elements).
function paraTextMatches(domKey, srcKey) {
  if (!domKey || !srcKey) return false;
  if (domKey === srcKey) return true;
  const n = Math.min(domKey.length, srcKey.length);
  return n >= 6 && domKey.slice(0, n) === srcKey.slice(0, n);
}

// A leaf line element: a DIV/SPAN that directly wraps line text, with no
// nested block containers inside (Pixiv may render each body line as its
// own <div> instead of a <p>).
function isLeafLineEl(el) {
  return !el.querySelector('p, div, section, article, ul, ol, table, h1, h2, h3, h4, h5, h6');
}

// Collect the paragraph run that starts at `startEl`: consecutive <p>
// siblings (Pixiv's new page renders the whole novel body as one run of
// <p> elements). With `acceptDivs`, leaf <div>/<span> line elements count
// as paragraphs too. Stops at the first non-paragraph block element.
function collectParagraphRun(startEl, acceptDivs = false) {
  const paras = [];
  let el = startEl;
  while (el) {
    // 跳过我们自己插入的节点。译文 div 是「叶子 div + 文本非空」，当
    // acceptDivs 为真时正好命中下面的条件，会被当成正文段落 —— 后果有两个：
    // 一是段落收集可能在这里断掉，二是 findNovelParagraphs() 的返回随
    // 译文渲染而变化，让翻页监视器的 DOM 签名抖动，于是把「刚画出的译文」
    // 误判成翻页残留并清掉（表现为点「显示翻译」后闪一下就全没了）。
    // 用 continue 跳过而不是 break，保证后面的正文段落仍能被收集到。
    if (el.classList && (el.classList.contains('pnt-inline-trans')
        || el.classList.contains('pnt-inline-unit'))) {
      el = el.nextElementSibling;
      continue;
    }
    if (el.tagName === 'P') {
      if (el.textContent.trim().length > 0) paras.push(el);
    } else if (acceptDivs && (el.tagName === 'DIV' || el.tagName === 'SPAN')
        && el.textContent.trim().length > 0 && isLeafLineEl(el)) {
      paras.push(el);
    } else if (el.tagName !== 'DIV' && el.tagName !== 'BR' && el.tagName !== 'SPAN') {
      // A non-paragraph block element breaks the run (ads, footer, …)
      break;
    }
    el = el.nextElementSibling;
  }
  return paras;
}

// Find the first paragraph run at or after `startEl`, tolerating the two
// layouts the strict sibling walk cannot handle: the run nested one or
// two wrapper levels deep (the <p> run is not the marker's sibling), and
// non-paragraph elements (e.g. the [chapter:…] heading) preceding it.
function findParagraphRunAfter(startEl, acceptDivs) {
  let el = startEl;
  for (let i = 0; el && i < 6; i++) {
    // (a) the element itself starts the run
    let paras = collectParagraphRun(el, acceptDivs);
    if (paras.length) return paras;
    // (b) the run is nested inside this element (wrapper container)
    if (el.querySelector) {
      const firstP = el.querySelector('p');
      if (firstP) {
        paras = collectParagraphRun(firstP, acceptDivs);
        if (paras.length) return paras;
      }
    }
    el = el.nextElementSibling;
  }
  return [];
}

// Locate the novel body paragraphs in the current DOM.
// Returns an array of <p> elements, or null if they are not rendered yet
// (the new Pixiv page renders the body client-side after page load).
function findNovelParagraphs(originalContent) {
  // 1. New Pixiv page: stable GTM marker div near the body. The <p> run
  //    may be the marker's immediate siblings (older layout) or nested
  //    inside a following wrapper element, and a [chapter:…] heading may
  //    precede the paragraphs — scan a few siblings forward and descend
  //    into wrappers instead of relying on an exact sibling layout.
  //    `id="gtm-novel-work-scroll-begin-reading"` is a GTM instrumentation
  //    id, not a styled-components hash — stable across builds.
  const gtm = document.querySelector('#gtm-novel-work-scroll-begin-reading');
  if (gtm && gtm.nextElementSibling) {
    let paras = findParagraphRunAfter(gtm.nextElementSibling, false);
    if (paras.length) return paras;
    // Layouts that render each body line as a leaf <div> instead of <p>.
    paras = findParagraphRunAfter(gtm.nextElementSibling, true);
    if (paras.length) return paras;
  }

  // 2. New Pixiv page: stable business class used for text counting.
  //    Every line of the body is wrapped in <span class="text-count">.
  const textCount = document.querySelector('span.text-count');
  if (textCount) {
    const lineEl = textCount.closest('p')
      || (textCount.parentElement && isLeafLineEl(textCount.parentElement)
        ? textCount.parentElement
        : null)
      || textCount.closest('div');
    if (lineEl) {
      const paras = collectParagraphRun(lineEl, lineEl.tagName !== 'P');
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

  // 4. Text-anchor fallback: locate the first real text paragraph of the
  //    novel body by content. Pixiv special tags ([chapter:…], [b:…] …)
  //    never appear as literal text in the DOM, so they are stripped (and
  //    tag-only paragraphs skipped) before picking the anchor — otherwise
  //    tag-led novels can never be anchored to the page.
  if (originalContent) {
    const anchorPara = textToParagraphs(htmlToText(originalContent))
      .map(b => paraMatchKey(b))
      .find(t => t.length >= 10);
    if (anchorPara) {
      const anchor = anchorPara.slice(0, 20);
      const walker = document.createTreeWalker(
        document.body,
        NodeFilter.SHOW_TEXT
      );
      while (walker.nextNode()) {
        const node = walker.currentNode;
        const t = paraMatchKey(domMatchText(node));
        if (t && (t.includes(anchor) || (t.length >= 6 && anchor.includes(t.slice(0, 8))))) {
          // Climb up to the element that holds (at most) this paragraph,
          // then collect the run from there.
          let el = node.parentElement;
          let line = el;
          while (el && el !== document.body) {
            if (paraMatchKey(domMatchText(el)).length > anchorPara.length + 8) break;
            line = el;
            el = el.parentElement;
          }
          if (line && line !== document.body) {
            const paras = findParagraphRunAfter(line, line.tagName !== 'P');
            if (paras.length) return paras;
          }
          // Fall back to the paragraph element holding this text node.
          let p = node.parentElement;
          while (p && p !== document.body && p.tagName !== 'P') {
            p = p.parentElement;
          }
          if (p && p !== document.body && p.textContent.trim().length > 10) {
            const paras = collectParagraphRun(p, false);
            if (paras.length) return paras;
          }
        }
      }
    }
  }
  return null;
}

function extractDomLines(element) {
  const lines = [];
  let buffer = '';
  const walker = document.createTreeWalker(element, NodeFilter.SHOW_ALL);
  while (walker.nextNode()) {
    const node = walker.currentNode;
    if (node.nodeType === Node.TEXT_NODE) {
      // Ruby readings are not part of the source text (see domMatchText).
      const parent = node.parentElement;
      if (parent && (parent.tagName === 'RT' || parent.tagName === 'RP')) continue;
      buffer += node.nodeValue || '';
    }
    else if (node.nodeType === Node.ELEMENT_NODE && node.tagName === 'BR') {
      lines.push(buffer);
      buffer = '';
    }
  }
  lines.push(buffer);
  return lines.filter(line => line.trim());
}

function domInlineUnitsForParagraph(element, separator = state.inlineSeparator) {
  if (separator !== 'p-br' || !element.querySelector('br')) return [element];

  // Pixiv owns the original children. Keep them untouched and create one
  // live marker after each line's text using Range boundary points.
  const lines = [];
  let buffer = '';
  const walker = document.createTreeWalker(element, NodeFilter.SHOW_ALL);
  const breaks = [];
  while (walker.nextNode()) {
    const node = walker.currentNode;
    if (node.nodeType === Node.TEXT_NODE) {
      buffer += node.nodeValue || '';
    } else if (node.nodeType === Node.ELEMENT_NODE && node.tagName === 'BR') {
      lines.push(buffer);
      buffer = '';
      breaks.push(node);
    }
  }
  lines.push(buffer);
  const nonEmptyLines = lines.filter(line => line.trim());
  if (nonEmptyLines.length <= 1) return [element];

  const markers = [];
  let lineIndex = 0;
  breaks.forEach((br) => {
    if (lines[lineIndex]?.trim()) {
      const marker = document.createElement('span');
      marker.className = 'pnt-inline-unit';
      marker.dataset.pntAnchor = 'true';
      marker.style.cssText = 'display:inline-block;width:0;overflow:hidden;';
      br.parentNode.insertBefore(marker, br);
      markers.push(marker);
    }
    lineIndex++;
  });
  if (lines[lineIndex]?.trim()) {
    const marker = document.createElement('span');
    marker.className = 'pnt-inline-unit';
    marker.dataset.pntAnchor = 'true';
    marker.style.cssText = 'display:inline-block;width:0;overflow:hidden;';
    element.appendChild(marker);
    markers.push(marker);
  }
  return markers.length ? markers : [element];
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

  // Align the DOM paragraphs to the numbered source paragraphs BY TEXT,
  // not by position. Tag paragraphs ([chapter:…]/[b:…] — no literal DOM
  // text) and non-body elements (ads, images) can no longer shift every
  // following id: an element that matches no source paragraph simply
  // gets no translation slot instead of pushing all later translations
  // one slot down (the root cause of 错段 on tag-led novels).
  const src = sourceParagraphsForPage(originalContent, getCurrentNovelPage(), state.inlineSeparator);
  const transEls = [];
  let si = 0;
  let unmatchedCount = 0;
  const LOOKAHEAD = 6; // consecutive source paragraphs without a DOM slot
  paraEls.forEach((p) => {
    const units = domInlineUnitsForParagraph(p);
    units.forEach((unit, unitIndex) => {
      const t = state.inlineSeparator === 'p-br' && unit.dataset.pntAnchor
        ? paraMatchKey(extractDomLines(p)[unitIndex] || '')
        : paraMatchKey(domMatchText(unit));
      if (!t) return; // empty element (image, ad) — no slot
      let matched = -1;
      const end = Math.min(src.length, si + LOOKAHEAD);
      for (let j = si; j < end; j++) {
        if (paraTextMatches(t, src[j].key)) { matched = j; break; }
      }
      if (matched < 0) {
        unmatchedCount++;
        return; // unmatched element — leave it alone
      }
      si = matched + 1;
      const trans = document.createElement('div');
      trans.className = 'pnt-inline-trans';
      trans.dataset.pid = String(src[matched].id);
      trans.dataset.sourceKey = src[matched].key;
      unit.insertAdjacentElement('afterend', trans);
      transEls.push(trans);
    });
  });

  console.debug('[PNT][inline-map]', {
    page: getCurrentNovelPage(),
    domCount: paraEls.length,
    sourceCount: src.length,
    matchedCount: transEls.length,
    unmatchedCount,
    firstDomKey: paraEls[0] ? paraMatchKey(domMatchText(paraEls[0])).slice(0, 40) : '',
    lastDomKey: paraEls.length ? paraMatchKey(domMatchText(paraEls[paraEls.length - 1])).slice(0, 40) : '',
    firstSourceKey: src[0]?.key.slice(0, 40) || '',
    lastSourceKey: src[src.length - 1]?.key.slice(0, 40) || '',
    firstPid: transEls[0]?.dataset.pid || '',
    lastPid: transEls[transEls.length - 1]?.dataset.pid || ''
  });

  // Nothing matched: the run is not the novel body — keep looking / fall
  // back to the panel instead of rendering under the wrong elements.
  if (!transEls.length) return null;

  state.inlineContainer = paraEls[0].parentElement;
  state.inlineTransEls = transEls;
  return state.inlineContainer;
}

function restoreOriginalHtml() {
  // Remove only the translation divs we inserted; leave Pixiv DOM intact
  document.querySelectorAll('.pnt-inline-trans, .pnt-inline-unit[data-pnt-anchor="true"]').forEach(el => el.remove());
  state.inlineContainer = null;
  state.inlineTransEls = [];
}

// ─── Streaming Rendering ────────────────────────────────────

function appendToken(token) {
  state.streamingText += token;

  if (state.transBody) {
    if (state.numberedRequest) {
      // JSON Lines stream landed in the panel renderer (inline mode
      // degraded to panel, or a paged request): parse and join it, otherwise
      // the window shows raw {"id":..,"text":..} lines.
      renderPanelFromJsonLines();
    } else if (state.mode === 'paged') {
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

// Panel renderer for JSON Lines streams: join entries by id in order.
// Same id takes the latest (most complete) text, so the progressive
// parse keeps the typewriter effect as each line finishes.
function renderPanelFromJsonLines() {
  if (!state.transBody) return;
  const entries = parseJsonLines(state.streamingText);
  if (!entries.length) {
    const warning = state.missingParagraphIds.length > 0
      ? `⚠ 未能解析完整的段落译文，请点击重新翻译。\n\n`
      : '';
    state.transBody.textContent = warning + state.streamingText;
    return;
  }
  const byId = new Map();
  entries.forEach((e) => byId.set(e.id, e.text));
  const ids = [...byId.keys()].sort((a, b) => a - b);
  const warning = state.missingParagraphIds.length > 0
    ? `⚠ 以下段落未返回译文：${state.missingParagraphIds.join('、')}。请点击重新翻译。\n\n`
    : '';
  state.transBody.textContent = warning + ids.map((id) => byId.get(id)).join('\n\n');
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
        const id = Number(obj && obj.id);
        if (obj && Number.isInteger(id) && typeof obj.text === 'string') {
          entries.push({ id, text: obj.text, complete: true });
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
      entries.push({ id, text, complete: false });
    }
  }
  return entries;
}

// 缺失提示同时是补译入口。inline 模式没有悬浮窗，而「补译缺失段落」按钮
// 只存在于窗口里 —— 用户在页面上看到的本来只有一行死文字（003 review 3.1
// 的后果：缺失时没有任何补译入口）。这里把提示本身变成可点击的入口。
function makeMissingHint(el, id) {
  el.textContent = `⚠ 第 ${id} 段翻译缺失 — 点击此处补译`;
  el.classList.add('pnt-missing-hint');
  el.onclick = (event) => {
    event.preventDefault();
    event.stopPropagation();
    repairMissingParagraphs();
  };
}

function clearMissingHint(el) {
  if (!el.classList) return;
  el.classList.remove('pnt-missing-hint');
  el.onclick = null;
}

// Fill the translation divs from JSON Lines entries, mapped by id.
function renderInlineJsonLines(entries) {
  const transEls = state.inlineTransEls || [];
  if (!transEls.length) return;

  // Map divs by the pid assigned when the pairs were built (text-based
  // alignment) — never by array position: a single unmatched element
  // would otherwise shift every later translation onto the wrong line.
  const byPid = new Map();
  transEls.forEach((el) => {
    if (el && el.dataset && el.dataset.pid) byPid.set(el.dataset.pid, el);
  });

  // Re-render the complete accumulated stream. During SSE streaming the
  // last JSON object is normally incomplete; clearing the DOM before
  // parsing that prefix makes already translated paragraphs blink empty
  // and, more importantly, allows a late partial line to replace a
  // complete line. Keep the latest entry per id and only replace a value
  // when the new entry is complete or no value exists yet.
  const latest = new Map();
  entries.forEach((entry) => {
    const previous = latest.get(entry.id);
    if (!previous || entry.complete || !previous.complete) latest.set(entry.id, entry);
  });

  transEls.forEach((el) => {
    if (!el) return;
    const entry = latest.get(Number(el.dataset.pid));
    clearMissingHint(el);
    el.textContent = entry ? entry.text : '';
  });

  // Never leave an omitted paragraph looking like an intentional blank line.
  state.missingParagraphIds.forEach((id) => {
    const el = byPid.get(String(id));
    if (el && !el.textContent) makeMissingHint(el, id);
  });
}

// Full-novel mode: refill every currently-visible translation div from
// the accumulated global map. Called on every stream token and again
// after a page flip (new page paragraphs get their matching texts).
function refillInlineFromMap() {
  const transEls = state.inlineTransEls || [];
  transEls.forEach((el) => {
    if (!el || !el.dataset || !el.dataset.pid) return;
    const id = Number(el.dataset.pid);
    const text = state.fullTranslations[el.dataset.pid];
    clearMissingHint(el);
    if (text) {
      el.textContent = text;
    } else if (state.missingParagraphIds.includes(id)) {
      makeMissingHint(el, id);
    } else {
      el.textContent = '';
    }
  });
}

// Inline mode: split accumulated translation into paragraphs and fill
// each translation div. Extra paragraphs merge into the last div instead
// of overwriting earlier ones (fixes misaligned paragraph mapping).
function validateNumberedStream() {
  const entries = parseJsonLines(state.streamingText);
  const counts = new Map();
  entries.forEach((entry) => {
    // A numbered paragraph with an empty text value is still missing: all
    // source paragraphs are non-empty after request construction.
    if (entry.complete && entry.text.trim().length > 0) {
      counts.set(entry.id, (counts.get(entry.id) || 0) + 1);
    }
  });
  const missing = [];
  const invalid = [];
  for (let id = 1; id <= state.expectedParagraphCount; id++) {
    if (!counts.has(id)) missing.push(id);
  }
  counts.forEach((count, id) => {
    if (id < 1 || id > state.expectedParagraphCount) invalid.push(id);
    if (count > 1) invalid.push(`${id}（重复）`);
  });
  return { entries, missing, invalid };
}

function renderInlineStreaming() {
  const transEls = state.inlineTransEls || [];
  if (!transEls.length) return;

  // Preferred path: numbered requests stream JSON Lines. If we can parse
  // any entries, render by id — this stays aligned even if the model
  // merges or drops paragraphs (the numbered input forces explicit ids).
  if (state.numberedRequest) {
    const jsonEntries = parseJsonLines(state.streamingText);
    if (jsonEntries.length) {
      if (state.fullMode) {
        // Full-novel stream: accumulate into the global map, then refill
        // whatever page is currently visible (typing effect preserved).
        jsonEntries.forEach((e) => {
          const key = String(e.id);
          const previous = state.fullEntryMeta[key];
          if (!previous || e.complete || !previous.complete) {
            state.fullTranslations[key] = e.text;
            state.fullEntryMeta[key] = e;
          }
        });
        refillInlineFromMap();
      } else {
        renderInlineJsonLines(jsonEntries);
      }
      return;
    }
    // Once completion has established that the numbered response is
    // incomplete, do not fall back to positional splitting: that would put
    // later paragraphs under the wrong original lines. Show explicit gaps.
    if (state.missingParagraphIds.length) {
      if (state.fullMode) refillInlineFromMap();
      else renderInlineJsonLines([]);
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
    // 这条路径也要摘掉缺失提示：否则元素会带着橙色样式与 onclick 继续存在，
    // 用户点任意一段译文都会触发一次全量补译（多付 token）
    // （003 review round2 N7）。
    clearMissingHint(el);
    if (idx > last) {
      el.textContent += '\n' + para; // merge overflow instead of overwrite
    } else {
      el.textContent = para;
    }
  });
}

// ─── Translation Flow ───────────────────────────────────────

// 所有「开始一次翻译」的入口都必须走这里，而不是直接调 handleTranslate。
//
// 原因（003 review 3.6/3.7）：MANUAL_TRANSLATE 与重新翻译按钮走的是
// 「先取消旧的、再开新的」序列，而 cancelTranslation 内部 await 了一次
// 消息往返（最长 20 秒）。这段窗口里 state.translating 已经是 false，
// 页面按钮上的第二次点击会插进来再开一次 —— 两个 handleTranslate 交错，
// 真的会向后台发两次请求（用户多付一份 token），而且后一次的 watchdog
// 会被前一次的 finishTranslate 停掉。用 startingTranslation 把这整段
// 序列（含取消）框成一个不可重入的整体。
let startingTranslation = false;

async function beginTranslation(skipCache = false) {
  if (startingTranslation) return;
  startingTranslation = true;
  try {
    if (state.translating) {
      await cancelTranslation();
      // 取消失败（例如后台已经换了实例）时不要贸然再发一次请求。
      if (state.translating) return;
    }
    await handleTranslate(skipCache, true);
  } finally {
    startingTranslation = false;
  }
}

async function handleTranslate(skipCache = false, fromBegin = false) {
  // Bail out if this content script belongs to a dead extension instance
  // (extension was reloaded) — chrome.* calls would throw
  // "Extension context invalidated" otherwise.
  try {
    if (!chrome.runtime?.id) return;
  } catch (e) {
    showToast('扩展已更新，请刷新页面后重试');
    return;
  }

  // fromBegin 是 beginTranslation 内部的调用标记：启动序列已经持有互斥锁，
  // 只有它能穿过 startingTranslation 这道门（003 review 3.6/3.7）。
  if (state.translating || (startingTranslation && !fromBegin)) return;

  const novelId = getNovelIdFromUrl();
  if (!novelId) {
    showToast('未检测到小说 ID');
    return;
  }

  // Load settings。整个读取包一层超时：chrome.storage 的回调在极端故障下
  // 可能永不触发，而它卡住的就是启动序列的互斥锁（startingTranslation）——
  // 一旦永久挂起，此后所有入口都会静默失效，用户点任何按钮都没反应
  // （003 review round2 N10）。超时后走默认设置，翻译仍能开始。
  const SETTINGS_TIMEOUT_MS = 5000;
  const settings = await new Promise((resolve) => {
    let settled = false;
    const finish = (value) => {
      if (settled) return;
      settled = true;
      clearTimeout(timer);
      resolve(value);
    };
    const defaults = {
      targetLang: 'zh',
      displayMode: 'inline',
      selectedPresets: [],
      customPrompt: '',
      thinkingType: 'disabled',
      model: '',
      modelProfileId: 0,
      inlineScope: 'page',
      inlineSeparator: 'p'
    };
    const timer = setTimeout(() => {
      console.warn('[PNT] storage.sync.get timed out; using default settings');
      recordContentError('读取插件设置超时，已使用默认设置');
      finish(defaults);
    }, SETTINGS_TIMEOUT_MS);
    try {
      chrome.storage.sync.get(['targetLang', 'displayMode', 'inlineScope', 'inlineSeparator', 'selectedPresets', 'customPrompt', 'thinkingType', 'model', 'modelProfileId'], (items) => {
        finish({
          targetLang: items.targetLang || 'zh',
          // 侧边面板已从界面移除：它的窗口呈现由分页模式接管，老配置
          // （storage 里存着 'panel'）映射过来，以免老用户升级后卡在一个
          // 界面上选不到的状态。
          displayMode: items.displayMode === 'panel'
            ? 'paged'
            : (items.displayMode || 'inline'),
          selectedPresets: Array.isArray(items.selectedPresets) ? items.selectedPresets : [],
          customPrompt: items.customPrompt || '',
          thinkingType: items.thinkingType || 'disabled',
          model: items.model || '',
          modelProfileId: items.modelProfileId !== null && items.modelProfileId !== undefined && items.modelProfileId !== ''
            ? Number(items.modelProfileId) : 0,
          inlineScope: items.inlineScope || (items.displayMode === 'inline-full' ? 'full' : 'page'),
          inlineSeparator: items.inlineSeparator || 'p'
        });
      });
    } catch (e) {
      // Extension context invalidated — surface a friendly message.
      showToast('扩展已更新，请刷新页面后重试');
      finish(defaults);
    }
  });

  state.novelId = novelId;
  state.targetLang = settings.targetLang;
  // Keep the legacy paged inline path stable: inline + page scope renders
  // only the visible page, while full scope explicitly enables global IDs.
  // The old inline-full setting remains backward compatible.
  state.fullMode = settings.displayMode === 'inline-full'
    || (settings.displayMode === 'inline' && settings.inlineScope === 'full');
  state.inlineSeparator = settings.inlineSeparator === 'p-br' ? 'p-br' : 'p';
  state.requestedSeparator = state.inlineSeparator; // effective value is resolved in onNovelLoaded against the novel text
  state.mode = settings.displayMode;
  state.translating = true;
  // 补译状态必须在这里就清干净，而不是等到下面的重置块末尾：从这一行到
  // 重置块之间没有任何 await，但「上一轮补译刚结束、新一轮刚置 translating」
  // 的交错窗口里，迟到的补译事件本可以落进来（003 review round3 R1）。
  // requestId 校验是结构性防线（见 onMessage），这里是第二道。
  state.repairMode = false;
  state.repairBackup = null;
  if (state.inlineWaitCleanup) {
    state.inlineWaitCleanup();
    state.inlineWaitCleanup = null;
  }
  if (state.retranslateBtn) state.retranslateBtn.style.display = 'none';
  // Manual invocations (button / popup) take over from autoTranslate.
  state.autoStarted = false;
  state.firstTokenReceived = false;
  state.aiConnected = false;
  state.streamingText = '';
  state.paraTranslations = [];
  // A fresh translation is shown by default, even if the previous one
  // was hidden with 关闭翻译.
  state.translationVisible = true;
  // Drop any previous full-novel watcher/state before starting fresh.
  if (state.pageFlipObserver) {
    state.pageFlipObserver.disconnect();
    state.pageFlipObserver = null;
  }
  state.fullTranslations = {};
  state.fullEntryMeta = {};
  state.pageStartIds = [];
  state.expectedParagraphCount = 0;
  state.missingParagraphIds = [];
  state.truncated = false;
  // 一并丢掉上一轮补译的备份（见上方 translating = true 处的说明，这里
  // 保留一次冗余清理：重置块与入口都清，任一漏写都不会漏到下一轮）。
  state.repairMode = false;
  state.repairBackup = null;
  // 这一轮的身份。迟到的事件（尤其是被取消的补译的 continuation）靠它
  // 判断自己是否还属于当前这一轮，避免去动新翻译的状态（N4/N5/N6）。
  state.runId += 1;
  const myRun = state.runId;
  state.settingsSnapshot = settings;
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
  startTranslateWatchdog();
  if (state.waitTick) clearInterval(state.waitTick);
  state.waitTick = setInterval(() => {
    if (!state.translating) { clearInterval(state.waitTick); state.waitTick = null; return; }
    const secs = Math.round((Date.now() - state.waitStart) / 1000);
    // 首 token 一到就进入推理阶段（缓存命中时后端不发 ai-connected，
    // 靠 firstTokenReceived 兜底）。
    if (state.aiConnected || state.firstTokenReceived) {
      state.phase = 'reasoning';
    }
    const phase = state.phase || 'connecting';
    const statusText = phaseText(phase) + ' ' + secs + 's…';
    const color = phaseColor(phase);
    // 同一份进度写到右下角胶囊和窗口里的取消按钮上（窗口打开时胶囊被隐藏）。
    [state.miniBtn, state.cancelBtn].forEach((btn) => {
      if (!btn) return;
      btn.textContent = statusText;
      btn.style.background = color;
      btn.style.borderColor = color;
    });
  }, 1000);

  // Prepare UI: show floating window in panel & paged modes;
  // inline mode renders directly under the original paragraphs.
  if (state.mode === 'panel' || state.mode === 'paged') {
    openWindow();
  }
  // 从这一刻起（到后端响应头抵达为止）是在抓 Pixiv 原文。
  state.phase = 'fetching';
  updateTranslateButton('preparing');
  if (state.cancelBtn) state.cancelBtn.style.display = 'inline-block';

  // Send stream request to background; tokens arrive via onMessage
  try {
    await sendToBackground('TRANSLATE_NOVEL_STREAM', {
      novelId,
      // fullMode: background translates the whole novel (global ids);
      // paged mode also needs the whole novel, because its renderer splits the
      // result on [newpage] markers into page blocks. Sending a page number
      // made background slice out the current page and number the paragraphs,
      // so the model answered with JSON Lines and the panel renderer took over
      // — which is why paged mode showed up as the side panel.
      currentPage: (state.fullMode || state.mode === 'paged') ? 0 : getCurrentNovelPage(),
      fullMode: state.fullMode,
      // background numbers paragraphs + requests JSON Lines for inline modes
      // (single-page and full) so the id mapping protects against model
      // paragraph merges/splits; panel and paged receive raw text.
      displayMode: state.mode,
      targetLang: state.targetLang,
      selectedPresets: settings.selectedPresets,
      customPrompt: settings.customPrompt,
      model: settings.model,
      modelProfileId: settings.modelProfileId,
      // Request accepted: the button moves to the blue "reasoning"
      // state until the first token arrives (DeepSeek pre-fill).
      thinkingType: settings.thinkingType,
      inlineSeparator: state.inlineSeparator,
      skipCache,
      repairParagraphIds: [],
      repairContext: ''
    });
  } catch (e) {
    // Cancellation makes background reject with abort error — expected.
    // runId 守卫：若这一轮已经被取消或被新一轮接管，不要再动当前状态。
    if (state.translating && state.runId === myRun) {
      finishTranslate(false, e.message);
    }
  }
}

function onNovelLoaded(data) {
  state.novelTitle = data.title || '';
  state.novelAuthor = data.author || '';
  state.originalContent = data.originalContent || '';
  state.numberedRequest = !!data.numberedRequest;
  state.fullMode = !!data.fullMode;
  // Resolve the effective separator against the actual text before anything
  // downstream (expected counts, page splits, DOM units, ids) depends on it.
  // background.js resolves the same way before numbering its request.
  state.inlineSeparator = resolveInlineSeparator(data.originalContent, state.requestedSeparator || state.inlineSeparator);
  // The page background.js actually numbered with (it may have waited for the
  // marker while the body rendered). Prefer it over our own reading so the
  // expected paragraph count matches the request even when this callback
  // fires before the body mounts. Falls back to our own value for older
  // background versions that do not send the field.
  const numberedPage = Number(data.currentPage) || getCurrentNovelPage();
  state.expectedParagraphCount = state.numberedRequest
    ? expectedParagraphCount(data.originalContent, state.fullMode, numberedPage, state.inlineSeparator)
    : 0;
  state.missingParagraphIds = [];
  if (state.fullMode) {
    state.pageStartIds = computePageStartIds(data.originalContent, state.inlineSeparator);
  }
  console.debug('[PNT][novel-loaded]', {
    fullMode: state.fullMode,
    inlineSeparator: state.inlineSeparator,
    currentPage: getCurrentNovelPage(),
    backgroundPage: numberedPage,
    pageCount: String(data.originalContent || '').split(/\\[newpage\\]/i).filter(p => p.trim()).length,
    pageStartIds: state.pageStartIds,
    expectedParagraphCount: state.expectedParagraphCount
  });

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
      .map(p => pixivTagText(p).trim())
      .filter(p => p.length > 0)
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
  let lastDomSignature = '';
  let observerMutating = false;
  state.pageFlipObserver = new MutationObserver((mutations) => {
    // Ignore mutations caused only by our own translation/anchor nodes.
    const pixivMutation = mutations.some((mutation) => {
      const nodes = [...mutation.addedNodes, ...mutation.removedNodes];
      return mutation.type === 'attributes'
        ? !mutation.target.closest?.('.pnt-inline-trans, .pnt-inline-unit')
        : nodes.some(node => !(node.nodeType === Node.ELEMENT_NODE
          && node.closest?.('.pnt-inline-trans, .pnt-inline-unit')));
    });
    if (!pixivMutation || observerMutating) return;
    // Debounce: Pixiv re-renders in a burst on page flip.
    if (timer) clearTimeout(timer);
    timer = setTimeout(() => {
      const page = getCurrentNovelPage() || 1;
      const active = state.inlineContainer && state.inlineContainer.isConnected;
      const pageChanged = page !== lastPage;
      lastPage = page;
      const currentKeys = sourceParagraphsForPage(originalContent, page, state.inlineSeparator)
        .map(item => item.key);
      const renderedKeys = (state.inlineTransEls || [])
        .map(el => el.dataset.sourceKey || '')
        .filter(Boolean);
      const liveParas = findNovelParagraphs(originalContent) || [];
      const domSignature = liveParas
        .map(el => paraMatchKey(domMatchText(el)).slice(0, 80))
        .join('|');
      const domChanged = domSignature !== lastDomSignature;
      lastDomSignature = domSignature;
      const stale = !active || pageChanged || domChanged
        || !renderedKeys.length
        || renderedKeys.some(key => !currentKeys.includes(key));
      console.debug('[PNT][page-flip]', {
        page,
        pageAttribute: document.querySelector('#gtm-novel-work-scroll-begin-reading')?.getAttribute('data-current-page') || '',
        domCount: liveParas.length,
        renderedCount: renderedKeys.length,
        pageChanged,
        domChanged,
        stale
      });
      if (!stale) return;

      // Translations hidden by the user: keep them hidden across page
      // flips instead of rebuilding the divs.
      if (!state.translationVisible) {
        restoreOriginalHtml();
        return;
      }

      if (state.fullMode) {
        // Full-novel mode: translations follow the user across pages.
        // Pixiv flips pages by REUSING the body container and swapping
        // the <p>s in place, so buildInlineParagraphs()'s isConnected
        // guard would wrongly think our pairs are still valid and return
        // early — leaving the old page's translation divs piled on top
        // and giving the new page none. Force a clean rebuild every time:
        // remove all translation divs, rebuild the new page's pairs, then
        // refill from the accumulated map.
        observerMutating = true;
        restoreOriginalHtml();
        const wrapper = buildInlineParagraphs(originalContent);
        if (wrapper) {
          refillInlineFromMap();
        }
        observerMutating = false;
      } else {
        // Per-page mode: Pixiv swapped the paragraphs but kept our stale
        // translation divs (the container itself stays connected). Clear
        // them so the new page starts clean; the user translates it on
        // demand. Never let previous-page translations pile up on top.
        observerMutating = true;
        restoreOriginalHtml();
        state.streamingText = '';
        state.inlineContainer = null;
        observerMutating = false;
      }
    }, 300);
  });
  // Watch both node replacement (page flip) and the data-current-page
  // attribute (in-place re-render keeps the container connected).
  // Seed the signature after the initial build so our own insertion does
  // not look like a page flip.
  const initialParas = findNovelParagraphs(originalContent) || [];
  lastDomSignature = initialParas
    .map(el => paraMatchKey(domMatchText(el)).slice(0, 80))
    .join('|');
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
    if (state.inlineWaitCleanup === cleanup) state.inlineWaitCleanup = null;
  };

  const fallbackToPanel = () => {
    console.warn('[PNT] inline container not found; showing in window instead');
    openWindow();
    state.mode = 'panel'; // render into the window from now on
    fillWindowFromNovel(data);
    // Tokens may have arrived while the inline DOM was still rendering.
    // Paint the accumulated result immediately instead of waiting for
    // another token (or leaving the completed translation blank).
    if (state.numberedRequest) {
      renderPanelFromJsonLines();
    } else if (state.transBody) {
      state.transBody.textContent = state.streamingText;
    }
    showToast('未找到原文容器，已改用悬浮窗显示');
    // When this fires after completion (translating already false), let
    // updateTranslateButton('idle') re-derive the pill from the rendered
    // content instead of forcing it back into a busy state.
    updateTranslateButton(state.translating ? 'preparing' : 'idle');
  };

  const tryBuild = () => {
    // Nothing to render and no translation running (cancelled, or completed
    // with an empty stream): building anchors now would only leave blank
    // placeholder divs on the page. Stop instead.
    if (!state.translating && !state.streamingText) {
      cleanup();
      return true;
    }
    if (buildInlineParagraphs(data.originalContent)) {
      cleanup();
      // If the stream completed before the DOM became available, the final
      // text is already in state.streamingText; render it immediately.
      if (state.fullMode) refillInlineFromMap();
      else if (state.streamingText) renderInlineStreaming();
      // In full-novel mode keep watching for page flips so translations
      // appear on every page without re-translating.
      // All inline modes watch for page flips: Pixiv reuses the body
      // container and only swaps the <p>s, so our stale translation
      // divs would otherwise pile up at the top of the new page.
      watchPageFlips(data.originalContent);
      if (state.fullMode) {
        refillInlineFromMap();
      }
      // Same as above: on a late build after completion, settle the pill on
      // the rendered content (关闭翻译) instead of a busy label.
      updateTranslateButton(state.translating ? 'preparing' : 'idle');
      return true;
    }
    if (Date.now() > deadline) {
      cleanup();
      fallbackToPanel();
      return true;
    }
    return false;
  };

  state.inlineWaitCleanup = cleanup;

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

function onStreamConnected() {
  if (!state.translating) return;
  state.aiConnected = true;
  state.phase = 'reasoning';
  if (!state.firstTokenReceived) updateTranslateButton('reasoning');
}

function onStreamToken(token) {
  if (!state.translating) return; // cancelled — ignore late tokens
  // First token means the AI is really streaming — flip the button.
  if (!state.firstTokenReceived) {
    state.firstTokenReceived = true;
    state.phase = 'reasoning';
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
  if (state.repairMode) {
    // 补译的流只包含缺失段落。用 backup 把结果并回完整译文，而不是替换：
    // 替换会让侧栏显示、显示/隐藏切换、重渲染全部只剩下补译的那几段
    // （003 review 3.5）。
    const repairedText = (data && typeof data.translatedText === 'string' && data.translatedText.trim())
      ? data.translatedText
      : state.streamingText;
    const repaired = parseJsonLines(repairedText);
    const backup = state.repairBackup || { streamText: '', missing: [...state.missingParagraphIds] };

    // 只保留「完整且非空」的补译结果，并按 id 去重（后面的覆盖前面的）：
    // 模型可能乱序输出、也可能对同一 id 同时给出非空与空文本，而两个渲染器
    // 都是「后写胜出」—— 若空行排在其后，用户会既看不到译文也看不到缺失
    // 提示（003 review round2 N3）。
    const repairedById = new Map();
    repaired.forEach((entry) => {
      if (entry.complete && entry.text.trim().length > 0) repairedById.set(entry.id, entry.text);
    });

    if (repairedById.size === 0) {
      // 模型没按 JSON Lines 返回（或全部为空）：回滚到补译前的状态，
      // 但要说清楚原因并留痕，否则用户只看到「补译仍有段落缺失」而
      // 不知道是格式问题（003 review round2 N2）。重绘交给
      // restoreAfterFailedRepair —— 它覆盖 inline / paged / panel 三条
      // 路径（round3 R3：只重绘 inline 会让窗口里留着半截补译内容）。
      const notice = '补译失败：模型没有按段落编号返回译文，可重试或重新翻译';
      recordContentError(notice);
      restoreAfterFailedRepair();
      finishTranslate(false, notice);
      return;
    }

    repairedById.forEach((text, id) => {
      state.fullTranslations[String(id)] = text;
      const el = (state.inlineTransEls || []).find(item => item.dataset.pid === String(id));
      if (el) el.textContent = text;
    });

    // 剔除 backup 中同 id 的旧行，再追加新行 —— 依赖「后写胜出」不够：
    // 旧行里可能就有一条**完整但为空**的同 id 行排在新行前面（N3）。
    const rebuiltLines = parseJsonLines(backup.streamText)
      .filter(entry => !repairedById.has(entry.id))
      .map(entry => JSON.stringify({ id: entry.id, text: entry.text }));
    const appendedLines = [...repairedById.entries()]
      .sort((a, b) => a[0] - b[0])
      .map(([id, text]) => JSON.stringify({ id, text }));
    state.streamingText = rebuiltLines.concat(appendedLines).join('\n');
    state.missingParagraphIds = backup.missing.filter(id => !repairedById.has(id));
    state.repairMode = false;
    state.repairBackup = null;
    // 重新渲染一次，让合并后的完整译文回到界面上（补译成功也要重画）。
    rerenderTranslation();
    // 补译的流同样可能撞上输出上限；它只覆盖缺失段落，截断意味着仍有段落没补上，
    // 由上面的 missingParagraphIds 判定收尾，这里只记录以便 toast 说清原因。
    state.truncated = !!(data && data.truncated);
    finishTranslate(state.missingParagraphIds.length === 0, state.missingParagraphIds.length ? '补译仍有段落缺失' : null, data);
    return;
  }

  // The model may finish before Pixiv has mounted the client-rendered body.
  // Keep the final payload and let the inline wait/build path consume it.
  if (state.mode === 'inline' && (!state.inlineTransEls || !state.inlineTransEls.length)) {
    const rebuilt = state.originalContent ? buildInlineParagraphs(state.originalContent) : null;
    if (rebuilt) {
      watchPageFlips(state.originalContent);
      if (state.fullMode) refillInlineFromMap();
    }
  }

  // The backend's done event contains the authoritative complete response.
  // Keep it when a proxy/client delivered tokens out of order or the final
  // token notification was lost.
  if (data && typeof data.translatedText === 'string'
      && data.translatedText.trim().length > 0) {
    // Cached responses and some proxies may deliver the final event without
    // all token events. Prefer the authoritative complete payload whenever
    // it is present; it is the only safe basis for completion validation.
    state.streamingText = data.translatedText;
  }
  if (state.mode === 'inline' && state.inlineTransEls && state.inlineTransEls.length) {
    renderInlineStreaming();
  } else if (state.transBody) {
    if (state.numberedRequest) renderPanelFromJsonLines();
    else if (state.mode === 'paged') renderPagedStreaming();
    else state.transBody.textContent = state.streamingText;
  }

  // The backend's done event carries the truncation flag when the model stopped at its output
  // cap. A capped answer is a prefix of the translation, so the page must not report
  // 「翻译完成」as if the whole work had been translated. Set before the completeness
  // checks below: a capped numbered stream fails those checks too, and the reason shown must
  // be the output cap, not a bare 「返回不完整」.
  state.truncated = !!(data && data.truncated);

  // A stream can close cleanly even when the model skipped a JSON line or
  // emitted an id twice. Treat that as incomplete instead of telling the
  // user "翻译完成" while silently leaving paragraphs untranslated.
  if (state.numberedRequest && state.expectedParagraphCount > 0) {
    const validation = validateNumberedStream();
    const ids = validation.entries.map(entry => entry.id);
    console.debug('[PNT][stream-done]', {
      translatedChars: state.streamingText.length,
      entryCount: validation.entries.length,
      minId: ids.length ? Math.min(...ids) : null,
      maxId: ids.length ? Math.max(...ids) : null,
      uniqueIdCount: new Set(ids).size,
      missing: validation.missing,
      invalid: validation.invalid
    });
    state.missingParagraphIds = validation.missing;
    if (validation.missing.length || validation.invalid.length) {
      if (state.mode === 'inline') renderInlineStreaming();
      else if (state.transBody) renderPanelFromJsonLines();
      const missingText = validation.missing.length
        ? `缺少第 ${validation.missing.slice(0, 8).join('、')}${validation.missing.length > 8 ? ' 等' : ''}段`
        : '段落编号重复或无效';
      finishTranslate(false, state.truncated
        ? `译文被截断：模型达到单次输出上限（${missingText}），请缩短篇幅或换用 deepseek-flash`
        : `模型返回不完整：${missingText}，请点击重新翻译`, data);
      return;
    }
  }
  finishTranslate(true, null, data);
}

async function repairMissingParagraphs() {
  // 启动序列进行中（包含「先取消再重开」的那段窗口）时不要插入补译。
  if (state.translating || startingTranslation || !state.missingParagraphIds.length) return;
  const ids = [...state.missingParagraphIds];
  const context = state.streamingText.slice(-12000);
  // 备份当前完整译文与缺失清单：补译流只含缺失段落，完成后要把它并回
  // 备份而不是替换，失败/取消时要能原样恢复（003 review 3.5）。
  state.repairBackup = { streamText: state.streamingText, missing: ids };
  state.repairMode = true;
  state.translating = true;
  // 补译也是一轮翻译：给它自己的 runId，让它的 continuation 能判断自己
  // 是否还属于当前这一轮（003 review round2 N4/N5）。
  state.runId += 1;
  const myRun = state.runId;
  startTranslateWatchdog();
  state.streamingText = '';
  state.firstTokenReceived = false;
  state.aiConnected = false;
  if (state.repairBtn) state.repairBtn.style.display = 'none';
  if (state.cancelBtn) state.cancelBtn.style.display = 'inline-block';
  updateTranslateButton('preparing');
  try {
    await sendToBackground('TRANSLATE_NOVEL_STREAM', {
      novelId: state.novelId,
      // Keep paged requests on the whole-novel path here too, so a repair can
      // never flip the renderer back to the panel mid-stream.
      currentPage: (state.fullMode || state.mode === 'paged') ? 0 : getCurrentNovelPage(),
      fullMode: state.fullMode,
      displayMode: state.mode === 'inline' ? 'inline' : state.mode,
      targetLang: state.targetLang,
      selectedPresets: state.settingsSnapshot?.selectedPresets || [],
      customPrompt: state.settingsSnapshot?.customPrompt || '',
      model: state.settingsSnapshot?.model || '',
      modelProfileId: state.settingsSnapshot?.modelProfileId ?? null,
      thinkingType: state.settingsSnapshot?.thinkingType,
      inlineSeparator: state.inlineSeparator,
      skipCache: true,
      repairParagraphIds: ids,
      repairContext: context
    });
  } catch (e) {
    // 这次补译可能已经被取消（cancelTranslation → restoreAfterFailedRepair
    // 会把 repairMode 清掉），或者已有一轮新翻译接管（runId 变了）。
    // 两种情况都必须原样退出 —— 否则 finishTranslate 会去动**新一轮**的
    // 状态（003 review round2 N4：这是本类问题的根源）。
    if (state.repairMode && state.runId === myRun) {
      finishTranslate(false, e.message);
    }
  }
}

function onStreamError(error) {
  if (!state.translating) return; // cancelled — ignore
  finishTranslate(false, error);
}

// 补译中断/失败/取消后的回滚：把内部状态恢复到补译前的样子，让用户
// 仍然拥有那份不完整但已渲染的译文，以及「补译」按钮。
// 把当前译文按模式重画一遍。补译成功、补译回滚、解析为空三条路径都要它，
// 而它们原来各写一份、分支不一致（成功分支漏了 paged）——
// 003 review round3 N12 / round4 N5。
function rerenderTranslation() {
  try {
    if (state.mode === 'inline' && state.inlineTransEls && state.inlineTransEls.length) {
      renderInlineStreaming();
    } else if (state.mode === 'paged' && state.transBody) {
      renderPagedStreaming();
    } else if (state.transBody && state.numberedRequest) {
      renderPanelFromJsonLines();
    }
  } catch (e) {
    console.warn('[PNT] re-render failed:', e && e.message);
  }
}

function restoreAfterFailedRepair() {
  if (state.repairMode && state.repairBackup) {
    state.streamingText = state.repairBackup.streamText;
    state.missingParagraphIds = state.repairBackup.missing;
  }
  state.repairMode = false;
  state.repairBackup = null;
  // 重画一次：inline 模式会把已恢复的译文按 id 重新填回，panel 模式重排文本。
  rerenderTranslation();
}

function finishTranslate(success, errorMsg, data) {
  // 重入守卫（003 review 1.3）：watchdog 判定与紧随其后的 SSE_ERROR 会同时
  // 收尾，第二次调用只会重复弹 toast。已经 idle 就直接忽略。
  if (!state.translating) return;
  state.translating = false;
  // 这一轮已经收尾：作废它的身份，之后到达的同类事件（后台可能还在收尾）
  // 一律丢弃，而不是落到用户的下一轮翻译上。
  state.activeRequestId = null;
  // 补译失败：恢复补译前的完整译文与缺失清单。补译流里只有缺失段落，
  // 不能用它代表整篇译文（003 review 3.5）—— 否则一次失败的补译会把已经
  // 拿到的译文从内部状态里抹掉，重渲染/切换显示后就再也回不来。
  if (state.repairMode && !success) {
    restoreAfterFailedRepair();
  }
  stopTranslateWatchdog();
  if (state.waitTick) { clearInterval(state.waitTick); state.waitTick = null; }
  if (state.firstTokenTimer) { clearTimeout(state.firstTokenTimer); state.firstTokenTimer = null; }
  // Keep a pending inline wait alive when the translation finished before the
  // Pixiv body rendered: tryBuild() builds the anchors and paints the
  // already-received text as soon as the paragraphs appear (or falls back to
  // the floating window at its deadline). Cancelling it unconditionally here
  // is what left fast (cached) translations permanently blank (2026-09-29).
  const inlineSettled = state.mode !== 'inline'
    || (state.inlineTransEls && state.inlineTransEls.length > 0);
  if (state.inlineWaitCleanup && inlineSettled) {
    state.inlineWaitCleanup();
    state.inlineWaitCleanup = null;
  }
  updateTranslateButton('idle');

  if (state.cancelBtn) state.cancelBtn.style.display = 'none';
  // Translation over: offer targeted repair when numbered paragraphs are missing.
  if (state.retranslateBtn) state.retranslateBtn.style.display = hasTranslationContent() ? '' : 'none';
  if (state.repairBtn) state.repairBtn.style.display = state.missingParagraphIds.length ? '' : 'none';

  if (success) {
    const inlineReady = state.mode !== 'inline'
      || (state.inlineTransEls && state.inlineTransEls.length > 0);
    if (!inlineReady) {
      // The body may simply not have rendered yet; the pending inline wait
      // paints the text as soon as it does (and announces a fallback if the
      // paragraphs never appear). Don't claim failure here.
      showToast(state.truncated
        ? '译文被截断：模型达到单次输出上限，可点击重新翻译或缩短篇幅'
        : '翻译完成，正在等待页面正文渲染…');
      console.warn('[PNT] translation completed before inline DOM anchors were ready; waiting for the body', {
        currentPage: getCurrentNovelPage(),
        originalChars: state.originalContent.length,
        translatedChars: state.streamingText.length
      });
    } else {
      showToast(state.truncated
        ? '译文被截断：模型达到单次输出上限，可点击重新翻译或缩短篇幅'
        : '翻译完成');
    }
  } else {
    // Treat both the raw AbortError and the background's friendly
    // "翻译已取消" as a user cancellation, not a failure.
    if (errorMsg && (errorMsg.includes('abort') || errorMsg.includes('取消'))) {
      showToast('已取消翻译');
    } else {
      // Keep whatever was already rendered: a transient network error
      // must not wipe the translations the user already received.
      showToast('翻译中断：' + (errorMsg || '未知错误'));
    }
  }
}

// ─── Liveness watchdog ──────────────────────────────────────

// MV3 reclaims the background service worker when it goes idle. The worker
// restarts on the next message, but the SSE fetch it was reading dies with
// the old instance: no token, no done and no error will ever arrive, and the
// page just watches "AI 预填充中 Ns…" with the seconds climbing forever
// (a user reported 18761 s). Probe the worker for a live stream so the page
// can reset itself instead of waiting on an event that will never come.
//
// Three consecutive "no" answers (≈ 75-105 s including probe timeouts) mean
// the translation is gone. A stream that is merely slow — or whose model is
// still pre-filling for minutes — answers "yes", so long legitimate waits
// are never cut short.
const WATCHDOG_INTERVAL_MS = 25000;
const WATCHDOG_PROBE_TIMEOUT_MS = 10000;
const WATCHDOG_MISS_LIMIT = 3;

function startTranslateWatchdog() {
  // 只在真正的「开始新一轮翻译」处调用。这里负责作废上一轮的探测、
  // 重置计数，并开启新计时器。
  stopTranslateWatchdog();
  state.watchdogMisses = 0;
  // 每次翻译一个新 epoch：在途的旧探测回来时，若 epoch 已变就不再计数 ——
  // 否则旧探测的 miss 会记到新一次翻译的账上（003 review 1.3）。
  state.watchdogEpoch += 1;
  const myEpoch = state.watchdogEpoch;
  state.watchdogTimer = setInterval(() => { probeTranslationAlive(myEpoch); }, WATCHDOG_INTERVAL_MS);
}

// 记录到与 background 同一个 pntErrorLog（popup 的「提交日志」读的就是它）：
// watchdog 的判定必须留痕，否则「还有多少卡死」在线上根本无从统计。
function recordContentError(message) {
  try {
    chrome.storage.local.get('pntErrorLog', (items) => {
      try {
        if (chrome.runtime.lastError) return;
        const log = Array.isArray(items.pntErrorLog) ? items.pntErrorLog : [];
        log.push({ time: new Date().toISOString(), message: String(message).slice(0, 2000) });
        if (log.length > 20) log.shift();
        chrome.storage.local.set({ pntErrorLog: log });
      } catch (e) { /* 扩展上下文已失效：无处可记，忽略 */ }
    });
  } catch (e) { /* 同上 */ }
}

function stopTranslateWatchdog() {
  // 只停计时器：不动 misses / epoch。
  //
  // 这两项属于「这一轮翻译」的身份，只能由 startTranslateWatchdog 在下一轮
  // 开始时重置。在这里自增 epoch 是错误的（003 review round2 1.7）—— 它会把
  // 每一次「顺手清理」（finishTranslate、探测自检、取消…）都变成一次「本轮
  // 作废」，于是在途探测回来时被静默丢弃，miss 永远攒不满 3 次，判定被无限
  // 推迟。而在 P1（SW 被回收）场景下不会再有人调用 startTranslateWatchdog，
  // 探测就永久失效 —— 那正是按钮卡死唯一的自愈路径。
  if (state.watchdogTimer) {
    clearInterval(state.watchdogTimer);
    state.watchdogTimer = null;
  }
}

async function probeTranslationAlive(myEpoch) {
  if (!state.translating) { stopTranslateWatchdog(); return; }
  let alive = false;
  try {
    const data = await sendToBackground('TRANSLATE_STATUS', {}, WATCHDOG_PROBE_TIMEOUT_MS);
    alive = !!(data && data.active);
  } catch (e) {
    // 扩展上下文失效（用户更新/重载了插件）不是「后台被回收」：此时重试
    // 必然无效 —— handleTranslate 入口的 chrome.runtime?.id 检查会直接
    // return，用户只会反复点一个没反应的按钮。sendToBackground 已把这种
    // 情况翻译成「扩展已更新，请刷新页面后重试」，直接用它收尾。
    const msg = (e && e.message) ? e.message : String(e);
    if (msg.includes('刷新页面') || msg.includes('扩展已更新')) {
      stopTranslateWatchdog();
      state.translating = false;
      state.activeRequestId = null;
      // 这条路不走 finishTranslate，所以那些清理必须自己做：否则 waitTick
      // 下一秒就会用旧的 state.phase 把按钮重新刷成忙态，而用户再也看不到
      // 这句提示（003 review round2 1.8）。
      if (state.waitTick) { clearInterval(state.waitTick); state.waitTick = null; }
      if (state.firstTokenTimer) { clearTimeout(state.firstTokenTimer); state.firstTokenTimer = null; }
      state.phase = 'idle';
      // 留痕：这是一条静默路径，与 watchdog 判定同样需要可诊断。
      recordContentError(msg);
      updateTranslateButton('idle');
      showToast(msg);
      return;
    }
    // 无应答（worker 不可达，或它中途死了）算一次 miss：健康的 worker 会立即作答。
    alive = false;
  }
  if (!state.translating) return;               // finished while the probe was in flight
  if (myEpoch !== state.watchdogEpoch) return;  // 已开启新一轮翻译，旧探测作废
  if (alive) { state.watchdogMisses = 0; return; }
  state.watchdogMisses += 1;
  if (state.watchdogMisses >= WATCHDOG_MISS_LIMIT) {
    stopTranslateWatchdog();
    // 重试是有效的（会重新发请求，SW 会按需冷启动），但若反复命中就该
    // 刷新页面 —— 检查器（003 review 3.9）指出只写「请重试」会让用户在
    // 无法自愈的情况下反复点按钮。
    const notice = '与插件后台的连接已中断（后台任务已被浏览器回收），请重试；若反复失败请刷新页面';
    // 留痕：这句话是判断「1.4.8 之后还剩多少卡死」的唯一线上线索。
    recordContentError(notice);
    finishTranslate(false, notice);
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
  // 不再属于任何一轮：此后旧流投递的事件（读空闲/首 token 超时的错误文本
  // 不含「取消」）必须被丢弃，直到新一轮的 SSE_NOVEL_LOADED 重新登记身份。
  state.activeRequestId = null;
  // 取消的若是一次补译，回滚到补译前的完整译文（003 review 3.5）。
  if (state.repairMode) restoreAfterFailedRepair();
  stopTranslateWatchdog();
  updateTranslateButton('idle');
  if (state.cancelBtn) state.cancelBtn.style.display = 'none';
  // 取消后这些入口应该重新可用：否则用户取消一次失败的补译后，
  // 既没有「补译」按钮也没有「重新翻译」按钮，只能刷新页面。
  if (state.retranslateBtn) state.retranslateBtn.style.display = hasTranslationContent() ? '' : 'none';
  if (state.repairBtn) state.repairBtn.style.display = state.missingParagraphIds.length ? '' : 'none';
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
  if (sender.id !== chrome.runtime.id || !message || typeof message.type !== 'string') {
    sendResponse({ ok: false });
    return false;
  }

  // 统一的失败处理：把界面强制拉回可用状态。finishTranslate 自己也可能抛
  // （它做大量 DOM 操作），所以这里不依赖它 —— 兜底直接清 translating 与
  // watchdog，再尽力复位按钮，保证用户至少还能点「翻译」重试。
  const handleHandlerFailure = (e) => {
    console.error('[PNT] message handler failed:', message.type, e);
    try {
      if (!state.translating) return;
      finishTranslate(false, '翻译处理出错：' + (e && e.message ? e.message : e));
    } catch (nested) {
      console.error('[PNT] finishTranslate itself failed; forcing UI reset:', nested);
      try {
        state.translating = false;
        state.activeRequestId = null;
        stopTranslateWatchdog();
        // 计时器不关掉的话，它会用旧的 state.phase 在下一秒把按钮刷回忙态，
        // 兜底就白做了（003 review round2 1.8）。
        if (state.waitTick) { clearInterval(state.waitTick); state.waitTick = null; }
        if (state.firstTokenTimer) { clearTimeout(state.firstTokenTimer); state.firstTokenTimer = null; }
        state.phase = 'idle';
        recordContentError('翻译处理出错：' + (e && e.message ? e.message : e));
        updateTranslateButton('idle');
        showToast('翻译处理出错：' + (e && e.message ? e.message : e));
      } catch (final) {
        console.error('[PNT] forced UI reset failed too:', final);
      }
    }
  };

  // 所有同步分支都必须应答：background 在 await chrome.tabs.sendMessage，
  // 端口异常关闭会被当作「未投递」。终止事件（SSE_DONE / SSE_ERROR）因此
  // 丢失的话，按钮会永远停在「AI 预填充中 Ns…」而无人复位。
  const guard = (handler) => {
    try {
      const result = handler();
      // finishTranslate 等处理器是 async：它 await 之后抛出的异常不会
      // 被上面的 try 捕获，必须接住返回的 Promise。
      if (result && typeof result.then === 'function') {
        result.catch(handleHandlerFailure);
      }
    } catch (e) {
      handleHandlerFailure(e);
    }
    sendResponse({ ok: true });
  };

  // requestId 校验：background 为每一轮翻译分配一个 id 并随所有事件下发。
  //
  // 不属于「当前这一轮」的事件一律丢弃：旧流迟到的 token / done / error
  // 不能去动新一轮的状态。尤其是旧流的**读空闲超时**与**首 token 超时**
  // —— 它们的错误文本不含「取消」，background 会正常投递
  // （003 review round3 R1/R2；这是「事件无身份」这个根因的修法）。
  //
  // 注意这里要求「有 requestId 的事件必须有匹配的活跃请求」而不是
  // 「两者都存在且不等才丢弃」：取消之后到新一轮 SSE_* 到达之前，
  // activeRequestId 会保持旧值（cancel 已把它置空，见 cancelTranslation），
  // 若只剩旧值就等于把旧流的事件误认成当前轮。
  //
  // SSE_NOVEL_LOADED 不在此列：它就是「新一轮开始」的标志，无条件接受。
  if (message.requestId && message.type !== 'SSE_NOVEL_LOADED') {
    if (!state.activeRequestId || message.requestId !== state.activeRequestId) {
      console.debug('[PNT] dropping event from a superseded request:', message.type);
      sendResponse({ ok: true });
      return false;
    }
  }

  switch (message.type) {
    case 'SSE_NOVEL_LOADED':
      // 原文到手，接下来是发翻译请求、等后端响应。同时记下这一轮的身份。
      state.activeRequestId = message.requestId || state.activeRequestId;
      state.phase = 'connecting';
      guard(() => onNovelLoaded(message.data));
      break;
    case 'SSE_SERVER_ACK':
      // 后端已回响应头：它在查缓存、装 prompt、调模型，到第一个 token
      // 之间就是模型预填充。
      guard(() => {
        if (!state.translating) return;
        state.phase = 'prefill';
        updateTranslateButton('preparing');
      });
      break;
    case 'SSE_CONNECTED':
      guard(() => onStreamConnected());
      break;
    case 'SSE_TOKEN':
      // 渲染异常不能让整次翻译作废：这个 token 已经进了 streamingText，
      // 终止事件到达时会用完整文本重渲染。若在这里复位 UI（旧 guard
      // 的行为），后续所有 token 与 SSE_DONE 都会被 translating 守卫
      // 丢弃 —— 用户只拿到半篇译文，而且完成校验与「补译」入口整个被
      // 绕过。这里只记录，不打断流（003 review 1.11：也写进错误日志，
      // 之前只 console.error，线上无从诊断）。
      try {
        onStreamToken(message.token);
      } catch (e) {
        console.error('[PNT] token render failed; keeping the stream alive:', e);
        recordContentError('译文渲染出错：' + (e && e.message ? e.message : e));
      }
      sendResponse({ ok: true });
      break;
    case 'SSE_DONE':
      guard(() => onStreamDone(message.data));
      break;
    case 'SSE_ERROR':
      guard(() => onStreamError(message.error));
      break;
    case 'GET_CURRENT_PAGE':
      // background.js: a paged request started before the body rendered —
      // wait (bounded) for the page marker before answering. Flip the pill
      // to say what we are actually waiting for; SSE_NOVEL_LOADED switches
      // it back to 'connecting' by itself.
      if (state.translating) {
        state.phase = 'waitingBody';
        updateTranslateButton('preparing');
      }
      waitForCurrentPage().then(
        (page) => sendResponse({ ok: true, page }),
        () => sendResponse({ ok: true, page: 0 })
      );
      break;
    case 'MANUAL_TRANSLATE':
      // Popup explicit trigger: cancel a running translation first, then
      // start fresh with the current settings (beginTranslation owns the
      // cancel-then-start sequence and keeps it non-reentrant).
      beginTranslation(true);
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
      setTimeout(() => beginTranslation(), 500);
    }
  });
}

if (document.readyState === 'loading') {
  document.addEventListener('DOMContentLoaded', init);
} else {
  init();
}
