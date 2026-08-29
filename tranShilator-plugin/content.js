// ============================================================
// Pixiv Novel Translator — Content Script
// ============================================================

// ─── State ───────────────────────────────────────────────────

let state = {
  novelId: null,
  translating: false,
  mode: 'panel',          // 'panel' | 'inline' | 'paged'
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
  inlineSeparator: 'p',   // 'p' or 'p-br'
  fullTranslations: {},   // global paragraph id -> translated text
  fullEntryMeta: {},      // global paragraph id -> latest JSON line metadata
  pageStartIds: [],       // pageStartIds[p-1] = first global id of page p
  pageFlipObserver: null,  // MutationObserver for page flips (fullMode)
  inlineTransEls: [],      // translation elements inserted after Pixiv paragraphs
  inlineWaitCleanup: null, // cancels a pending inline-container wait
  firstTokenTimer: null,   // pre-fill feedback timer
  waitTick: null,          // elapsed-time display timer
  autoStarted: false,      // translation was started by autoTranslate
  firstTokenReceived: false, // true once the first SSE token arrived
  aiConnected: false,       // upstream model response has connected
  expectedParagraphCount: 0, // number of numbered paragraphs in this request
  missingParagraphIds: [] // ids the model did not return at completion
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
        cancelTranslation();
        handleTranslate();
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
      <button class="pnt-toggle-trans-btn" title="隐藏/显示译文">关闭翻译</button>
      <button class="pnt-retranslate-btn" style="display:none;" title="用当前设置重新翻译">重新翻译</button>
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
  const retranslateBtn = w.querySelector('.pnt-retranslate-btn');
  retranslateBtn.addEventListener('click', () => {
    if (state.translating) {
      cancelTranslation();
      handleTranslate(true);
    } else {
      handleTranslate(true);
    }
  });
  state.retranslateBtn = retranslateBtn;

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

function updateTranslateButton(status) {
  const set = (el, text, bg) => {
    if (!el) return;
    el.textContent = text;
    el.style.background = bg;
    el.style.borderColor = bg;
  };

  if (status === 'preparing') {
    set(state.miniBtn, '网络连接中...', '#e03131');
  } else if (status === 'reasoning') {
    set(state.miniBtn, 'AI 推理中...', '#1971c2');
  } else if (status === 'ai-processing') {
    set(state.miniBtn, 'AI 处理中...', '#1971c2');
  } else {
    // idle: with a rendered translation the pill toggles its display
    // (关闭翻译/显示翻译); otherwise it starts a new translation.
    if (hasTranslationContent()) {
      syncToggleLabels();
    } else {
      set(state.miniBtn, '翻译', '#1a1a1a');
    }
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

// Pixiv special tags never appear as literal text in the rendered DOM:
// [chapter:X]/[b:X]/[i:X]/[e:X] render as their inner text X, ruby
// [rb:漢字,かな] keeps the base text, and control tags ([newpage],
// [jump:N], [uploadedimage:N], [[jumpuri:…]]…) render as nothing. Convert
// them the same way before matching source text against the DOM, or
// tag-led novels (bold/italics formatting update) can never be anchored
// and every paragraph id would shift by the number of tag paragraphs.
function pixivTagText(s) {
  return (s || '')
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
        const t = paraMatchKey(node.textContent);
        if (t && (t.includes(anchor) || (t.length >= 6 && anchor.includes(t.slice(0, 8))))) {
          // Climb up to the element that holds (at most) this paragraph,
          // then collect the run from there.
          let el = node.parentElement;
          let line = el;
          while (el && el !== document.body) {
            if (paraMatchKey(el.textContent).length > anchorPara.length + 8) break;
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

function domInlineUnitsForParagraph(element, separator = state.inlineSeparator) {
  if (separator !== 'p-br' || !element.querySelector('br')) return [element];

  // Keep Pixiv's React-owned children untouched. A live zero-width marker is
  // inserted at each line boundary and used as the stable insertion anchor;
  // unlike detached clones, these anchors remain connected to the document.
  const units = [];
  let rangeStart = element.firstChild;
  let text = '';
  const addUnit = (beforeNode) => {
    if (!text.trim()) return;
    const marker = document.createElement('span');
    marker.className = 'pnt-inline-unit';
    marker.dataset.pntAnchor = 'true';
    marker.style.display = 'inline';
    marker.style.width = '0';
    marker.style.overflow = 'hidden';
    element.insertBefore(marker, beforeNode);
    units.push(marker);
    text = '';
  };

  const walker = document.createTreeWalker(element, NodeFilter.SHOW_TEXT);
  const textNodes = [];
  while (walker.nextNode()) textNodes.push(walker.currentNode);
  textNodes.forEach((node) => {
    text += node.textContent || '';
  });

  // The DOM's <br> nodes are the line boundaries. Insert markers directly
  // before each break, then one before the paragraph's end. Source matching
  // is performed against the complete paragraph below; markers are only
  // anchors and carry no source text.
  const breaks = Array.from(element.querySelectorAll('br'));
  if (!breaks.length) return [element];
  const paragraphText = element.textContent || '';
  const lines = paragraphText.split(/\n+/).filter(line => line.trim());
  if (!lines.length) return [element];
  const markers = [];
  breaks.forEach((br) => {
    const marker = document.createElement('span');
    marker.className = 'pnt-inline-unit';
    marker.dataset.pntAnchor = 'true';
    marker.style.display = 'inline-block';
    marker.style.width = '0';
    element.insertBefore(marker, br);
    markers.push(marker);
  });
  const tail = document.createElement('span');
  tail.className = 'pnt-inline-unit';
  tail.dataset.pntAnchor = 'true';
  tail.style.display = 'inline-block';
  tail.style.width = '0';
  element.appendChild(tail);
  return markers.concat(tail);
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
        ? paraMatchKey((p.textContent || '').split(/\n+/).filter(line => line.trim())[unitIndex] || '')
        : paraMatchKey(unit.textContent);
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
    firstDomKey: paraEls[0] ? paraMatchKey(paraEls[0].textContent).slice(0, 40) : '',
    lastDomKey: paraEls.length ? paraMatchKey(paraEls[paraEls.length - 1].textContent).slice(0, 40) : '',
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
    el.textContent = entry ? entry.text : '';
  });

  // Never leave an omitted paragraph looking like an intentional blank line.
  state.missingParagraphIds.forEach((id) => {
    const el = byPid.get(String(id));
    if (el && !el.textContent) {
      el.textContent = `⚠ 第 ${id} 段翻译缺失，请点击重新翻译`;
    }
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
    el.textContent = state.fullTranslations[el.dataset.pid]
      || (state.missingParagraphIds.includes(id)
        ? `⚠ 第 ${id} 段翻译缺失，请点击重新翻译`
        : '');
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
    if (idx > last) {
      el.textContent += '\n' + para; // merge overflow instead of overwrite
    } else {
      el.textContent = para;
    }
  });
}

// ─── Translation Flow ───────────────────────────────────────

async function handleTranslate(skipCache = false) {
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
      chrome.storage.sync.get(['targetLang', 'displayMode', 'inlineScope', 'inlineSeparator', 'selectedPresets', 'customPrompt', 'thinkingType', 'model', 'modelProfileId'], (items) => {
        resolve({
          targetLang: items.targetLang || 'zh',
          displayMode: items.displayMode || 'panel',
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
      resolve({
        targetLang: 'zh',
        displayMode: 'panel',
        selectedPresets: [],
        customPrompt: '',
        model: '',
        modelProfileId: null,
        inlineScope: 'page',
        inlineSeparator: 'p'
      });
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
  state.mode = settings.displayMode;
  state.translating = true;
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
      const connected = state.aiConnected || state.firstTokenReceived;
      btn.textContent = connected
        ? 'AI 推理中 ' + secs + 's…'
        : '网络连接中 ' + secs + 's…';
      btn.style.background = connected ? '#1971c2' : '#e03131';
      btn.style.borderColor = connected ? '#1971c2' : '#e03131';
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
      // background numbers paragraphs + requests JSON Lines for every
      // inline mode (single-page, paged, full) so the id mapping protects
      // against model paragraph merges/splits; plain panel stays raw.
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
      skipCache
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
  state.originalContent = data.originalContent || '';
  state.numberedRequest = !!data.numberedRequest;
  state.fullMode = !!data.fullMode;
  state.expectedParagraphCount = state.numberedRequest
    ? expectedParagraphCount(data.originalContent, state.fullMode, getCurrentNovelPage(), state.inlineSeparator)
    : 0;
  state.missingParagraphIds = [];
  if (state.fullMode) {
    state.pageStartIds = computePageStartIds(data.originalContent, state.inlineSeparator);
  }
  console.debug('[PNT][novel-loaded]', {
    fullMode: state.fullMode,
    inlineSeparator: state.inlineSeparator,
    currentPage: getCurrentNovelPage(),
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
        .map(el => paraMatchKey(el.textContent).slice(0, 80))
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
    .map(el => paraMatchKey(el.textContent).slice(0, 80))
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
    showToast('未找到原文容器，已改用侧边面板显示');
    updateTranslateButton('preparing');
  };

  const tryBuild = () => {
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
  if (!state.firstTokenReceived) updateTranslateButton('reasoning');
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
      finishTranslate(false, `模型返回不完整：${missingText}，请点击重新翻译`, data);
      return;
    }
  }
  finishTranslate(true, null, data);
}

function onStreamError(error) {
  if (!state.translating) return; // cancelled — ignore
  finishTranslate(false, error);
}

function finishTranslate(success, errorMsg, data) {
  state.translating = false;
  if (state.waitTick) { clearInterval(state.waitTick); state.waitTick = null; }
  if (state.firstTokenTimer) { clearTimeout(state.firstTokenTimer); state.firstTokenTimer = null; }
  if (state.inlineWaitCleanup) {
    state.inlineWaitCleanup();
    state.inlineWaitCleanup = null;
  }
  updateTranslateButton('idle');

  if (state.cancelBtn) state.cancelBtn.style.display = 'none';
  // Translation over (finished or failed with text kept): offer a
  // one-click retranslate instead of leaving the user to hunt for it.
  if (state.retranslateBtn) {
    state.retranslateBtn.style.display = hasTranslationContent() ? '' : 'none';
  }

  if (success) {
    const inlineReady = state.mode !== 'inline'
      || (state.inlineTransEls && state.inlineTransEls.length > 0);
    if (!inlineReady) {
      showToast('翻译完成，但未找到 Pixiv 原文段落，译文未能插入页面；请刷新页面后重试');
      console.warn('[PNT] translation completed without inline DOM anchors', {
        currentPage: getCurrentNovelPage(),
        originalChars: state.originalContent.length,
        translatedChars: state.streamingText.length
      });
    } else {
      showToast('翻译完成');
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
  if (sender.id !== chrome.runtime.id || !message || typeof message.type !== 'string') {
    sendResponse({ ok: false });
    return false;
  }

  switch (message.type) {
    case 'SSE_NOVEL_LOADED':
      onNovelLoaded(message.data);
      sendResponse({ ok: true });
      break;
    case 'SSE_CONNECTED':
      onStreamConnected();
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
        await handleTranslate(true);
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
