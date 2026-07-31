// ============================================================
// Pixiv Novel Translator — Popup Script
// ============================================================

document.addEventListener('DOMContentLoaded', () => {
  const backendUrlInput = document.getElementById('backendUrl');
  const apiKeyInput = document.getElementById('apiKey');
  const targetLangSelect = document.getElementById('targetLang');
  const displayModeSelect = document.getElementById('displayMode');
  const autoTranslateCheckbox = document.getElementById('autoTranslate');
  const customPromptInput = document.getElementById('customPrompt');
  const presetsGroup = document.getElementById('presetsGroup');
  const statusDiv = document.getElementById('status');
  const translateBtn = document.getElementById('translateBtn');
  const saveBtn = document.getElementById('saveBtn');
  const pageStatus = document.getElementById('pageStatus');

  // ─── Show version badge ──────────────────────────────────

  const verEl = document.getElementById('extVersion');
  if (verEl) {
    verEl.textContent = typeof EXTENSION_VERSION !== 'undefined'
      ? '版本 ' + EXTENSION_VERSION
      : '';
  }

  let selectedPresets = [];

  // ─── Check current tab on open ──────────────────────────

  chrome.tabs.query({ active: true, currentWindow: true }, ([tab]) => {
    if (tab && tab.url && tab.url.includes('pixiv.net')) {
      pageStatus.textContent = '当前在 Pixiv 页面';
      pageStatus.className = 'status-ok';
      translateBtn.disabled = false;
    } else {
      pageStatus.textContent = tab && tab.url && tab.url.includes('pixiv.net')
        ? '请在小说页面使用'
        : '请打开 Pixiv 页面';
      pageStatus.className = 'status-err';
      translateBtn.disabled = true;
    }
  });

  // ─── Load saved settings ─────────────────────────────────

  chrome.storage.sync.get(
    ['backendUrl', 'apiKey', 'targetLang', 'displayMode', 'autoTranslate', 'selectedPresets', 'customPrompt'],
    (items) => {
      if (items.backendUrl) backendUrlInput.value = items.backendUrl;
      if (items.apiKey) apiKeyInput.value = items.apiKey;
      if (items.targetLang) targetLangSelect.value = items.targetLang;
      if (items.displayMode) displayModeSelect.value = items.displayMode;
      if (items.customPrompt) customPromptInput.value = items.customPrompt;
      if (Array.isArray(items.selectedPresets)) selectedPresets = items.selectedPresets;
      autoTranslateCheckbox.checked = items.autoTranslate !== false;

      loadPresets();
    }
  );

  // ─── Fetch presets from backend ─────────────────────────

  async function loadPresets() {
    const backendUrl = backendUrlInput.value.trim();
    const apiKey = apiKeyInput.value.trim();
    if (!backendUrl) {
      presetsGroup.innerHTML = '<span style="color:#999;">请先填写后端地址</span>';
      return;
    }
    try {
      const response = await fetch(backendUrl.replace(/\/+$/, '') + '/api/v1/presets', {
        headers: { 'X-API-Key': apiKey }
      });
      if (!response.ok) {
        presetsGroup.innerHTML = '<span style="color:#c5221f;">预设加载失败</span>';
        return;
      }
      const presets = await response.json();
      renderPresets(presets);
    } catch (e) {
      presetsGroup.innerHTML = '<span style="color:#c5221f;">无法连接后端</span>';
    }
  }

  function renderPresets(presets) {
    if (!presets || presets.length === 0) {
      presetsGroup.innerHTML = '<span style="color:#999;">暂无预设</span>';
      return;
    }
    presetsGroup.innerHTML = '';
    presets.forEach((preset) => {
      const label = document.createElement('label');
      label.className = 'preset-item';

      const checkbox = document.createElement('input');
      checkbox.type = 'checkbox';
      checkbox.value = preset;
      checkbox.checked = selectedPresets.includes(preset);
      checkbox.addEventListener('change', () => {
        if (checkbox.checked) {
          if (!selectedPresets.includes(preset)) selectedPresets.push(preset);
        } else {
          selectedPresets = selectedPresets.filter(p => p !== preset);
        }
        saveSettings(false);
      });

      const span = document.createElement('span');
      span.textContent = preset;

      label.appendChild(checkbox);
      label.appendChild(span);
      presetsGroup.appendChild(label);
    });
  }

  // ─── Save settings ───────────────────────────────────────

  function saveSettings(showToast = true) {
    const data = {
      backendUrl: backendUrlInput.value.trim(),
      apiKey: apiKeyInput.value.trim(),
      targetLang: targetLangSelect.value,
      displayMode: displayModeSelect.value,
      autoTranslate: autoTranslateCheckbox.checked,
      customPrompt: customPromptInput.value.trim(),
      selectedPresets
    };
    chrome.storage.sync.set(data, () => {
      if (showToast) showStatus('设置已保存', 'ok');
    });
  }

  saveBtn.addEventListener('click', () => saveSettings(true));

  [backendUrlInput, apiKeyInput, targetLangSelect, displayModeSelect, autoTranslateCheckbox, customPromptInput].forEach(el => {
    el.addEventListener('change', () => saveSettings(false));
  });

  backendUrlInput.addEventListener('change', loadPresets);

  // ─── Inject content script if needed ────────────────────

  async function ensureContentScript(tabId) {
    try {
      await chrome.tabs.sendMessage(tabId, { type: 'PING' });
      return true;
    } catch {
      try {
        await chrome.scripting.executeScript({
          target: { tabId },
          files: ['content.js']
        });
        await chrome.scripting.insertCSS({
          target: { tabId },
          files: ['styles.css']
        });
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
    saveSettings(false);

    const [tab] = await chrome.tabs.query({ active: true, currentWindow: true });
    if (!tab) {
      showStatus('未找到当前标签页', 'err');
      return;
    }

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
