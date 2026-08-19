<template>
  <div class="profile-grid">
    <section class="card">
      <h2 class="page-title">个人</h2>
      <div class="account-summary">
        <p><strong>用户名：</strong>{{ profile.username }}</p>
        <p><strong>邮箱：</strong>{{ profile.email }}</p>
        <p><strong>注册时间：</strong>{{ profile.createdAt || '未知' }}</p>
      </div>

      <div class="usage-highlight">
        <span class="usage-label">累计 Token 用量</span>
        <strong>{{ formatNumber(usage.totalTokens) }}</strong>
        <span class="usage-meta">输入 {{ formatNumber(usage.promptTokens) }} · 输出 {{ formatNumber(usage.completionTokens) }} · {{ usage.requestCount }} 次调用</span>
      </div>

      <hr />

      <h3>模型配置</h3>
      <p class="hint">可配置 DeepSeek、OpenAI 兼容或 Anthropic 兼容的模型。API Key 仅用于请求，不会回显。</p>
      <label class="field-label">协议</label>
      <select v-model="modelConfig.provider">
        <option value="deepseek">DeepSeek</option>
        <option value="openai">OpenAI 兼容</option>
        <option value="anthropic">Anthropic 兼容</option>
      </select>

      <label class="field-label">模型名称</label>
      <input v-model.trim="modelConfig.model" type="text" placeholder="例如 gpt-4o-mini 或 claude-3-5-sonnet" />

      <label class="field-label">Base URL</label>
      <input v-model.trim="modelConfig.baseUrl" type="url" :placeholder="baseUrlPlaceholder" />
      <p class="field-hint">填写 API 根地址，不要包含末尾的具体接口路径（如 /chat/completions 或 /messages）。</p>

      <label class="field-label">API Key</label>
      <input v-model.trim="apiKey" type="password" placeholder="留空表示保持当前 Key" />
      <p v-if="profile.hasAiApiKey" class="configured">已配置个人 API Key</p>

      <div class="actions">
        <button @click="saveModel" :disabled="saving">{{ saving ? '保存中...' : '保存模型配置' }}</button>
        <button v-if="profile.hasAiApiKey" class="btn-sm btn-remove" @click="clearAiKey">清除个人 Key</button>
      </div>
      <p v-if="message" class="success">{{ message }}</p>
      <p v-if="error" class="error">{{ error }}</p>
    </section>

    <section class="card plugin-card">
      <h3 class="section-title">插件 API Key</h3>
      <p class="hint">用于浏览器插件调用翻译接口，生成后请妥善保存（只显示一次）。</p>
      <button @click="createPluginKey">生成插件 Key</button>
      <div v-if="newKey" class="new-key">
        <code>{{ newKey }}</code>
        <button class="btn-sm btn-primary" @click="copyKey">复制</button>
      </div>
      <div v-if="keys.length" class="key-list">
        <div v-for="key in keys" :key="key.id" class="key-row">
          <span>{{ key.name }}</span>
          <span class="muted">{{ key.keyPrefix }}</span>
          <button class="btn-sm btn-remove" @click="deleteKey(key.id)">删除</button>
        </div>
      </div>
    </section>
  </div>
</template>

<script setup>
import { computed, onMounted, ref } from 'vue'
import api from '../api'

const profile = ref({ username: '', email: '', hasAiApiKey: false, createdAt: '', provider: 'deepseek', baseUrl: '', model: '' })
const modelConfig = ref({ provider: 'deepseek', baseUrl: '', model: '' })
const usage = ref({ promptTokens: 0, completionTokens: 0, totalTokens: 0, requestCount: 0 })
const apiKey = ref('')
const message = ref('')
const error = ref('')
const saving = ref(false)
const newKey = ref('')
const keys = ref([])

const baseUrlPlaceholder = computed(() => modelConfig.value.provider === 'anthropic'
  ? 'https://api.anthropic.com/v1'
  : modelConfig.value.provider === 'openai'
    ? 'https://api.openai.com/v1'
    : '留空使用站方 DeepSeek 地址')

async function loadProfile() {
  try {
    const res = await api.get('/auth/profile')
    profile.value = res.data
    modelConfig.value = {
      provider: res.data.provider || 'deepseek',
      baseUrl: res.data.baseUrl || '',
      model: res.data.model || ''
    }
  } catch (e) {
    error.value = e.response?.data?.error || '加载个人资料失败'
  }
}

async function loadUsage() {
  try {
    const res = await api.get('/auth/usage')
    usage.value = res.data
  } catch (e) {
    error.value = e.response?.data?.error || '加载用量失败'
  }
}

async function saveModel() {
  saving.value = true
  message.value = ''
  error.value = ''
  try {
    await api.put('/auth/profile/model', {
      ...modelConfig.value,
      ...(apiKey.value ? { apiKey: apiKey.value } : {})
    })
    apiKey.value = ''
    message.value = '模型配置已保存'
    await loadProfile()
  } catch (e) {
    error.value = e.response?.data?.error || '保存模型配置失败'
  } finally {
    saving.value = false
  }
}

async function clearAiKey() {
  try {
    await api.put('/auth/profile/ai-key', { aiApiKey: '' })
    message.value = '个人 API Key 已清除，将使用站方 DeepSeek 配置'
    await loadProfile()
  } catch (e) {
    error.value = e.response?.data?.error || '清除失败'
  }
}

async function createPluginKey() {
  try {
    const res = await api.post('/auth/api-key', { name: 'pixiv-plugin' })
    newKey.value = res.data.keyValue
    await loadKeys()
  } catch (e) {
    error.value = e.response?.data?.error || '生成失败'
  }
}

async function copyKey() {
  try {
    await navigator.clipboard.writeText(newKey.value)
    message.value = '已复制'
  } catch (e) {
    error.value = '复制失败，请手动选择复制'
  }
}

async function loadKeys() {
  try {
    const res = await api.get('/auth/api-keys')
    keys.value = res.data.apiKeys || []
  } catch (e) {
    error.value = e.response?.data?.error || '加载插件 Key 失败'
  }
}

async function deleteKey(id) {
  try {
    await api.delete('/auth/api-key/' + id)
    await loadKeys()
  } catch (e) {
    error.value = e.response?.data?.error || '删除失败'
  }
}

function formatNumber(value) {
  return Number(value || 0).toLocaleString()
}

onMounted(() => {
  loadProfile()
  loadUsage()
  loadKeys()
})
</script>

<style scoped>
.profile-grid {
  display: grid;
  grid-template-columns: minmax(0, 1.4fr) minmax(260px, .6fr);
  gap: 16px;
  max-width: 1000px;
  margin: 0 auto;
}

.card { max-width: none; margin: 0; }
.page-title { margin-top: 0; font-weight: 600; }
.account-summary p { margin: 0 0 4px; }
.section-title { margin-top: 0; }
.hint, .field-hint { color: #777; font-size: 14px; }
.field-label { display: block; margin: 12px 0 6px; font-size: 14px; font-weight: 600; }
.field-hint { margin: 5px 0 0; font-size: 12px; }
.usage-highlight { display: flex; flex-direction: column; gap: 2px; margin-top: 20px; padding: 16px; background: #f5f5f5; border: 1px solid #e6e6e6; border-radius: 8px; }
.usage-highlight strong { font-size: 30px; letter-spacing: -.5px; }
.usage-label, .usage-meta, .muted { color: #777; font-size: 13px; }
.usage-meta { font-size: 12px; }
.actions { display: flex; flex-wrap: wrap; gap: 10px; margin-top: 16px; }
.configured, .success { color: #2b8a3e; font-size: 14px; }
.error { color: #e03131; font-size: 14px; }
.new-key { display: flex; align-items: center; gap: 8px; margin-top: 12px; }
.new-key code { flex: 1; padding: 8px 12px; overflow-wrap: anywhere; background: #f5f5f5; border-radius: 6px; }
.key-list { margin-top: 18px; }
.key-row { display: flex; align-items: center; gap: 10px; padding: 9px 0; border-bottom: 1px solid #eee; }
.key-row span:first-child { flex: 1; }

@media (max-width: 720px) {
  .profile-grid { grid-template-columns: 1fr; }
}
</style>
