// ============================================================
// Pixiv Novel Translator — Background Service Worker
// ============================================================

// ─── Message Handler ─────────────────────────────────────────

chrome.runtime.onMessage.addListener((message, sender, sendResponse) => {
  (async () => {
    try {
      if (message.type === 'TRANSLATE_NOVEL') {
        const { novelId, targetLang } = message;
        const result = await translateNovel(novelId, targetLang || 'zh');
        sendResponse({ success: true, data: result });
      }
    } catch (error) {
      console.error('[PixivTranslator] Error:', error.message);
      sendResponse({ success: false, error: error.message });
    }
  })();
  return true; // async response
});

// ─── Main Flow: Fetch from Pixiv → Translate via Backend ────

async function translateNovel(novelId, targetLang) {
  // Step 1: fetch novel from Pixiv API
  const novel = await fetchNovelFromPixiv(novelId);

  // Step 2: load plugin settings
  const settings = await loadSettings();

  // Step 3: translate via backend
  const translation = await callTranslateApi(
    settings.backendUrl,
    settings.apiKey,
    novel.content,
    targetLang
  );

  return {
    id: novel.id,
    title: novel.title,
    originalContent: novel.content,
    translatedContent: translation,
    author: novel.userName,
    tags: novel.tags || [],
    characterCount: novel.characterCount
  };
}

// ─── Step 1: Fetch Pixiv Novel ───────────────────────────────

async function fetchNovelFromPixiv(novelId) {
  const cookie = await chrome.cookies.get({
    url: 'https://www.pixiv.net',
    name: 'PHPSESSID'
  });

  if (!cookie) {
    throw new Error('未登录 Pixiv，请先登录 pixiv.net');
  }

  const response = await fetch(`https://www.pixiv.net/ajax/novel/${novelId}`, {
    headers: {
      'Cookie': `PHPSESSID=${cookie.value}`,
      'User-Agent': 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36',
      'Referer': 'https://www.pixiv.net/'
    }
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

// ─── Step 3: Call Backend Translate API ──────────────────────

async function callTranslateApi(backendUrl, apiKey, text, targetLang) {
  if (!backendUrl) {
    throw new Error('请先在插件设置中配置后端地址');
  }
  if (!apiKey) {
    throw new Error('请先在插件设置中配置 API Key');
  }

  const url = backendUrl.replace(/\/+$/, '') + '/api/v1/translate';

  const response = await fetch(url, {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
      'X-API-Key': apiKey
    },
    body: JSON.stringify({
      sourceText: text,
      model: 'deepseek-v4-flash',
      customPrompt: `请将以下日语小说内容翻译为${targetLang === 'zh' ? '简体中文' : targetLang === 'en' ? '英语' : targetLang === 'ko' ? '韩语' : '简体中文'}。保留原文的段落结构和换行。`
    })
  });

  if (!response.ok) {
    const body = await response.text().catch(() => '');
    throw new Error(`翻译服务请求失败 (${response.status}): ${body}`);
  }

  const result = await response.json();
  return result.translatedText;
}
