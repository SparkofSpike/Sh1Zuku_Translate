// ============================================================
// Pixiv Novel Translator — Popup Script
// ============================================================

document.addEventListener('DOMContentLoaded', () => {
  const backendUrlInput = document.getElementById('backendUrl');
  const apiKeyInput = document.getElementById('apiKey');
  const targetLangSelect = document.getElementById('targetLang');
  const autoTranslateCheckbox = document.getElementById('autoTranslate');
  const statusDiv = document.getElementById('status');
  const translateBtn = document.getElementById('translateBtn');
  const saveBtn = document.getElementById('saveBtn');
  const pageStatus = document.getElementById('pageStatus');

  // ─── Check current tab on open ──────────────────────────

  chrome.tabs.query({ active: true, currentWindow: true }, ([tab]) => {
    if (tab && tab.url && tab.url.includes('pixiv.net/novel/')) {
      pageStatus.textContent = '当前在小说页面';
      pageStatus.className = 'status-ok';
      translateBtn.disabled = false;
    } else {
      pageStatus.textContent = tab && tab.url && tab.url.includes('pixiv.net')
        ? '请在小说页面使用'
        : '请打开 Pixiv 小说页面';
      pageStatus.className = 'status-err';
      translateBtn.disabled = true;
    }
  });

  // ─── Load saved settings ─────────────────────────────────

  chrome.storage.sync.get(['backendUrl', 'apiKey', 'targetLang', 'autoTranslate'], (items) => {
    if (items.backendUrl) backendUrlInput.value = items.backendUrl;
    if (items.apiKey) apiKeyInput.value = items.apiKey;
    if (items.targetLang) targetLangSelect.value = items.targetLang;
    autoTranslateCheckbox.checked = items.autoTranslate !== false;
  });

  // ─── Save settings ───────────────────────────────────────

  function saveSettings() {
    const data = {
      backendUrl: backendUrlInput.value.trim(),
      apiKey: apiKeyInput.value.trim(),
      targetLang: targetLangSelect.value,
      autoTranslate: autoTranslateCheckbox.checked
    };
    chrome.storage.sync.set(data, () => {
      showStatus('设置已保存', 'ok');
    });
  }

  saveBtn.addEventListener('click', saveSettings);
  [backendUrlInput, apiKeyInput, targetLangSelect, autoTranslateCheckbox].forEach(el => {
    el.addEventListener('change', saveSettings);
  });

  // ─── Inject content script if needed ────────────────────

  async function ensureContentScript(tabId) {
    // Try to ping the content script
    try {
      await chrome.tabs.sendMessage(tabId, { type: 'PING' });
      return true; // already injected
    } catch {
      // Not injected — inject now
      try {
        await chrome.scripting.executeScript({
          target: { tabId },
          files: ['content.js']
        });
        await chrome.scripting.insertCSS({
          target: { tabId },
          files: ['styles.css']
        });
        // Wait for initialization
        await new Promise(r => setTimeout(r, 200));
        return true;
      } catch (e) {
        console.error('Inject failed:', e);
        return false;
      }
    }
  }

  // ─── Translate current page ──────────────────────────────

  translateBtn.addEventListener('click', async () => {
    saveSettings();

    const [tab] = await chrome.tabs.query({ active: true, currentWindow: true });
    if (!tab) {
      showStatus('未找到当前标签页', 'err');
      return;
    }

    // Check URL
    if (!tab.url || !tab.url.includes('pixiv.net/novel/')) {
      showStatus('请在 Pixiv 小说页面使用', 'err');
      return;
    }

    showStatus('正在连接...', 'ok');

    const injected = await ensureContentScript(tab.id);
    if (!injected) {
      showStatus('注入失败，请刷新页面重试', 'err');
      return;
    }

    try {
      await chrome.tabs.sendMessage(tab.id, { type: 'MANUAL_TRANSLATE' });
      window.close();
    } catch (e) {
      showStatus('请刷新 Pixiv 小说页面后重试', 'err');
    }
  });

  // ─── Status helper ───────────────────────────────────────

  function showStatus(message, type) {
    statusDiv.textContent = message;
    statusDiv.className = 'status ' + (type === 'ok' ? 'status-ok' : 'status-err');
    statusDiv.style.display = 'block';
    setTimeout(() => { statusDiv.style.display = 'none'; }, 3000);
  }
});
