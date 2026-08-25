// ============================================================
// Pixiv Novel Translator — Popup Script
// ============================================================

document.addEventListener('DOMContentLoaded', () => {
  const backendUrlInput = document.getElementById('backendUrl');
  const apiKeyInput = document.getElementById('apiKey');
  const modelSelect = document.getElementById('model');
  const modelStatus = document.getElementById('modelStatus');
  const targetLangSelect = document.getElementById('targetLang');
  const thinkingCheck = document.getElementById('thinking');
  const displayModeSelect = document.getElementById('displayMode');
  const settingsToggle = document.getElementById('settingsToggle');
  const settingsPanel = document.getElementById('settingsPanel');
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
  let savedModel = '';
  let savedModelProfileId = '';
  const defaultModels = ['deepseek-v4-flash', 'deepseek-v4-pro', 'deepseek-v4-flash-vision-exp'];

  function backendPermissionOrigin(backendUrl) {
    try {
      const url = new URL(backendUrl);
      if (url.protocol !== 'http:' && url.protocol !== 'https:') return null;
      if (url.hostname === 'localhost' || url.hostname === '127.0.0.1') return null;
      return url.origin + '/*';
    } catch {
      return null;
    }
  }

  async function ensureBackendHostPermission(backendUrl, showPrompt) {
    const origin = backendPermissionOrigin(backendUrl);
    if (!origin) return true;

    const permissions = { origins: [origin] };
    const alreadyGranted = await chrome.permissions.contains(permissions);
    if (alreadyGranted) return true;
    if (!showPrompt) return false;

    const granted = await chrome.permissions.request(permissions);
    if (!granted) {
      showStatus('请允许插件访问后端地址后再试', 'err');
    }
    return granted;
  }

  // ─── Check current tab on open ──────────────────────────

  chrome.tabs.query({ active: true, currentWindow: true }, ([tab]) => {
    const isNovelPage = !!tab?.url && tab.url.includes('pixiv.net/novel/');
    pageStatus.textContent = isNovelPage ? '当前在 Pixiv 小说页面' : '请在 Pixiv 小说页面使用';
    pageStatus.className = isNovelPage ? 'status-ok' : 'status-err';
    translateBtn.disabled = !isNovelPage;
  });

  // ─── Load saved settings ─────────────────────────────────

  chrome.storage.sync.get(
    ['backendUrl', 'apiKey', 'model', 'modelProfileId', 'targetLang', 'thinkingType', 'displayMode', 'autoTranslate', 'selectedPresets', 'customPrompt'],
    (items) => {
      if (items.backendUrl) backendUrlInput.value = items.backendUrl;
      if (items.apiKey) apiKeyInput.value = items.apiKey;
      savedModel = items.model || '';
      savedModelProfileId = items.modelProfileId ? 'profile:' + String(items.modelProfileId) : (items.model ? 'site:' + items.model : '');
      if (items.targetLang) targetLangSelect.value = items.targetLang;
      // 思考：复选框，勾选 = 开启思考推理（默认关闭，与后端 disabled 一致）
      thinkingCheck.checked = items.thinkingType === 'enabled';
      if (items.displayMode) displayModeSelect.value = items.displayMode;
      if (items.customPrompt) customPromptInput.value = items.customPrompt;
      if (Array.isArray(items.selectedPresets)) selectedPresets = items.selectedPresets;
      autoTranslateCheckbox.checked = items.autoTranslate !== false;

      // 已配置过：后端地址 / API Key / 目标语言收进“设置”；首次配置直接展示
      const isConfigured = !!(items.backendUrl && items.apiKey);
      if (isConfigured) {
        settingsToggle.style.display = '';
        settingsPanel.style.display = 'none';
      } else {
        settingsToggle.style.display = 'none';
        settingsPanel.style.display = 'block';
      }

      renderModelOptions(null);
      loadUserModel();
      loadPresets();
    }
  );

  settingsToggle.addEventListener('click', () => {
    const hidden = settingsPanel.style.display === 'none';
    settingsPanel.style.display = hidden ? 'block' : 'none';
  });

  function renderModelOptions(profiles) {
    const profileList = Array.isArray(profiles) ? profiles : [];
    const models = [
      ...defaultModels.map(model => ({ key: 'site:' + model, profileId: '0', model, label: '站方/DeepSeek/' + model })),
      ...profileList.map(profile => ({
        key: 'profile:' + String(profile.id),
        profileId: String(profile.id),
        model: profile.model,
        label: profile.name + '/' + profile.provider + '/' + profile.model
      }))
    ];
    modelSelect.innerHTML = '';
    models.forEach((item) => {
      const option = document.createElement('option');
      option.value = item.key;
      option.dataset.model = item.model;
      option.dataset.profileId = item.profileId;
      option.textContent = item.label;
      modelSelect.appendChild(option);
    });
    const selected = models.find(item => item.key === savedModelProfileId)
      || models.find(item => !savedModelProfileId && item.model === savedModel)
      || models[0];
    if (selected) {
      modelSelect.value = selected.key;
      savedModel = selected.model;
      savedModelProfileId = selected.key;
    }
    if (modelStatus) {
      modelStatus.textContent = profileList.length
        ? '已加载个人页面配置的模型，可在此选择'
        : '';
    }
  }

  async function loadUserModel() {
    const backendUrl = backendUrlInput.value.trim();
    const apiKey = apiKeyInput.value.trim();
    if (!backendUrl || !apiKey) {
      renderModelOptions(null);
      return;
    }
    try {
      const response = await fetch(backendUrl.replace(/\/+$/, '') + '/api/v1/auth/model-profiles', {
        headers: { 'X-API-Key': apiKey },
        signal: AbortSignal.timeout(10000)
      });
      if (!response.ok) throw new Error('model profiles ' + response.status);
      renderModelOptions(await response.json());
    } catch (e) {
      renderModelOptions(null);
      if (modelStatus) modelStatus.textContent = '无法读取个人模型配置';
    }
  }

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
      model: modelSelect.options[modelSelect.selectedIndex]?.dataset?.model || savedModel,
      modelProfileId: modelSelect.options[modelSelect.selectedIndex]?.dataset?.profileId || '0',
      targetLang: targetLangSelect.value,
      thinkingType: thinkingCheck.checked ? 'enabled' : 'disabled',
      displayMode: displayModeSelect.value,
      autoTranslate: autoTranslateCheckbox.checked,
      customPrompt: customPromptInput.value.trim(),
      selectedPresets
    };
    return new Promise((resolve) => {
      chrome.storage.sync.set(data, () => {
        if (showToast) showStatus('设置已保存', 'ok');
        resolve();
      });
    });
  }

  saveBtn.addEventListener('click', async () => {
    if (await ensureBackendHostPermission(backendUrlInput.value.trim(), true)) {
      saveSettings(true);
    }
  });

  // ─── Submit error log ─────────────────────────────────────
  // Send the most recent recorded extension errors to the server's log
  // page (POST /api/v1/plugin/logs, X-API-Key auth). The submitter is
  // resolved server-side from the API key; we only attach version + the
  // error text gathered by background.js.

  const submitLogBtn = document.getElementById('submitLogBtn');
  const logInfo = document.getElementById('logInfo');

  function renderLogPreview() {
    chrome.storage.local.get('pntErrorLog', (items) => {
      const log = Array.isArray(items.pntErrorLog) ? items.pntErrorLog : [];
      if (logInfo) {
        const latestMessage = log[log.length - 1]?.message || '';
        logInfo.textContent = log.length
          ? '最近 ' + log.length + ' 条错误' + (latestMessage ? '：' + String(latestMessage).slice(0, 60) : '')
          : '暂无记录的错误';
      }
    });
  }

  renderLogPreview();

  submitLogBtn.addEventListener('click', async () => {
    const backendUrl = backendUrlInput.value.trim();
    const apiKey = apiKeyInput.value.trim();
    if (!backendUrl || !apiKey) {
      showStatus('请先在设置中填写后端地址和 API Key', 'err');
      return;
    }
    const items = await new Promise((resolve) => {
      chrome.storage.local.get('pntErrorLog', resolve);
    });
    const log = Array.isArray(items.pntErrorLog) ? items.pntErrorLog : [];
    if (!log.length) {
      showStatus('当前没有可提交的错误记录', 'err');
      return;
    }
    const version = typeof EXTENSION_VERSION !== 'undefined' ? String(EXTENSION_VERSION) : '';
    const fullErrorMessage = log
      .map((e) => '[' + (e.time || '') + '] ' + (e.message || ''))
      .join('\n');
    const errorMessage = fullErrorMessage.length > 4000
      ? '[日志过长，仅保留最近内容]\n' + fullErrorMessage.slice(-3970)
      : fullErrorMessage;

    submitLogBtn.disabled = true;
    submitLogBtn.textContent = '提交中...';
    try {
      const response = await fetch(backendUrl.replace(/\/+$/, '') + '/api/v1/plugin/logs', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json', 'X-API-Key': apiKey },
        body: JSON.stringify({ version, errorMessage }),
        signal: AbortSignal.timeout(15000)
      });
      if (!response.ok) {
        const text = await response.text().catch(() => '');
        throw new Error('HTTP ' + response.status + (text ? ': ' + text.slice(0, 200) : ''));
      }
      // Submitted successfully: clear so the next report is fresh.
      chrome.storage.local.set({ pntErrorLog: [] });
      renderLogPreview();
      showStatus('错误日志已提交，感谢反馈！', 'ok');
    } catch (e) {
      showStatus('提交失败: ' + (e && e.message ? e.message : '网络错误'), 'err');
    } finally {
      submitLogBtn.disabled = false;
      submitLogBtn.textContent = '提交错误日志';
    }
  });

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

  [backendUrlInput, apiKeyInput, targetLangSelect, thinkingCheck, displayModeSelect, autoTranslateCheckbox, customPromptInput].forEach(el => {
    el.addEventListener('change', () => saveSettings(false));
  });
  modelSelect.addEventListener('change', () => {
    const selected = modelSelect.options[modelSelect.selectedIndex];
    savedModelProfileId = modelSelect.value;
    savedModel = selected?.dataset?.model || modelSelect.value;
    saveSettings(false);
  });

  backendUrlInput.addEventListener('change', () => {
    loadUserModel();
    loadPresets();
  });
  apiKeyInput.addEventListener('change', loadUserModel);

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
  // Both the primary and the "retranslate" buttons trigger the same
  // flow: the content script starts a fresh translation (cancelling any
  // in-flight one), so re-running after a finished translation simply
  // retranslates.

  async function startTranslate() {
    if (!await ensureBackendHostPermission(backendUrlInput.value.trim(), true)) {
      return;
    }
    await saveSettings(false);

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
  }

  translateBtn.addEventListener('click', startTranslate);
  const retranslateBtn = document.getElementById('retranslateBtn');
  retranslateBtn.addEventListener('click', startTranslate);

  // ─── Status helper ───────────────────────────────────────

  function showStatus(message, type) {
    statusDiv.textContent = message;
    statusDiv.className = 'status ' + (type === 'ok' ? 'status-ok' : 'status-err');
    statusDiv.style.display = 'block';
    setTimeout(() => { statusDiv.style.display = 'none'; }, 3000);
  }

  // ─── Automatic update status ──────────────────────────────
  // The service worker checks GitHub every six hours and on browser
  // startup. This popup reads the cached result and keeps a manual check
  // available for users who want an immediate answer.

  const updateBtn = document.getElementById('updateBtn');
  const updateStatus = document.getElementById('updateStatus');

  chrome.runtime.sendMessage({ type: 'GET_UPDATE_STATUS' }, (response) => {
    if (chrome.runtime.lastError || !response?.update) return;
    renderUpdateResult(response.update);
  });

  updateBtn.addEventListener('click', () => {
    updateBtn.disabled = true;
    updateBtn.textContent = '检查中...';
    chrome.runtime.sendMessage({ type: 'CHECK_FOR_UPDATES' }, (response) => {
      if (chrome.runtime.lastError || !response?.update) {
        renderUpdateStatus('检查更新失败（无法连接更新服务器）', 'err');
      } else {
        renderUpdateResult(response.update);
      }
      updateBtn.disabled = false;
      updateBtn.textContent = '检查更新';
    });
  });

  function renderUpdateResult(update) {
    const current = update.currentVersion || '0.0.0';
    if (update.available && update.latestVersion) {
      renderUpdateStatus(
        '发现新版本 ' + update.latestVersion + '（当前 ' + current + '）→ 点击下载',
        'new',
        update.releaseUrl
      );
    } else if (update.error) {
      renderUpdateStatus('检查更新失败（无法连接更新服务器）', 'err');
    } else {
      renderUpdateStatus('已是最新版本（' + current + '）', 'ok');
    }
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
