// ============================================================
// Pixiv Novel Translator — Background Service Worker
// ============================================================

// ─── Active translations (per-tab, for cancellation) ─────────

const PNT_BG_VERSION = '1.2.0';
console.log('[PNT] background.js v' + PNT_BG_VERSION + ' loaded');

// ─── Automatic update checks ────────────────────────────────
// Unpacked/non-store extensions cannot replace their own files. We still
// check releases in the background so the user does not have to remember
// to look for updates, then show a badge + one OS notification per release.
const UPDATE_ALARM = 'pnt-update-check';
const UPDATE_PERIOD_MINUTES = 360; // check at most four times per day
const UPDATE_REPO = 'SparkofSpike/Sh1Zuku_Translate';
const UPDATE_API_URL = 'https://api.github.com/repos/' + UPDATE_REPO + '/releases/latest';

let updateCheckInFlight = null;

function currentExtensionVersion() {
  return String(chrome.runtime.getManifest().version || '0.0.0');
}

function normalizeVersion(version) {
  const value = String(version || '')
    .replace(/^v/i, '')
    .split('+')[0]
    .split('-')[0]
    .trim();
  return /^[0-9]+(?:[.][0-9]+)*$/.test(value) ? value : '';
}

function compareVersions(a, b) {
  const pa = normalizeVersion(a).split('.').map(Number);
  const pb = normalizeVersion(b).split('.').map(Number);
  if (!pa[0] || !pb[0]) return 0;
  for (let i = 0; i < Math.max(pa.length, pb.length); i++) {
    const left = pa[i] || 0;
    const right = pb[i] || 0;
    if (left !== right) return left - right;
  }
  return 0;
}

function getUpdateState() {
  return new Promise((resolve) => {
    chrome.storage.local.get('pntUpdate', (items) => resolve(items.pntUpdate || null));
  });
}

function setUpdateState(state) {
  return new Promise((resolve) => {
    chrome.storage.local.set({ pntUpdate: state }, resolve);
  });
}

function setUpdateBadge(available) {
  return new Promise((resolve) => {
    chrome.action.setBadgeText({ text: available ? '!' : '' }, () => {
      chrome.action.setBadgeBackgroundColor({ color: '#1a73e8' }, resolve);
    });
  });
}

async function checkForUpdates({ notify = true } = {}) {
  if (updateCheckInFlight) return updateCheckInFlight;

  updateCheckInFlight = (async () => {
    const current = normalizeVersion(currentExtensionVersion()) || '0.0.0';
    try {
      const response = await fetch(UPDATE_API_URL, {
        headers: { 'Accept': 'application/vnd.github+json' },
        signal: AbortSignal.timeout(10000)
      });
      if (!response.ok) throw new Error('HTTP ' + response.status);

      const release = await response.json();
      const latest = normalizeVersion(release.tag_name);
      if (!latest) throw new Error('GitHub release has no semantic version tag');

      const available = compareVersions(latest, current) > 0;
      const previous = await getUpdateState();
      const state = {
        available,
        currentVersion: current,
        latestVersion: latest,
        releaseUrl: release.html_url || ('https://github.com/' + UPDATE_REPO + '/releases/latest'),
        checkedAt: Date.now(),
        lastNotifiedVersion: previous?.lastNotifiedVersion || ''
      };
      await setUpdateBadge(available);

      if (available && notify && state.lastNotifiedVersion !== latest) {
        state.lastNotifiedVersion = latest;
        await setUpdateState(state);
        chrome.notifications.create('pnt-update-' + latest.replace(/[^0-9.]/g, '-'), {
          type: 'basic',
          iconUrl: 'icons/icon128.png',
          title: 'Pixiv 小说翻译有新版本',
          message: '发现版本 ' + latest + '，点击查看下载和更新说明。'
        });
      } else {
        await setUpdateState(state);
      }
      return state;
    } catch (error) {
      const state = {
        available: false,
        currentVersion: current,
        latestVersion: '',
        releaseUrl: '',
        checkedAt: Date.now(),
        error: error?.message || 'network error'
      };
      await setUpdateState(state);
      return state;
    } finally {
      updateCheckInFlight = null;
    }
  })();

  return updateCheckInFlight;
}

function ensureUpdateAlarm() {
  chrome.alarms.get(UPDATE_ALARM, (alarm) => {
    if (!alarm) {
      chrome.alarms.create(UPDATE_ALARM, { periodInMinutes: UPDATE_PERIOD_MINUTES });
    }
  });
}

chrome.runtime.onInstalled.addListener(() => {
  ensureUpdateAlarm();
  // Do not show a notification immediately after installation/update.
  checkForUpdates({ notify: false });
});

chrome.runtime.onStartup.addListener(() => {
  ensureUpdateAlarm();
  checkForUpdates();
});

chrome.notifications.onClicked.addListener((notificationId) => {
  if (!notificationId.startsWith('pnt-update-')) return;
  getUpdateState().then((state) => {
    if (state?.releaseUrl) chrome.tabs.create({ url: state.releaseUrl });
  });
  chrome.notifications.clear(notificationId);
});

ensureUpdateAlarm();

// One AbortController per tab: translating in two tabs at once must
// not clobber each other's cancellation handle.
const activeControllers = new Map();

// ─── Error log (for the popup's "submit log" button) ───────
// Keep the most recent translation errors in local storage so the user
// can report them to the server with one click (popup → POST
// /api/v1/plugin/logs). User cancellations are not errors and skipped.
const ERROR_LOG_KEY = 'pntErrorLog';
const MAX_ERROR_LOG = 20;
let errorLogWrite = Promise.resolve();

function recordPluginError(message) {
  if (!message) return;
  const entry = {
    time: new Date().toISOString(),
    message: String(message).slice(0, 2000)
  };
  // Serialize read-modify-write operations so errors arriving together do
  // not overwrite one another in storage.
  errorLogWrite = errorLogWrite
    .then(() => new Promise((resolve) => {
      chrome.storage.local.get(ERROR_LOG_KEY, (items) => {
        const log = Array.isArray(items[ERROR_LOG_KEY]) ? items[ERROR_LOG_KEY] : [];
        log.push(entry);
        if (log.length > MAX_ERROR_LOG) log.shift();
        chrome.storage.local.set({ [ERROR_LOG_KEY]: log }, resolve);
      });
    }))
    .catch((error) => {
      console.warn('[PixivTranslator] Failed to store plugin error:', error);
    });
}

// ─── Keepalive (MV3 service worker) ─────────────────────────
// The long SSE fetch keeps the SW alive while it is in flight, but the
// gaps between steps (Pixiv fetch done → settings → backend fetch) can
// exceed the idle-reclaim timer. A periodic alarm resets it, so a
// several-minute full-novel translation is not killed mid-stream.

const KEEPALIVE_ALARM = 'pnt-keepalive';
let keepaliveRefs = 0;

chrome.alarms.onAlarm.addListener((alarm) => {
  if (alarm.name === KEEPALIVE_ALARM) {
    // No-op: firing the alarm itself resets the SW idle timer.
  } else if (alarm.name === UPDATE_ALARM) {
    checkForUpdates();
  }
});

function keepaliveStart() {
  keepaliveRefs++;
  if (keepaliveRefs === 1) {
    chrome.alarms.create(KEEPALIVE_ALARM, { periodInMinutes: 0.5 });
  }
}

function keepaliveStop() {
  keepaliveRefs = Math.max(0, keepaliveRefs - 1);
  if (keepaliveRefs === 0) {
    chrome.alarms.clear(KEEPALIVE_ALARM);
  }
}

// ─── Message Handler ─────────────────────────────────────────

chrome.runtime.onMessage.addListener((message, sender, sendResponse) => {
  if (sender.id !== chrome.runtime.id || !message || typeof message.type !== 'string') {
    sendResponse({ success: false, error: '非法消息' });
    return false;
  }

  switch (message.type) {
    case 'TRANSLATE_NOVEL_STREAM': {
      // Acknowledge immediately; the streaming work runs detached and
      // reports progress/errors via tabs.sendMessage. Responding only
      // after the whole stream would hold the message port open for
      // minutes — a needless SW keepalive dependency.
      const tabId = sender.tab?.id ?? null;
      startStreamingTranslation(
        message.novelId,
        message.targetLang || 'zh',
        tabId,
        message.selectedPresets || [],
        message.customPrompt || '',
        message.model || 'deepseek-v4-flash',
        message.modelProfileId || null,
        message.currentPage || 0,
        !!message.fullMode,
        message.thinkingType,
        message.displayMode
      ).catch((error) => {
        const msg = error && error.message ? error.message : String(error);
        // An aborted body stream is almost always the user pressing cancel
        // (Edge reports it as "BodyStreamBuffer was aborted"). Treat it as
        // an expected cancellation, not an error — no scary console.error.
        if (/abort|取消/i.test(msg)) {
          console.warn('[PixivTranslator] Translation cancelled:', msg);
        } else {
          console.error('[PixivTranslator] Error:', msg);
          recordPluginError(msg);
          notifyTab(tabId, { type: 'SSE_ERROR', error: msg });
        }
      });
      sendResponse({ success: true });
      break;
    }

    case 'CANCEL_TRANSLATE': {
      // Cancel by tab so a request in another tab is never aborted.
      const key = sender.tab?.id ?? 'unknown';
      const controller = activeControllers.get(key);
      if (controller) {
        controller.abort();
        activeControllers.delete(key);
        sendResponse({ success: true });
      } else {
        sendResponse({ success: false, error: '没有进行中的翻译' });
      }
      break;
    }

    case 'GET_UPDATE_STATUS':
      getUpdateState().then((state) => {
        sendResponse({ success: true, update: state });
      });
      return true;

    case 'CHECK_FOR_UPDATES':
      checkForUpdates({ notify: false }).then((state) => {
        sendResponse({ success: true, update: state });
      });
      return true;

    case 'PING':
      sendResponse({ pong: true });
      break;

    default:
      sendResponse({ success: false, error: '未知消息类型' });
  }
  return false; // all branches respond synchronously
});

// ─── Main Flow: Fetch from Pixiv → Stream Translate ─────────

async function startStreamingTranslation(novelId, targetLang, tabId, selectedPresets = [], customPrompt = '', model = 'deepseek-v4-flash', modelProfileId = null, currentPage = 0, fullMode = false, thinkingType, displayMode) {
  const safeNovelId = String(novelId || '').match(/^\d+$/) ? String(novelId) : '';
  if (!safeNovelId) {
    throw new Error('无效的小说 ID');
  }
  // Create abort controller up-front so cancellation works even during
  // the Pixiv fetch (STEP1), not just the backend SSE stream (STEP3).
  const controller = new AbortController();
  const key = tabId ?? 'unknown';
  // Same-tab re-entry (should not happen — the content script guards
  // with state.translating) would otherwise leak the old controller.
  const prev = activeControllers.get(key);
  if (prev) prev.abort();
  activeControllers.set(key, controller);
  keepaliveStart();

  try {
    // Step 1: fetch novel from Pixiv API
    console.log('[PNT] STEP1 fetchNovelFromPixiv start, novelId=' + safeNovelId);
    const novel = await fetchNovelFromPixiv(safeNovelId, controller.signal);
    console.log('[PNT] STEP1 done, title=' + (novel.title || '?'));

    // All inline modes (single-page, paged, full) number their paragraphs
    // and receive JSON Lines output, so the content script can map every
    // translation back to the exact DOM paragraph by id — a model that
    // merges or splits paragraphs can no longer shift the mapping. Only
    // plain panel mode on a non-paged novel stays unnumbered.
    const numbered = fullMode || currentPage > 0 || displayMode === 'inline';

    // Notify content script: novel loaded, begin streaming
    await notifyTab(tabId, {
      type: 'SSE_NOVEL_LOADED',
      data: {
        id: novel.id,
        title: novel.title,
        author: novel.userName,
        originalContent: novel.content,
        tags: novel.tags || [],
        characterCount: novel.characterCount,
        // The content script needs to know whether this request produces
        // numbered paragraphs + JSON Lines output (all inline/paged/full
        // modes) so it can pick the right streaming renderer. Only plain
        // panel mode on a non-paged novel stays unnumbered.
        numberedRequest: numbered,
        fullMode
      }
    });

    // Step 2: load settings
    const settings = await loadSettings();

    // Step 3: stream translate via backend
    // Paged novels: translate only the current page (the DOM only shows
    // that page). The neighbouring pages are passed as context so the
    // model keeps continuity — they are marked as reference, not output.
    // fullMode translates the WHOLE novel in one request with global
    // paragraph ids; the content script maps ids back to whatever page
    // the user is reading, so flipping pages never needs a re-translate.
    const sourceText = fullMode ? buildFullSource(novel.content) : buildPageSource(novel.content, currentPage, numbered);
    await streamTranslateApi(
      settings.backendUrl,
      settings.apiKey,
      settings.model,
      settings.modelProfileId,
      sourceText,
      targetLang,
      selectedPresets,
      customPrompt,
      thinkingType,
      controller,
      async () => {
        await notifyTab(tabId, { type: 'SSE_CONNECTED' });
      },
      async (token) => {
        await notifyTab(tabId, { type: 'SSE_TOKEN', token });
      },
      async (result) => {
        await notifyTab(tabId, { type: 'SSE_DONE', data: result });
      },
      async (error) => {
        recordPluginError(error);
        await notifyTab(tabId, { type: 'SSE_ERROR', error });
      }
    );
  } finally {
    // Clean up controller + keepalive on every exit path, STEP1/2
    // failures included — not only after the stream completes.
    if (activeControllers.get(key) === controller) {
      activeControllers.delete(key);
    }
    keepaliveStop();
  }
}

// Full-novel request: every paragraph of every page gets a GLOBAL id
// ([1]..[N], continuous across [newpage] page breaks). The content
// script computes the per-page id offset from originalContent so it can
// render each page's translations as the user flips to it — the SSE
// stream keeps running while the user reads/flips (never interrupted).
function buildFullSource(fullText) {
  const pages = fullText
    .split(/\[newpage\]/i)
    .map((p) => p.trim())
    .filter((p) => p.length > 0);

  const numbered = [];
  let id = 1;
  for (const page of pages) {
    const paras = page
      .split(/\n{2,}/)
      .map((p) => p.trim())
      .filter((p) => p.length > 0);
    for (const para of paras) {
      numbered.push(`[${id}] ${para}`);
      id++;
    }
    numbered.push(''); // blank line between pages
  }
  return `【全文翻译（所有段落已编号）】\n` + numbered.join('\n');
}

// Number every paragraph of a text as [1]..[N]. Used for single-page
// novels in inline mode so the model's JSON Lines ids map 1:1 onto the
// DOM paragraphs. Also the base for the per-page numbering below.
function numberAllParagraphs(fullText) {
  return fullText
    .split(/\n{2,}/)
    .map((p) => p.trim())
    .filter((p) => p.length > 0)
    .map((p, i) => `[${i + 1}] ${p}`)
    .join('\n\n');
}

// Build the request text for a possibly paged novel.
// Pixiv marks page breaks with [newpage]; each page is translated
// independently in inline mode because the DOM only renders the page
// the user is currently reading. The full text stays as context: the
// page before and after the target page are included as "reference
// only" sections, and the prompt tells the model not to translate them.
function buildPageSource(fullText, currentPage, numbered) {
  const pages = fullText
    .split(/\[newpage\]/i)
    .map((p) => p.trim())
    .filter((p) => p.length > 0);

  if (!currentPage || currentPage <= 0 || pages.length <= 1) {
    // Single-page novel (or a "paged" marker with only one real page):
    // panel mode keeps the raw text (display only), but inline mode
    // numbers every paragraph so the model emits id-tagged JSON Lines
    // and the content script maps each translation back to the exact
    // DOM paragraph. Without numbering, a single merged paragraph
    // shifts every later translation one slot and the inline pairs
    // misalign — the root cause of intermittent 错段 on short novels.
    if (!numbered) return fullText;
    return `【待翻译文本（段落已编号）】\n` + numberAllParagraphs(fullText);
  }

  const idx = Math.min(Math.max(currentPage - 1, 0), pages.length - 1);
  const current = pages[idx] || '';

  // Keep the model grounded: a slice of the previous and next page as
  // context, clearly marked so it is not translated.
  const ctxBefore = idx > 0 ? pages[idx - 1].slice(-800) : '';
  const ctxAfter = idx < pages.length - 1 ? pages[idx + 1].slice(0, 800) : '';

  let text = '';
  if (ctxBefore) {
    text += `【参考上下文·上一页（仅理解用，不要翻译）】\n${ctxBefore}\n\n`;
  }
  if (ctxAfter) {
    text += `【参考上下文·下一页（仅理解用，不要翻译）】\n${ctxAfter}\n\n`;
  }

  // Number the paragraphs of the current page so the model can reference
  // them in its output and the content script can map each translated
  // paragraph back to the exact DOM paragraph, even when the model merges
  // or splits paragraphs. Without numbering, a single merged paragraph
  // shifts every later translation one slot and the inline pairs misalign.
  text += `【当前页（第 ${currentPage} 页，请翻译这部分）】\n` + numberAllParagraphs(current);
  return text;
}

// ─── Send message to a specific tab (content script) ────────

async function notifyTab(tabId, message) {
  if (!tabId) return;
  try {
    await chrome.tabs.sendMessage(tabId, message);
  } catch (e) {
    // Content script may not be injected yet; ignore
    console.warn('[PixivTranslator] notifyTab failed:', e.message);
  }
}

// ─── Step 1: Fetch Pixiv Novel ───────────────────────────────

async function fetchNovelFromPixiv(novelId, signal) {
  // PHPSESSID is only a login hint — the Pixiv AJAX novel API serves
  // public novels without any cookie (verified: bare fetch returns 200
  // with no login). Reading the cookie may also throw "No host
  // permissions for cookies at url" when the extension's cookie host
  // permission is missing or revoked (e.g. Edge restricted "all sites"
  // access), and that must NOT abort the whole translation. So this
  // read is best-effort: if it fails we simply cannot tell the login
  // state, and we continue the bare fetch anyway.
  let hasLogin = false;
  let cookieUnavailable = false;
  try {
    const cookie = await chrome.cookies.get({
      url: 'https://www.pixiv.net',
      name: 'PHPSESSID'
    });
    hasLogin = !!cookie;
  } catch (e) {
    // No host permission for cookies — cannot check login state.
    // Not an error: public novels still translate fine without it.
    cookieUnavailable = true;
    console.warn('[PNT] STEP1 cookie read unavailable (no host permission?); continuing:', e && e.message);
  }

  console.log('[PNT] STEP1 fetch pixiv api...');
  // NOTE: do NOT set Cookie / User-Agent / Referer headers manually —
  // they are forbidden headers in browser fetch; Edge rejects such
  // cross-origin requests with TypeError "Failed to fetch". The Pixiv
  // AJAX API works without them (verified: bare fetch returns 200).
  // PHPSESSID auth is handled by Chrome automatically via host
  // permission + credentials when needed.
  const response = await fetch(`https://www.pixiv.net/ajax/novel/${novelId}`, {
    // Extension-origin fetches are cross-origin; explicitly include the
    // browser-managed Pixiv session for login-gated novels.
    credentials: 'include',
    signal
  }).catch((e) => {
    if (e && e.name === 'AbortError') {
      console.log('[PNT] STEP1 aborted by user');
      throw new Error('翻译已取消');
    }
    console.error('[PNT] STEP1 FAIL pixiv fetch:', e && e.message, e && e.cause ? String(e.cause) : '');
    throw new Error('Pixiv 请求失败: ' + (e && e.message ? e.message : 'network error'));
  });

  if (!response.ok) {
    // 401/403 usually means login-gated content.
    if (response.status === 401 || response.status === 403) {
      if (!cookieUnavailable && !hasLogin) {
        // Cookie was readable and there was no session — clear hint.
        throw new Error('未登录 Pixiv，请先登录 pixiv.net 后重试');
      }
      if (cookieUnavailable) {
        // Cookie host permission was not granted (common on Chrome for
        // unpacked extensions with wildcard hosts): the bare fetch may
        // not have carried the session cookie either. Give the user a
        // path to restore it instead of a dead end.
        throw new Error(`Pixiv 返回 ${response.status}，该小说可能需要登录。已登录仍失败时，请在浏览器扩展详情中允许插件访问 pixiv.net`);
      }
      // Cookie readable + logged in: the novel itself is members-only.
      throw new Error(`Pixiv 返回 ${response.status}，该小说可能需要登录 Pixiv 后翻译`);
    }
    throw new Error(`Pixiv API 请求失败 (${response.status})`);
  }

  const result = await response.json();
  if (result.error || !result.body) {
    throw new Error('Pixiv 返回错误，小说可能不存在或需要登录');
  }

  return result.body;
}

// ─── Step 2: Load Settings from Storage ──────────────────────

async function loadSettings() {
  return new Promise((resolve) => {
    chrome.storage.sync.get(['backendUrl', 'apiKey', 'model', 'modelProfileId'], (items) => {
      resolve({
        backendUrl: items.backendUrl || '',
        apiKey: items.apiKey || '',
        model: items.model || 'deepseek-v4-flash',
        modelProfileId: items.modelProfileId !== null && items.modelProfileId !== undefined && items.modelProfileId !== ''
          ? Number(items.modelProfileId) : 0
      });
    });
  });
}

// ─── Step 3: Call Backend SSE Stream API ─────────────────────

async function streamTranslateApi(backendUrl, apiKey, model, modelProfileId, text, targetLang, selectedPresets, customPrompt, thinkingType, controller, onConnected, onToken, onDone, onError) {
  if (!backendUrl) {
    throw new Error('请先在插件设置中配置后端地址');
  }
  if (!apiKey) {
    throw new Error('请先在插件设置中配置 API Key');
  }

  const url = backendUrl.replace(/\/+$/, '') + '/api/v1/translate/stream';
  const targetLangName = targetLang === 'en' ? '英语' : targetLang === 'ko' ? '韩语' : '简体中文';

  // Merge base prompt with user custom prompt
  let prompt = `请将以下日语小说内容翻译为${targetLangName}。保留原文的段落结构和换行。`;
  if (customPrompt && customPrompt.trim()) {
    prompt += `\n\n用户额外指示：` + customPrompt.trim();
  }

  // Numbered requests (buildPageSource paged/inline + buildFullSource)
  // carry [编号] markers and must be answered with id-tagged JSON Lines.
  // The example lines matter: without them the flash model drifts into
  // plain-text output, which the fallback renderer maps by position and
  // misaligns as soon as one paragraph is merged or split.
  if (text.includes('【当前页') || text.includes('【全文翻译') || text.includes('【待翻译文本')) {
    prompt += `

请只翻译标记了 [编号] 的段落，逐段输出 JSON Lines 格式：每一行是一个完整、合法的 JSON 对象，id 与输入的段落编号一一对应，text 是该段的译文。`;
    if (text.includes('【参考上下文')) {
      prompt += `
【参考上下文】部分仅用于理解情节，不要翻译、不要输出。`;
    }
    prompt += `
要求：
1. 每行一个 JSON 对象，用双引号，不要注释、不要 Markdown 代码块标记、不要任何额外文字；
2. id 与输入 [编号] 一一对应，顺序不变，不得合并或遗漏；
3. 译文内部的换行用 \n 转义写在 text 里。

示例：
{"id":1,"text":"第一段的译文"}
{"id":2,"text":"第二段的译文"}
{"id":3,"text":"第三段的译文"}`;
  }


  console.log('[PNT] STEP3 fetch backend:', url);
  // Full-novel requests can take minutes to pre-fill (huge prompt), so a
  // fixed total timeout would kill legitimate translations. Instead wait
  // up to 10 min for the FIRST token, then clear the timer — after that
  // the stream runs without an artificial cap (the backend enforces its
  // own async timeout). User cancellation still wins via AbortSignal.any
  // (Chrome/Edge 116+).
  // Match the backend's upstream read timeout. Long model pre-fill is slow,
  // but should still fail eventually instead of hanging forever.
  const firstTokenTimeoutMs = 600000;
  const firstTokenController = new AbortController();
  const signal = typeof AbortSignal.any === 'function'
    ? AbortSignal.any([controller.signal, firstTokenController.signal])
    : controller.signal;
  // Timer stays harmless after the stream ends (aborting a finished
  // fetch is a no-op), so no explicit cleanup is needed.
  let firstTokenTimer = setTimeout(() => {
    firstTokenController.abort();
  }, firstTokenTimeoutMs);
  const response = await fetch(url, {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
      'X-API-Key': apiKey
    },
    body: JSON.stringify({
      sourceText: text,
      model: model || 'deepseek-v4-flash',
      modelProfileId: modelProfileId === null || modelProfileId === undefined ? 0 : modelProfileId,
      customPrompt: prompt,
      thinkingType: thinkingType || undefined,
      presets: (selectedPresets && selectedPresets.length > 0) ? selectedPresets : undefined
    }),
    signal
  }).catch((e) => {
    if (e && e.name === 'AbortError' && controller.signal.aborted) {
      throw new Error('翻译已取消');
    }
    if (e && (e.name === 'TimeoutError'
        || (e.name === 'AbortError' && firstTokenController.signal.aborted))) {
      throw new Error('AI 响应超时（10 分钟未收到首个 token），请重试');
    }
    console.error('[PNT] STEP3 FAIL backend fetch:', url, '->', e && e.message, e && e.cause ? String(e.cause) : '');
    throw new Error('翻译服务请求失败(网络): ' + (e && e.message ? e.message : 'network error'));
  });

  if (!response.ok) {
    const body = await response.text().catch(() => '');
    throw new Error(`翻译服务请求失败 (${response.status}): ${body}`);
  }
  const reader = response.body?.getReader();
  if (!reader) {
    throw new Error('浏览器不支持流式响应');
  }

  const decoder = new TextDecoder();
  let buffer = '';
  let streamEnded = false; // set once a done/error event was delivered

  const processSseLine = async (line) => {
    const trimmed = line.trim();
    if (!trimmed.startsWith('data:')) return;

    const data = trimmed.substring(5).trim();
    if (!data) return;

    let event;
    try {
      event = JSON.parse(data);
    } catch (parseErr) {
      console.warn('[PixivTranslator] Bad SSE chunk:', data);
      return;
    }

    if (event.status === 'ai-connected') {
      await onConnected();
    } else if (typeof event.token === 'string') {
      // First token received: the pre-fill wait is over, drop the
      // timeout so a long full-novel stream is never cut short.
      if (firstTokenTimer) {
        clearTimeout(firstTokenTimer);
        firstTokenTimer = null;
      }
      await onToken(event.token);
    } else if (event.done) {
      streamEnded = true;
      await onDone({
        translatedText: event.translatedText,
        id: event.id,
        tokenUsage: event.tokenUsage
      });
    } else if (event.error) {
      streamEnded = true;
      await onError(event.error);
    }
  };

  while (true) {
    const { done, value } = await reader.read();
    if (done) break;

    buffer += decoder.decode(value, { stream: true });
    const lines = buffer.split('\n');
    buffer = lines.pop() || '';

    for (const line of lines) {
      await processSseLine(line);
    }
  }

  // A final SSE event is valid even when the server closes without a
  // trailing newline. Flush the decoder and process that buffered line.
  buffer += decoder.decode();
  if (buffer) await processSseLine(buffer);
  if (firstTokenTimer) {
    clearTimeout(firstTokenTimer);
    firstTokenTimer = null;
  }

  // The body stream ended without the backend sending done/error (proxy
  // cut, backend crash): the content script would otherwise stay stuck
  // in "translating" forever. Surface it explicitly.
  if (!streamEnded) {
    await onError('翻译流意外中断，请重试');
  }
}
