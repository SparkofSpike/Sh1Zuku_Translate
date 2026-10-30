// ============================================================
// GitHub Page Translator — Content Script
// ------------------------------------------------------------
// Translates the natural-language body of a GitHub page (README,
// issue / PR descriptions and comments, release notes, wiki pages)
// paragraph by paragraph and renders each translation under its
// source block.
//
// Design notes:
//  * Only blocks inside `.markdown-body` are considered. That class is
//    GitHub's stable anchor across the React/Turbo rewrites and it is
//    exactly the rendered Markdown surface — navigation, file lists,
//    diffs, buttons and code viewers live outside it.
//  * Blocks are numbered [1]..[N] and answered with JSON Lines, so a
//    model that merges or splits a paragraph can never shift the
//    mapping onto the wrong DOM node.
//  * The floating panel lives in a shadow root: GitHub's own CSS cannot
//    reach it, and the panel cannot leak styles into the page.
//  * GitHub navigates with Turbo. Any page change tears down the
//    previous translations before the new body is scanned.
// ============================================================

(() => {
  'use strict';

  if (globalThis.__PNT_GITHUB_LOADED__) return;
  globalThis.__PNT_GITHUB_LOADED__ = true;

  // ─── Tunables ───────────────────────────────────────────────

  // Block-level elements that carry prose inside rendered Markdown.
  const BLOCK_SELECTOR = [
    'p', 'li', 'h1', 'h2', 'h3', 'h4', 'h5', 'h6',
    'blockquote', 'dt', 'dd', 'td', 'th', 'summary', 'figcaption'
  ].join(',');

  // Never descend into code, raw data, or our own output.
  const SKIP_SELECTOR = [
    'pre', 'code', 'kbd', 'samp', 'var', 'script', 'style', 'textarea', 'select',
    '.highlight', '.blob-code', '.diff-table', '.csv-data', '.pnt-gh-trans'
  ].join(',');

  const MIN_TEXT_LENGTH = 2;
  const MAX_BLOCKS = 400;
  const MAX_TOTAL_CHARS = 60000;
  const SCAN_DEBOUNCE_MS = 700;
  const SETTINGS_TIMEOUT_MS = 5000;
  const PANEL_ID = 'pnt-gh-panel-host';

  // ─── State ──────────────────────────────────────────────────

  const state = {
    blocks: [],              // [{ id, el, text }]
    byId: new Map(),         // id -> block
    translations: new Map(), // id -> { source, text }
    nodes: new Map(),        // id -> rendered translation node
    translating: false,
    requestId: '',
    batchIndex: 0,
    batchTotal: 0,
    batchIds: [],
    pendingIds: [],
    buffer: '',
    error: '',
    truncated: false,
    hidden: false,
    targetLang: 'zh',
    thinkingType: 'disabled',
    skippedBlocks: 0,
    errorKind: '',
    nextId: 1,
    scanTimer: null,
    observer: null,
    panel: null,
    shadow: null,
    els: {}
  };

  // ─── Small helpers ──────────────────────────────────────────

  function sendToBackground(type, payload = {}) {
    return new Promise((resolve) => {
      try {
        if (!chrome.runtime?.id) {
          resolve({ success: false, error: '扩展已更新，请刷新页面后重试' });
          return;
        }
        chrome.runtime.sendMessage({ type, ...payload }, (response) => {
          if (chrome.runtime.lastError) {
            resolve({ success: false, error: chrome.runtime.lastError.message });
          } else {
            resolve(response || { success: false, error: '后台无响应' });
          }
        });
      } catch (e) {
        resolve({ success: false, error: e && e.message ? e.message : String(e) });
      }
    });
  }

  function loadSettings() {
    return new Promise((resolve) => {
      let settled = false;
      const finish = (value) => {
        if (settled) return;
        settled = true;
        clearTimeout(timer);
        resolve(value);
      };
      const defaults = { targetLang: 'zh', thinkingType: 'disabled' };
      const timer = setTimeout(() => finish(defaults), SETTINGS_TIMEOUT_MS);
      try {
        chrome.storage.sync.get(['targetLang', 'thinkingType'], (items) => {
          finish({
            targetLang: items.targetLang || 'zh',
            thinkingType: items.thinkingType || 'disabled'
          });
        });
      } catch (e) {
        finish(defaults);
      }
    });
  }

  function normalizeBlockText(text) {
    // The request format puts one block per line, so internal newlines
    // must collapse. Markdown soft wraps carry no meaning here.
    return String(text || '').replace(/\s+/g, ' ').trim();
  }

  // Read a block's own prose. List items and table cells hold the
  // translation node *inside* them, so a plain innerText would fold the
  // previous translation into the next request's source text — the block
  // would then look "changed", its translation would be dropped and the
  // page would re-translate itself forever.
  function blockText(el) {
    if (!el.querySelector('.pnt-gh-trans')) {
      return el.innerText || el.textContent || '';
    }
    const clone = el.cloneNode(true);
    clone.querySelectorAll('.pnt-gh-trans').forEach((node) => node.remove());
    return clone.innerText || clone.textContent || '';
  }

  function countLetters(text) {
    return (text.match(/[A-Za-z\u00C0-\u024F\u3040-\u30FF\u3400-\u9FFF\uAC00-\uD7AF]/g) || []).length;
  }

  function isTranslatable(text, targetLang) {
    if (!text || text.length < MIN_TEXT_LENGTH) return false;

    const letters = countLetters(text);
    if (letters < 2) return false; // pure punctuation, numbers, emoji

    // A bare URL or a lone identifier has nothing to translate.
    if (/^(?:https?:\/\/|www\.)\S+$/i.test(text)) return false;
    if (!/\s/.test(text) && /^[\w./:@#-]+$/.test(text)) return false;

    // Do not re-translate content that is already written in the target
    // script. Only meaningful for CJK targets: an English target should
    // still translate a Chinese README.
    if (targetLang !== 'en') {
      const cjk = (text.match(/[\u3400-\u9FFF\uF900-\uFAFF]/g) || []).length;
      if (letters > 0 && cjk / letters > 0.55) return false;
    }
    return true;
  }

  // Strip the few Markdown markers the model is asked to preserve but
  // that read as noise when the translation is rendered as plain text.
  function cleanMarkdownForDisplay(text) {
    return String(text || '')
      .replace(/\*\*([^*]+)\*\*/g, '$1')
      .replace(/__([^_]+)__/g, '$1')
      .replace(/\[([^\]]+)\]\((?:[^()]*)\)/g, '$1')
      .trim();
  }

  // ─── Block collection ───────────────────────────────────────

  function hasBlockChild(el) {
    // querySelector only matches descendants, so a leaf block returns null.
    return !!el.querySelector(BLOCK_SELECTOR);
  }

  function collectBlocks(targetLang) {
    const roots = Array.from(document.querySelectorAll('.markdown-body'));
    if (!roots.length) return [];

    const result = [];
    const seen = new Set();

    for (const root of roots) {
      if (root.closest(SKIP_SELECTOR)) continue;
      for (const el of root.querySelectorAll(BLOCK_SELECTOR)) {
        if (seen.has(el)) continue;
        if (el.closest(SKIP_SELECTOR)) continue;
        // Container blocks are covered by their descendants; translating
        // both would duplicate text (a <li> holding a <p>, a <blockquote>
        // holding paragraphs).
        if (hasBlockChild(el)) continue;

        const text = normalizeBlockText(blockText(el));
        if (!isTranslatable(text, targetLang)) continue;
        seen.add(el);
        result.push({ el, text });
        if (result.length >= MAX_BLOCKS) return result;
      }
    }
    return result;
  }

  function assignIds(blocks) {
    let totalChars = 0;
    const accepted = [];
    for (const block of blocks) {
      if (totalChars + block.text.length > MAX_TOTAL_CHARS) break;
      let id = Number(block.el.dataset.pntGhId);
      if (!Number.isInteger(id) || id <= 0) {
        id = state.nextId++;
        block.el.dataset.pntGhId = String(id);
      }
      block.id = id;
      totalChars += block.text.length;
      accepted.push(block);
    }
    // Blocks past the per-page cap stay untranslated; the panel says so
    // instead of silently pretending the page is complete.
    state.skippedBlocks = blocks.length - accepted.length;
    return accepted;
  }

  // ─── Rendering ──────────────────────────────────────────────

  function removeTranslationNode(id) {
    const node = state.nodes.get(id);
    if (node) {
      node.remove();
      state.nodes.delete(id);
    }
  }

  function translationHost(block) {
    const el = block.el;
    // A <div> inside a <p> would make the browser close the paragraph and
    // wreck the page structure. List items and table cells accept flow
    // content, so the node goes inside them and stays visually attached.
    if (el.tagName === 'LI') return { parent: el, inside: true };
    if (el.tagName === 'TD' || el.tagName === 'TH') return { parent: el, inside: true };
    return { parent: el, inside: false };
  }

  function renderTranslation(block, text, { loading = false } = {}) {
    if (!block.el.isConnected) return;
    let node = state.nodes.get(block.id);
    if (!node) {
      node = document.createElement('div');
      node.className = 'pnt-gh-trans';
      node.dataset.pntGhFor = String(block.id);
      node.setAttribute('dir', 'auto');
      const host = translationHost(block);
      if (host.inside) {
        host.parent.appendChild(node);
      } else {
        host.parent.insertAdjacentElement('afterend', node);
      }
      state.nodes.set(block.id, node);
    }
    node.classList.toggle('pnt-gh-loading', !!loading);
    node.classList.toggle('pnt-gh-error', !loading && !!text && text.indexOf('翻译失败') === 0);
    node.textContent = text;
    node.style.display = state.hidden ? 'none' : '';
  }

  function clearTranslations() {
    for (const node of state.nodes.values()) node.remove();
    state.nodes.clear();
    state.translations.clear();
    for (const block of state.blocks) {
      if (block.el && block.el.dataset) delete block.el.dataset.pntGhId;
    }
  }

  // ─── JSON Lines parsing ─────────────────────────────────────

  // Locate the end of the JSON object that starts at `start`. Returns -1
  // while the object is still incomplete, which is the normal case in the
  // middle of an SSE token stream.
  function findObjectEnd(text, start) {
    let depth = 0;
    let inString = false;
    let escaped = false;
    for (let i = start; i < text.length; i++) {
      const ch = text[i];
      if (inString) {
        if (escaped) escaped = false;
        else if (ch === '\\') escaped = true;
        else if (ch === '"') inString = false;
        continue;
      }
      if (ch === '"') inString = true;
      else if (ch === '{') depth++;
      else if (ch === '}') {
        depth--;
        if (depth === 0) return i;
      }
    }
    return -1;
  }

  function safeJsonParse(text) {
    try {
      return JSON.parse(text);
    } catch (e) {
      return null;
    }
  }

  // Pull every complete {"id":..,"text":".."} object out of the buffer and
  // hand back the unparsed tail. Code fences or stray prose before an
  // object are skipped rather than allowed to poison the whole batch.
  function drainJsonObjects(buffer) {
    const entries = [];
    let cursor = 0;
    for (;;) {
      const start = buffer.indexOf('{', cursor);
      if (start < 0) return { entries, rest: '' };
      const end = findObjectEnd(buffer, start);
      if (end < 0) return { entries, rest: buffer.slice(start) };
      const parsed = safeJsonParse(buffer.slice(start, end + 1));
      if (parsed) {
        const id = Number(parsed.id);
        if (Number.isInteger(id) && typeof parsed.text === 'string') {
          entries.push({ id, text: parsed.text });
        }
      }
      cursor = end + 1;
    }
  }

  function applyEntries(entries, allowedIds) {
    const allowed = allowedIds ? new Set(allowedIds) : null;
    let applied = 0;
    for (const entry of entries) {
      if (allowed && !allowed.has(entry.id)) continue;
      const block = state.byId.get(entry.id);
      if (!block) continue;
      const text = cleanMarkdownForDisplay(entry.text);
      if (!text) continue;
      state.translations.set(entry.id, { source: block.text, text });
      renderTranslation(block, text);
      applied++;
    }
    return applied;
  }

  function countRendered() {
    let count = 0;
    for (const [id, node] of state.nodes) {
      if (!node.isConnected) {
        // Turbo replaced the block this translation belonged to, so the
        // node is detached and both records are stale.
        state.nodes.delete(id);
        state.translations.delete(id);
        continue;
      }
      count++;
    }
    return count;
  }

  // ─── Panel (shadow DOM) ─────────────────────────────────────

  const PANEL_CSS = `
    :host { all: initial; }
    .panel {
      width: 258px;
      font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", "Noto Sans", Helvetica, Arial, sans-serif;
      font-size: 12px;
      line-height: 1.45;
      color: #1f2328;
      background: #ffffff;
      border: 1px solid rgba(31,35,40,.15);
      border-radius: 10px;
      box-shadow: 0 8px 24px rgba(140,149,159,.25);
      overflow: hidden;
    }
    .head {
      display: flex;
      align-items: center;
      gap: 6px;
      padding: 8px 10px;
      background: #f6f8fa;
      border-bottom: 1px solid rgba(31,35,40,.1);
      cursor: move;
      user-select: none;
    }
    .dot { width: 8px; height: 8px; border-radius: 50%; background: #8c959f; flex: none; }
    .dot.running { background: #bf8700; animation: pulse 1.2s ease-in-out infinite; }
    .dot.done { background: #1a7f37; }
    .dot.error { background: #cf222e; }
    @keyframes pulse { 0%,100% { opacity: 1; } 50% { opacity: .35; } }
    .title { font-weight: 600; font-size: 12px; flex: 1; }
    .collapse {
      border: none; background: transparent; color: #57606a;
      font-size: 14px; line-height: 1; padding: 2px 4px; cursor: pointer;
    }
    .collapse:hover { color: #1f2328; }
    .body { padding: 9px 10px 10px; }
    .status { color: #57606a; min-height: 16px; word-break: break-word; }
    .status.error { color: #cf222e; }
    .actions { display: flex; gap: 6px; margin-top: 8px; }
    button.action {
      flex: 1;
      border: 1px solid rgba(31,35,40,.15);
      border-radius: 6px;
      background: #f6f8fa;
      color: #24292f;
      font: inherit;
      font-weight: 600;
      padding: 6px 8px;
      cursor: pointer;
    }
    button.action:hover { background: #eef1f4; }
    button.action.primary { background: #1f883d; border-color: rgba(31,35,40,.15); color: #fff; }
    button.action.primary:hover { background: #1a7f37; }
    button.action[hidden] { display: none; }
    .hint { margin-top: 7px; color: #8c959f; font-size: 11px; }
    @media (prefers-color-scheme: dark) {
      .panel { background: #161b22; color: #e6edf3; border-color: #30363d; box-shadow: 0 8px 24px rgba(1,4,9,.6); }
      .head { background: #21262d; border-bottom-color: #30363d; }
      .status { color: #8b949e; }
      .status.error { color: #ff7b72; }
      button.action { background: #21262d; border-color: #30363d; color: #c9d1d9; }
      button.action:hover { background: #30363d; }
      button.action.primary { background: #238636; color: #fff; }
      button.action.primary:hover { background: #2ea043; }
      .collapse { color: #8b949e; }
      .collapse:hover { color: #e6edf3; }
    }
  `;

  function ensurePanel() {
    if (state.panel && state.panel.isConnected) return;
    const host = document.createElement('div');
    host.id = PANEL_ID;
    host.style.cssText = 'position:fixed;right:16px;bottom:16px;z-index:2147483000;';
    const shadow = host.attachShadow({ mode: 'open' });

    const style = document.createElement('style');
    style.textContent = PANEL_CSS;
    shadow.appendChild(style);

    const panel = document.createElement('div');
    panel.className = 'panel';
    panel.innerHTML = [
      '<div class="head">',
      '  <span class="dot"></span>',
      '  <span class="title">GitHub 翻译</span>',
      '  <button class="collapse" data-action="collapse" title="收起">–</button>',
      '</div>',
      '<div class="body">',
      '  <div class="status">正在扫描页面…</div>',
      '  <div class="actions">',
      '    <button class="action primary" data-action="translate">翻译本页</button>',
      '    <button class="action" data-action="cancel" hidden>取消</button>',
      '    <button class="action" data-action="toggle" hidden>显示原文</button>',
      '  </div>',
      '  <div class="hint">只翻译正文；代码、文件名与导航保持原样</div>',
      '</div>'
    ].join('');
    shadow.appendChild(panel);
    document.documentElement.appendChild(host);

    state.panel = host;
    state.shadow = shadow;
    state.els = {
      panel,
      dot: shadow.querySelector('.dot'),
      status: shadow.querySelector('.status'),
      body: shadow.querySelector('.body'),
      translate: shadow.querySelector('[data-action="translate"]'),
      cancel: shadow.querySelector('[data-action="cancel"]'),
      toggle: shadow.querySelector('[data-action="toggle"]'),
      collapse: shadow.querySelector('[data-action="collapse"]')
    };

    shadow.addEventListener('click', (event) => {
      const button = event.target.closest('[data-action]');
      if (!button) return;
      const action = button.dataset.action;
      if (action === 'translate') startTranslation(countRendered() > 0);
      else if (action === 'cancel') cancelTranslation();
      else if (action === 'toggle') toggleTranslations();
      else if (action === 'collapse') toggleCollapsed();
    });

    makeDraggable(host, shadow.querySelector('.head'));
  }

  function toggleCollapsed() {
    if (!state.els.body) return;
    const collapsed = state.els.body.style.display === 'none';
    state.els.body.style.display = collapsed ? '' : 'none';
    state.els.collapse.textContent = collapsed ? '–' : '+';
  }

  function makeDraggable(host, handle) {
    let dragging = null;
    handle.addEventListener('mousedown', (event) => {
      if (event.button !== 0) return;
      const rect = host.getBoundingClientRect();
      dragging = { dx: event.clientX - rect.left, dy: event.clientY - rect.top };
      event.preventDefault();
    });
    window.addEventListener('mousemove', (event) => {
      if (!dragging) return;
      const x = Math.max(0, Math.min(window.innerWidth - 268, event.clientX - dragging.dx));
      const y = Math.max(0, Math.min(window.innerHeight - 80, event.clientY - dragging.dy));
      host.style.left = x + 'px';
      host.style.top = y + 'px';
      host.style.right = 'auto';
      host.style.bottom = 'auto';
    });
    window.addEventListener('mouseup', () => { dragging = null; });
  }

  function setPanelState(kind, status, { translateLabel, translateHidden, cancelHidden, toggleHidden, toggleLabel } = {}) {
    if (!state.els.panel) return;
    state.els.dot.className = 'dot' + (kind ? ' ' + kind : '');
    state.els.status.textContent = status;
    state.els.status.classList.toggle('error', kind === 'error');
    if (translateLabel !== undefined) state.els.translate.textContent = translateLabel;
    if (translateHidden !== undefined) state.els.translate.hidden = !!translateHidden;
    if (cancelHidden !== undefined) state.els.cancel.hidden = !!cancelHidden;
    if (toggleHidden !== undefined) state.els.toggle.hidden = !!toggleHidden;
    if (toggleLabel !== undefined) state.els.toggle.textContent = toggleLabel;
  }

  function updateIdlePanel() {
    // An error message must survive the re-scan that follows a failed or
    // cancelled run; otherwise the panel silently flips back to "可翻译 N
    // 段" and the user never learns why nothing was translated.
    if (state.error && !state.translating) {
      const renderedOnError = countRendered();
      setPanelState(state.errorKind || 'error', state.error, {
        translateLabel: renderedOnError ? '重新翻译' : '翻译本页',
        translateHidden: false,
        cancelHidden: true,
        toggleHidden: !renderedOnError,
        toggleLabel: state.hidden ? '显示译文' : '显示原文'
      });
      return;
    }
    const rendered = countRendered();
    const pending = state.blocks.filter((block) => !state.translations.has(block.id)).length;
    let status;
    if (!state.blocks.length) {
      status = '本页没有找到可翻译的正文段落';
    } else if (pending === 0) {
      status = '已翻译 ' + rendered + ' 段';
    } else {
      status = '可翻译正文 ' + pending + ' 段' + (rendered ? '，已有译文 ' + rendered + ' 段' : '');
    }
    if (state.skippedBlocks > 0) {
      status += '（另有 ' + state.skippedBlocks + ' 段超出单次上限）';
    }
    setPanelState(
      rendered ? 'done' : '',
      status,
      {
        translateLabel: rendered ? '重新翻译' : '翻译本页',
        translateHidden: !state.blocks.length,
        cancelHidden: true,
        toggleHidden: !rendered,
        toggleLabel: state.hidden ? '显示译文' : '显示原文'
      }
    );
  }

  // ─── Scan ───────────────────────────────────────────────────

  function scan() {
    ensurePanel();
    const found = assignIds(collectBlocks(state.targetLang));

    // A block whose source text changed (Turbo re-rendered a comment in
    // place) must not keep a translation that belongs to the old text.
    for (const block of found) {
      const previous = state.translations.get(block.id);
      if (previous && previous.source !== block.text) {
        state.translations.delete(block.id);
        removeTranslationNode(block.id);
      }
    }

    state.blocks = found;
    state.byId.clear();
    for (const block of found) state.byId.set(block.id, block);

    // GitHub hydrates the server-rendered HTML after this script runs, so
    // the elements scanned a moment ago can be replaced by fresh nodes.
    // Re-bind their translations by source text instead of dropping them.
    if (!state.translating) adoptExistingTranslations();

    if (!state.translating) updateIdlePanel();
  }

  // Re-attach translations whose DOM node disappeared with a React/Turbo
  // re-render. The id cannot follow the element (the new node carries no
  // dataset), but the source text is stable, so it is the join key.
  function adoptExistingTranslations() {
    const orphaned = new Map();
    for (const [id, info] of state.translations) {
      const node = state.nodes.get(id);
      if (node && node.isConnected) continue;
      if (!orphaned.has(info.source)) orphaned.set(info.source, info.text);
      state.translations.delete(id);
      state.nodes.delete(id);
      if (node) node.remove();
    }
    if (!orphaned.size) return;
    for (const block of state.blocks) {
      if (state.translations.has(block.id)) continue;
      const text = orphaned.get(block.text);
      if (text === undefined) continue;
      state.translations.set(block.id, { source: block.text, text });
      renderTranslation(block, text);
    }
  }

  function scheduleScan() {
    if (state.scanTimer) clearTimeout(state.scanTimer);
    state.scanTimer = setTimeout(() => {
      state.scanTimer = null;
      try {
        scan();
      } catch (e) {
        console.warn('[PNT-GH] scan failed:', e && e.message);
      }
    }, SCAN_DEBOUNCE_MS);
  }

  // ─── Translation flow ───────────────────────────────────────

  function resetRunState() {
    state.requestId = '';
    state.batchIndex = 0;
    state.batchTotal = 0;
    state.batchIds = [];
    state.pendingIds = [];
    state.buffer = '';
    state.error = '';
    state.errorKind = '';
    state.truncated = false;
  }

  async function startTranslation(skipCache) {
    if (state.translating) return;

    const settings = await loadSettings();
    state.targetLang = settings.targetLang;
    state.thinkingType = settings.thinkingType;

    scan();

    let queue = state.blocks.slice();
    if (!skipCache) {
      // "Translate" on a fresh page is normally a first run; "重新翻译"
      // passes skipCache and deliberately redoes everything.
      queue = queue.filter((block) => !state.translations.has(block.id));
    } else {
      for (const block of state.blocks) {
        state.translations.delete(block.id);
        removeTranslationNode(block.id);
      }
    }

    if (!queue.length) {
      updateIdlePanel();
      return;
    }

    resetRunState();
    state.translating = true;
    state.pendingIds = queue.map((block) => block.id);
    for (const block of queue) renderTranslation(block, '翻译中…', { loading: true });
    setPanelState('running', '正在连接翻译服务…', {
      translateHidden: true,
      cancelHidden: false,
      toggleHidden: true
    });

    const response = await sendToBackground('TRANSLATE_GITHUB_STREAM', {
      blocks: queue.map((block) => ({ id: block.id, text: block.text })),
      targetLang: state.targetLang,
      thinkingType: state.thinkingType,
      skipCache: !!skipCache
    });

    if (!response.success) {
      failRun(response.error || '无法启动翻译');
      return;
    }
    // The background creates the id synchronously; events may already be
    // in flight, so only adopt it when no event has claimed one yet.
    if (!state.requestId && response.requestId) state.requestId = String(response.requestId);
  }

  async function cancelTranslation() {
    if (!state.translating) return;
    setPanelState('running', '正在取消…', { cancelHidden: true });
    await sendToBackground('CANCEL_GITHUB_TRANSLATE');
    // The worker sends GH_CANCELLED; a missing message must not strand
    // the panel, so restore the UI after a short grace period.
    setTimeout(() => {
      if (state.translating) failRun('翻译已取消', { cancelled: true });
    }, 3000);
  }

  function clearLoadingMarkers(cancelled) {
    // Only the blocks this run actually asked for can be marked failed.
    // Painting every unscanned paragraph on a 200-block page would bury
    // the page under error placeholders the request never covered.
    const ids = state.pendingIds.length
      ? state.pendingIds
      : state.blocks.map((block) => block.id);
    for (const id of ids) {
      if (state.translations.has(id)) continue;
      const block = state.byId.get(id);
      if (!block) continue;
      if (cancelled) removeTranslationNode(id);
      else renderTranslation(block, '翻译失败，可重试');
    }
  }

  function failRun(message, { cancelled = false } = {}) {
    if (!state.translating) return;
    state.translating = false;
    state.error = message;
    state.errorKind = cancelled ? '' : 'error';
    clearLoadingMarkers(cancelled);
    const rendered = countRendered();
    setPanelState(
      cancelled ? '' : 'error',
      message,
      {
        translateLabel: rendered ? '重新翻译' : '翻译本页',
        translateHidden: false,
        cancelHidden: true,
        toggleHidden: !rendered,
        toggleLabel: state.hidden ? '显示译文' : '显示原文'
      }
    );
    // Same reason as finishRun: let the next scan re-bind translations
    // whose nodes were replaced during the failed run.
    scheduleScan();
  }

  function finishRun() {
    if (!state.translating) return;
    state.translating = false;
    const rendered = countRendered();
    const missing = state.blocks.length - rendered;
    let status = '已翻译 ' + rendered + ' 段';
    if (state.truncated) status += '（部分内容被截断）';
    else if (missing > 0) status += '，' + missing + ' 段未返回译文';
    setPanelState('done', status, {
      translateLabel: '重新翻译',
      translateHidden: false,
      cancelHidden: true,
      toggleHidden: !rendered,
      toggleLabel: state.hidden ? '显示译文' : '显示原文'
    });
    // Re-scan with translating off: if GitHub's hydration replaced nodes
    // while the stream was running, the adopt pass re-attaches whatever
    // translations lost their DOM node.
    scheduleScan();
  }

  function toggleTranslations() {
    state.hidden = !state.hidden;
    for (const node of state.nodes.values()) {
      node.style.display = state.hidden ? 'none' : '';
    }
    if (state.els.toggle) state.els.toggle.textContent = state.hidden ? '显示译文' : '显示原文';
  }

  function acceptRequest(requestId) {
    if (!state.translating) return false;
    if (!state.requestId) {
      state.requestId = String(requestId || '');
      return true;
    }
    return String(requestId || '') === state.requestId;
  }

  function batchProgressText() {
    const batch = state.batchTotal ? Math.min(state.batchIndex + 1, state.batchTotal) : 0;
    const parts = [];
    if (batch) parts.push('第 ' + batch + '/' + state.batchTotal + ' 批');
    parts.push('已完成 ' + countRendered() + ' 段');
    return parts.join(' · ');
  }

  // ─── Background events ──────────────────────────────────────

  function handleBackgroundEvent(message) {
    if (!acceptRequest(message.requestId)) return;
    const data = message.data || {};

    switch (message.type) {
      case 'GH_START': {
        state.batchTotal = Number(data.batches) || 0;
        setPanelState('running', '开始翻译 ' + (Number(data.totalBlocks) || 0) + ' 段…', {
          translateHidden: true,
          cancelHidden: false,
          toggleHidden: true
        });
        break;
      }
      case 'GH_BATCH_START': {
        state.batchIndex = Number(data.index) || 0;
        state.batchTotal = Number(data.total) || state.batchTotal;
        state.batchIds = Array.isArray(data.ids) ? data.ids.map(Number) : [];
        state.buffer = '';
        setPanelState('running', batchProgressText(), { cancelHidden: false });
        break;
      }
      case 'GH_SERVER_ACK': {
        setPanelState('running', batchProgressText() + ' · 模型处理中…', { cancelHidden: false });
        break;
      }
      case 'GH_CONNECTED': {
        setPanelState('running', batchProgressText() + ' · 正在生成…', { cancelHidden: false });
        break;
      }
      case 'GH_TOKEN': {
        state.buffer += typeof message.token === 'string' ? message.token : '';
        const { entries, rest } = drainJsonObjects(state.buffer);
        state.buffer = rest;
        if (entries.length) {
          applyEntries(entries, state.batchIds);
          setPanelState('running', batchProgressText(), { cancelHidden: false });
        }
        break;
      }
      case 'GH_BATCH_DONE': {
        // Flush the buffered tail first, then take the completed text as
        // the authoritative fallback: a model that forgot the final
        // newline must not lose its last paragraph.
        const drained = drainJsonObjects(state.buffer);
        applyEntries(drained.entries, state.batchIds);
        state.buffer = '';
        if (typeof data.translatedText === 'string' && data.translatedText) {
          const full = drainJsonObjects(data.translatedText);
          applyEntries(full.entries, state.batchIds);
        }
        if (data.truncated) state.truncated = true;
        setPanelState('running', batchProgressText(), { cancelHidden: false });
        break;
      }
      case 'GH_DONE': {
        finishRun();
        break;
      }
      case 'GH_ERROR': {
        failRun(message.error || '翻译失败');
        break;
      }
      case 'GH_CANCELLED': {
        failRun('翻译已取消', { cancelled: true });
        break;
      }
      default:
        break;
    }
  }

  // ─── Navigation + observers ─────────────────────────────────

  function onNavigate() {
    // A Turbo navigation replaces the body. Drop every translation and
    // id before the new content is scanned, so nothing from the previous
    // page can be matched onto a reused DOM node.
    state.translating = false;
    resetRunState();
    clearTranslations();
    state.blocks = [];
    state.byId.clear();
    state.nextId = 1;
    ensurePanel();
    scheduleScan();
  }

  function installNavigationHooks() {
    // Turbo is GitHub's SPA layer; the pjax event covers older layouts.
    document.addEventListener('turbo:load', onNavigate);
    document.addEventListener('turbo:render', onNavigate);
    document.addEventListener('pjax:end', onNavigate);
    window.addEventListener('popstate', onNavigate);
  }

  function installObserver() {
    if (state.observer || !document.body) return;
    state.observer = new MutationObserver((records) => {
      for (const record of records) {
        // Ignore our own insertions/removals; reacting to them would make
        // the observer re-scan on every translated paragraph.
        if (record.target instanceof Element && record.target.closest('.pnt-gh-trans, #' + PANEL_ID)) continue;

        // Only the rendered-Markdown surface can produce new translatable
        // blocks. GitHub updates timers, tooltips, menus and notification
        // counters constantly; re-scanning for those burns layout work.
        let relevant = record.target instanceof Element
          && !!record.target.closest('.markdown-body');
        if (!relevant) {
          for (const node of [...record.addedNodes, ...record.removedNodes]) {
            if (node.nodeType !== 1) continue;
            if (node.classList && node.classList.contains('pnt-gh-trans')) continue;
            if (node.id === PANEL_ID) continue;
            if (node.matches?.('.markdown-body') || node.querySelector?.('.markdown-body')) {
              relevant = true;
              break;
            }
          }
        }
        if (relevant) {
          scheduleScan();
          return;
        }
      }
    });
    state.observer.observe(document.body, { childList: true, subtree: true });
  }

  // ─── Messages from popup / background ───────────────────────

  chrome.runtime.onMessage.addListener((message, sender, sendResponse) => {
    if (sender.id !== chrome.runtime.id || !message || typeof message.type !== 'string') {
      sendResponse({ ok: false });
      return false;
    }
    if (message.type === 'PING') {
      // The popup probes with PING before injecting; an explicit answer
      // keeps it from re-injecting the script on every click.
      sendResponse({ pong: true });
      return false;
    }
    if (message.type === 'GH_MANUAL_TRANSLATE') {
      startTranslation(countRendered() > 0).catch((e) => failRun(e && e.message ? e.message : String(e)));
      sendResponse({ ok: true });
      return false;
    }
    if (message.type === 'GH_SCAN') {
      scan();
      sendResponse({ ok: true, data: { blocks: state.blocks.length } });
      return false;
    }
    if (message.type.startsWith('GH_')) {
      handleBackgroundEvent(message);
      sendResponse({ ok: true });
      return false;
    }
    return false;
  });

  // ─── Init ───────────────────────────────────────────────────

  function init() {
    if (!/^https:\/\/github\.com\//.test(location.href)) return;
    ensurePanel();
    installNavigationHooks();
    installObserver();
    scan();
  }

  if (document.readyState === 'loading') {
    document.addEventListener('DOMContentLoaded', init);
  } else {
    init();
  }
})();
