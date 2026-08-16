// ============================================================
// Pixiv Novel Translator — Popup Script
// ============================================================

document.addEventListener('DOMContentLoaded', () => {
  const backendUrlInput = document.getElementById('backendUrl');
  const apiKeyInput = document.getElementById('apiKey');
  const targetLangSelect = document.getElementById('targetLang');
  const thinkingTypeSelect = document.getElementById('thinkingType');
  const displayModeSelect = document.getElementById('displayMode');
  const autoTranslateCheckbox = document.getElementById('autoTranslate');
  const customPromptInput = document.getElementById('customPrompt');
  const presetsGroup = document.getElementById('presetsGroup');
  const statusDiv = document.getElementById('status');
  const translateBtn = document.getElementById('translateBtn');
  const historyBtn = document.getElementById('historyBtn');
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
    ['backendUrl', 'apiKey', 'targetLang', 'thinkingType', 'displayMode', 'autoTranslate', 'selectedPresets', 'customPrompt'],
    (items) => {
      if (items.backendUrl) backendUrlInput.value = items.backendUrl;
      if (items.apiKey) apiKeyInput.value = items.apiKey;
      if (items.targetLang) targetLangSelect.value = items.targetLang;
      if (items.thinkingType) thinkingTypeSelect.value = items.thinkingType;
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
        headers: { 'X-API-Key': apiKey },
        signal: AbortSignal.timeout(10000)
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
      thinkingType: thinkingTypeSelect.value,
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

  // Open the web history page where every finished translation is stored.
  historyBtn.addEventListener('click', () => {
    const backendUrl = backendUrlInput.value.trim();
    if (!backendUrl) {
      showStatus('请先填写后端地址', 'err');
      return;
    }
    chrome.tabs.create({ url: backendUrl.replace(/\/+$/, '') + '/history' });
    window.close();
  });

  [backendUrlInput, apiKeyInput, targetLangSelect, thinkingTypeSelect, displayModeSelect, autoTranslateCheckbox, customPromptInput].forEach(el => {
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
          // version.js first: content.js reads EXTENSION_VERSION for the
          // badge, matching the manifest-declared script order.
          files: ['version.js', 'content.js']
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

  // ─── Check for updates (GitHub Releases) ─────────────────
  // Not a store extension, so the browser cannot self-update it.
  // Instead we compare against the latest GitHub release and guide
  // the user to download it (or run install.cmd on Windows).

  const UPDATE_REPO = 'SparkofSpike/Sh1Zuku_Translate';
  const updateBtn = document.getElementById('updateBtn');
  const updateStatus = document.getElementById('updateStatus');

  updateBtn.addEventListener('click', checkForUpdates);

  async function checkForUpdates() {
    const cur = typeof EXTENSION_VERSION !== 'undefined' ? EXTENSION_VERSION : '0.0.0';
    const base = String(cur).split('+')[0]; // "1.1.0+c8369a1" -> "1.1.0"
    updateBtn.disabled = true;
    updateBtn.textContent = '检查中...';
    try {
      const res = await fetch('https://api.github.com/repos/' + UPDATE_REPO + '/releases/latest', {
        signal: AbortSignal.timeout(10000)
      });
      if (!res.ok) throw new Error('HTTP ' + res.status);
      const rel = await res.json();
      const latest = String(rel.tag_name || '').replace(/^v/, '');
      if (latest && compareVersions(latest, base) > 0) {
        renderUpdateStatus(
          '发现新版本 ' + latest + '（当前 ' + base + '）→ 点击下载',
          'new',
          rel.html_url || ('https://github.com/' + UPDATE_REPO + '/releases/latest')
        );
      } else {
        renderUpdateStatus('已是最新版本（' + base + '）', 'ok');
      }
    } catch (e) {
      renderUpdateStatus('检查更新失败（无法连接更新服务器）', 'err');
    } finally {
      updateBtn.disabled = false;
      updateBtn.textContent = '检查更新';
    }
  }

  function compareVersions(a, b) {
    const pa = String(a).split('.').map(Number);
    const pb = String(b).split('.').map(Number);
    for (let i = 0; i < Math.max(pa.length, pb.length); i++) {
      const x = pa[i] || 0;
      const y = pb[i] || 0;
      if (x !== y) return x - y;
    }
    return 0;
  }

  function renderUpdateStatus(message, type, url) {
    updateStatus.innerHTML = '';
    const span = document.createElement('span');
    span.className = type;
    span.textContent = message;
    if (url) {
      span.style.cursor = 'pointer';
      span.style.textDecoration = 'underline';
      span.addEventListener('click', () => {
        chrome.tabs.create({ url });
        window.close();
      });
    }
    updateStatus.appendChild(span);
  }
});
