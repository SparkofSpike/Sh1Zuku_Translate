// ============================================================
// Pixiv Novel Translator — Background Service Worker
// ============================================================

// ─── Active translation (for cancellation) ───────────────────

const PNT_BG_VERSION = '1.1.0';
console.log('[PNT] background.js v' + PNT_BG_VERSION + ' loaded');
let activeController = null;

// ─── Message Handler ─────────────────────────────────────────

chrome.runtime.onMessage.addListener((message, sender, sendResponse) => {
  (async () => {
    try {
      switch (message.type) {
        case 'TRANSLATE_NOVEL_STREAM':
          // Start streaming translation; tokens pushed via tabs.sendMessage
          await startStreamingTranslation(
            message.novelId,
            message.targetLang || 'zh',
            sender.tab?.id,
            message.selectedPresets || [],
            message.customPrompt || '',
            message.currentPage || 0,
            !!message.fullMode
          );
          sendResponse({ success: true });
          break;

        case 'CANCEL_TRANSLATE':
          if (activeController) {
            activeController.abort();
            activeController = null;
            sendResponse({ success: true });
          } else {
            sendResponse({ success: false, error: '没有进行中的翻译' });
          }
          break;

        case 'PING':
          sendResponse({ pong: true });
          break;

        default:
          sendResponse({ success: false, error: '未知消息类型' });
      }
    } catch (error) {
      const msg = error && error.message ? error.message : String(error);
      // An aborted body stream is almost always the user pressing cancel
      // (Edge reports it as "BodyStreamBuffer was aborted"). Treat it as
      // an expected cancellation, not an error — no scary console.error.
      if (/abort/i.test(msg)) {
        console.warn('[PixivTranslator] Translation cancelled:', msg);
        sendResponse({ success: false, cancelled: true, error: '翻译已取消' });
      } else {
        console.error('[PixivTranslator] Error:', msg);
        sendResponse({ success: false, error: msg });
      }
    }
  })();
  return true; // async response
});

// ─── Main Flow: Fetch from Pixiv → Stream Translate ─────────

async function startStreamingTranslation(novelId, targetLang, tabId, selectedPresets = [], customPrompt = '', currentPage = 0, fullMode = false) {
  // Create abort controller up-front so cancellation works even during
  // the Pixiv fetch (STEP1), not just the backend SSE stream (STEP3).
  const controller = new AbortController();
  activeController = controller;

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
  try {
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
    if (activeController === controller) {
      activeController = null;
    }
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
  // explicitly to translate only the marked page and never echo context.
  // Full-novel requests (buildFullSource) carry the same [编号] structure.
  if (text.includes('【当前页') || text.includes('【全文翻译')) {
    prompt += `\n\n源文本中【参考上下文】部分（如有）仅用于理解情节，一律不要翻译、不要输出、不要复述。请只翻译需要翻译的部分。

所有需要翻译的段落已用 [编号] 标记（[1]、[2]、...）。请逐段翻译，输出为 JSON Lines 格式：每一行是一个独立的 JSON 对象，id 与输入的段落编号一一对应，text 是该段的译文。

{"id":1,"text":"第一段的译文"}
{"id":2,"text":"第二段的译文"}

要求：
1. 每一行必须是一个完整、合法的 JSON 对象，用双引号，不要注释、不要 Markdown 代码块标记、不要任何额外文字；
2. id 必须与输入 [编号] 一一对应，顺序不变，不得合并或遗漏；
3. 译文内部的换行用 \\n 转义写在 text 里，段落之间严格分行；
4. 参考上下文不要翻译、不要出现在输出中；全文模式下必须输出所有编号段落的译文，不得遗漏任何编号。`;
  }


  console.log('[PNT] STEP3 fetch backend:', url);
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
    signal: controller.signal
  }).catch((e) => {
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
          await onToken(event.token);
        } else if (event.done) {
          await onDone({
            translatedText: event.translatedText,
            id: event.id,
            tokenUsage: event.tokenUsage
          });
        } else if (event.error) {
          await onError(event.error);
        }
      } catch (parseErr) {
        console.warn('[PixivTranslator] Bad SSE chunk:', data);
      }
    }
  }
}
