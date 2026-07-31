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
            message.customPrompt || ''
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
      console.error('[PixivTranslator] Error:', error.message);
      sendResponse({ success: false, error: error.message });
    }
  })();
  return true; // async response
});

// ─── Main Flow: Fetch from Pixiv → Stream Translate ─────────

async function startStreamingTranslation(novelId, targetLang, tabId, selectedPresets = [], customPrompt = '') {
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
      characterCount: novel.characterCount
    }
  });

  // Step 2: load settings
  const settings = await loadSettings();

  // Step 3: stream translate via backend
  try {
    await streamTranslateApi(
      settings.backendUrl,
      settings.apiKey,
      novel.content,
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
  const response = await fetch(`https://www.pixiv.net/ajax/novel/${novelId}`, {
    headers: {
      'Cookie': `PHPSESSID=${cookie.value}`,
      'User-Agent': 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36',
      'Referer': 'https://www.pixiv.net/'
    },
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
