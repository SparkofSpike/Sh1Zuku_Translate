// ============================================================
// Pixiv Novel Translator — Background Service Worker
// ============================================================

// ─── Active translations (per-tab, for cancellation) ─────────

const PNT_BG_VERSION = '1.1.0';
console.log('[PNT] background.js v' + PNT_BG_VERSION + ' loaded');
// One AbortController per tab: translating in two tabs at once must
// not clobber each other's cancellation handle.
const activeControllers = new Map();

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
        message.currentPage || 0,
        !!message.fullMode
      ).catch((error) => {
        const msg = error && error.message ? error.message : String(error);
        // An aborted body stream is almost always the user pressing cancel
        // (Edge reports it as "BodyStreamBuffer was aborted"). Treat it as
        // an expected cancellation, not an error — no scary console.error.
        if (/abort/i.test(msg)) {
          console.warn('[PixivTranslator] Translation cancelled:', msg);
        } else {
          console.error('[PixivTranslator] Error:', msg);
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

    case 'PING':
      sendResponse({ pong: true });
      break;

    default:
      sendResponse({ success: false, error: '未知消息类型' });
  }
  return false; // all branches respond synchronously
});

// ─── Main Flow: Fetch from Pixiv → Stream Translate ─────────

async function startStreamingTranslation(novelId, targetLang, tabId, selectedPresets = [], customPrompt = '', currentPage = 0, fullMode = false) {
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
    console.log('[PNT] STEP1 fetchNovelFromPixiv start, novelId=' + novelId);
    const novel = await fetchNovelFromPixiv(novelId, controller.signal);
    console.log('[PNT] STEP1 done, title=' + (novel.title || '?'));

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
        // The content script needs to know whether this request is the
        // paged-novel flow (numbered paragraphs + JSON Lines output) so it
        // can pick the right streaming renderer.
        pagedRequest: currentPage > 0 || fullMode,
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
    const sourceText = fullMode ? buildFullSource(novel.content) : buildPageSource(novel.content, currentPage);
    await streamTranslateApi(
      settings.backendUrl,
      settings.apiKey,
      sourceText,
      targetLang,
      selectedPresets,
      customPrompt,
      controller,
      async (token) => {
        await notifyTab(tabId, { type: 'SSE_TOKEN', token });
      },
      async (result) => {
        await notifyTab(tabId, { type: 'SSE_DONE', data: result });
      },
      async (error) => {
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

// Build the request text for a possibly paged novel.
// Pixiv marks page breaks with [newpage]; each page is translated
// independently in inline mode because the DOM only renders the page
// the user is currently reading. The full text stays as context: the
// page before and after the target page are included as "reference
// only" sections, and the prompt tells the model not to translate them.
function buildPageSource(fullText, currentPage) {
  if (!currentPage || currentPage <= 0) return fullText;

  const pages = fullText
    .split(/\[newpage\]/i)
    .map((p) => p.trim())
    .filter((p) => p.length > 0);

  if (pages.length <= 1) return fullText; // not actually paged

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
  const numbered = current
    .split(/\n{2,}/)
    .map((p) => p.trim())
    .filter((p) => p.length > 0)
    .map((p, i) => `[${i + 1}] ${p}`)
    .join('\n\n');
  text += `【当前页（第 ${currentPage} 页，请翻译这部分）】\n${numbered}`;
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
  const cookie = await chrome.cookies.get({
    url: 'https://www.pixiv.net',
    name: 'PHPSESSID'
  });

  if (!cookie) {
    console.error('[PNT] STEP1 FAIL: PHPSESSID cookie not found');
    throw new Error('未登录 Pixiv，请先登录 pixiv.net');
  }

  console.log('[PNT] STEP1 fetch pixiv api...');
  // NOTE: do NOT set Cookie / User-Agent / Referer headers manually —
  // they are forbidden headers in browser fetch; Edge rejects such
  // cross-origin requests with TypeError "Failed to fetch". The Pixiv
  // AJAX API works without them (verified: bare fetch returns 200).
  // PHPSESSID auth is handled by Chrome automatically via host
  // permission + credentials when needed.
  const response = await fetch(`https://www.pixiv.net/ajax/novel/${novelId}`, {
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
    chrome.storage.sync.get(['backendUrl', 'apiKey'], (items) => {
      resolve({
        backendUrl: items.backendUrl || '',
        apiKey: items.apiKey || ''
      });
    });
  });
}

// ─── Step 3: Call Backend SSE Stream API ─────────────────────

async function streamTranslateApi(backendUrl, apiKey, text, targetLang, selectedPresets, customPrompt, controller, onToken, onDone, onError) {
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

  // Paged-novel request built by buildPageSource(): the text contains
  // reference-only context sections plus the target page. Tell the model
  // to translate only the marked page and never echo context. Full-novel
  // requests (buildFullSource) carry the same [编号] structure.
  if (text.includes('【当前页') || text.includes('【全文翻译')) {
    prompt += `

【参考上下文】部分仅用于理解情节，不要翻译。请只翻译标记了 [编号] 的段落，逐段输出 JSON Lines：每行一个 JSON 对象 {"id":段落编号,"text":"译文"}，id 与输入编号一一对应、顺序不变、不得合并或遗漏，不要输出其他任何内容。`;
  }


  console.log('[PNT] STEP3 fetch backend:', url);
  // Full-novel requests can take minutes to pre-fill (huge prompt), so a
  // fixed total timeout would kill legitimate translations. Instead wait
  // up to 5 min for the FIRST token, then clear the timer — after that
  // the stream runs without an artificial cap (the backend enforces its
  // own async timeout). User cancellation still wins via AbortSignal.any
  // (Chrome/Edge 116+).
  const firstTokenTimeoutMs = 300000;
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
      model: 'deepseek-v4-flash',
      customPrompt: prompt,
      presets: (selectedPresets && selectedPresets.length > 0) ? selectedPresets : undefined
    }),
    signal
  }).catch((e) => {
    if (e && e.name === 'AbortError' && controller.signal.aborted) {
      throw new Error('翻译已取消');
    }
    if (e && (e.name === 'TimeoutError'
        || (e.name === 'AbortError' && firstTokenController.signal.aborted))) {
      throw new Error('AI 响应超时（5 分钟未收到首个 token），请重试');
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

  while (true) {
    const { done, value } = await reader.read();
    if (done) break;

    buffer += decoder.decode(value, { stream: true });
    const lines = buffer.split('\n');
    buffer = lines.pop() || '';

    for (const line of lines) {
      const trimmed = line.trim();
      if (!trimmed.startsWith('data:')) continue;

      const data = trimmed.substring(5).trim();
      if (!data) continue;

      try {
        const event = JSON.parse(data);
        if (event.token) {
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
      } catch (parseErr) {
        console.warn('[PixivTranslator] Bad SSE chunk:', data);
      }
    }
  }

  // The body stream ended without the backend sending done/error (proxy
  // cut, backend crash): the content script would otherwise stay stuck
  // in "translating" forever. Surface it explicitly.
  if (!streamEnded) {
    await onError('翻译流意外中断，请重试');
  }
}
