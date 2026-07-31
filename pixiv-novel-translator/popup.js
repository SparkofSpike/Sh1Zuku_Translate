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
      showStatus('✅ 设置已保存', 'ok');
    });
  }

  saveBtn.addEventListener('click', saveSettings);

  // Auto-save on field change
  [backendUrlInput, apiKeyInput, targetLangSelect, autoTranslateCheckbox].forEach(el => {
    el.addEventListener('change', saveSettings);
  });

  // ─── Translate current page ──────────────────────────────

  translateBtn.addEventListener('click', () => {
    // Save first
    saveSettings();

    chrome.tabs.query({ active: true, currentWindow: true }, (tabs) => {
      if (!tabs[0]) {
        showStatus('❌ 未找到当前标签页', 'err');
        return;
      }
      chrome.tabs.sendMessage(tabs[0].id, { type: 'MANUAL_TRANSLATE' }, (response) => {
        if (chrome.runtime.lastError) {
          showStatus('⚠️ 请刷新 Pixiv 小说页面后重试', 'err');
        } else {
          window.close(); // close popup after triggering
        }
      });
    });
  });

  // ─── Status helper ───────────────────────────────────────

  function showStatus(message, type) {
    statusDiv.textContent = message;
    statusDiv.className = 'status ' + (type === 'ok' ? 'status-ok' : 'status-err');
    statusDiv.style.display = 'block';
    setTimeout(() => { statusDiv.style.display = 'none'; }, 3000);
  }
});
