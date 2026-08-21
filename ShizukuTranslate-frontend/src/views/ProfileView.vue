<template>
  <div class="profile-grid">
    <section class="card model-card">
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

      <div class="section-heading">
        <div>
          <h3>模型配置</h3>
          <p class="hint">可以保存多条配置，在网页翻译和浏览器插件中分别选择。</p>
        </div>
        <button class="btn-sm btn-primary" @click="startCreate">新增配置</button>
      </div>

      <div class="profile-list">
        <div
          class="profile-row site-profile"
          :class="{ selected: selectedProfileId === null }"
          @click="selectProfile(null)"
        >
          <div class="profile-main">
            <strong>站方 DeepSeek</strong>
            <span class="profile-meta">使用站方 API Key · deepseek-v4-flash</span>
          </div>
          <span v-if="selectedProfileId === null" class="selected-label">当前选择</span>
        </div>

        <div
          v-for="item in modelProfiles"
          :key="item.id"
          class="profile-row"
          :class="{ selected: selectedProfileId === item.id }"
          @click="selectProfile(item.id)"
        >
          <div class="profile-main">
            <strong>{{ item.name }}</strong>
            <span class="profile-meta">{{ providerLabel(item.provider) }} · {{ item.model }}</span>
            <span class="profile-key">{{ item.hasApiKey ? 'API Key：' + item.apiKeyPreview : 'API Key：使用站方 Key' }}</span>
          </div>
          <div class="profile-actions">
            <span v-if="selectedProfileId === item.id" class="selected-label">当前选择</span>
            <button class="btn-sm btn-remove" @click.stop="startEdit(item)">编辑</button>
            <button class="btn-sm btn-remove" @click.stop="deleteProfile(item.id)">删除</button>
          </div>
        </div>
      </div>

      <p v-if="!modelProfiles.length" class="empty-hint">还没有个人模型配置，当前使用站方 DeepSeek。</p>

      <div v-if="formVisible" class="profile-form">
        <h4>{{ editingId ? '编辑模型配置' : '新增模型配置' }}</h4>
        <label class="field-label">配置名称</label>
        <input v-model.trim="form.name" type="text" />

        <label class="field-label">协议</label>
        <select v-model="form.provider" @change="handleProviderChange">
          <option value="deepseek">DeepSeek</option>
          <option value="openai">OpenAI 兼容</option>
          <option value="anthropic">Anthropic 兼容</option>
        </select>

        <label class="field-label">模型名称</label>
        <input v-model.trim="form.model" type="text" />

        <template v-if="form.provider !== 'deepseek'">
          <label class="field-label">Base URL</label>
          <input v-model.trim="form.baseUrl" class="base-url-input" type="url" :placeholder="baseUrlPlaceholder" />
        </template>

        <label class="field-label">API Key</label>
        <input v-model.trim="form.apiKey" type="password" :placeholder="editingId ? '留空保持当前 Key' : 'DeepSeek 可留空使用站方 Key'" />
        <p v-if="editingId && editingProfile?.hasApiKey" class="configured">当前 Key：{{ editingProfile.apiKeyPreview }}</p>

        <div class="actions">
          <button @click="saveProfile" :disabled="saving">{{ saving ? '保存中...' : '保存配置' }}</button>
          <button class="btn-sm btn-remove" @click="cancelForm">取消</button>
          <button v-if="editingId && form.provider === 'deepseek' && editingProfile?.hasApiKey" class="btn-sm btn-remove" @click="clearProfileKey">清除 Key</button>
        </div>
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

const PROFILE_SELECTION_KEY = 'modelProfileId'
const DEFAULT_BASE_URLS = {
  openai: 'https://api.openai.com/v1',
  anthropic: 'https://api.anthropic.com/v1'
}

const profile = ref({ username: '', email: '', createdAt: '' })
const modelProfiles = ref([])
const selectedProfileId = ref(readSelectedProfileId())
const formVisible = ref(false)
const editingId = ref(null)
const editingProfile = ref(null)
const form = ref(emptyForm())
const usage = ref({ promptTokens: 0, completionTokens: 0, totalTokens: 0, requestCount: 0 })
const message = ref('')
const error = ref('')
const saving = ref(false)
const newKey = ref('')
const keys = ref([])

const baseUrlPlaceholder = computed(() => DEFAULT_BASE_URLS[form.value.provider] || '')

function emptyForm() {
  return { name: '', provider: 'deepseek', baseUrl: '', model: 'deepseek-v4-flash', apiKey: '' }
}

function readSelectedProfileId() {
  const value = localStorage.getItem(PROFILE_SELECTION_KEY)
  const id = value ? Number(value) : 0
  return Number.isInteger(id) && id > 0 ? id : null
}

function selectProfile(id) {
  selectedProfileId.value = id
  if (id === null) {
    localStorage.removeItem(PROFILE_SELECTION_KEY)
    localStorage.setItem('modelSelection', 'site:deepseek-v4-flash')
  } else {
    localStorage.setItem(PROFILE_SELECTION_KEY, String(id))
    localStorage.setItem('modelSelection', 'profile:' + id)
  }
  message.value = id === null ? '已选择站方 DeepSeek' : '已选择个人模型配置'
  error.value = ''
}

function providerLabel(provider) {
  return provider === 'anthropic' ? 'Anthropic 兼容' : provider === 'openai' ? 'OpenAI 兼容' : 'DeepSeek'
}

function handleProviderChange() {
  if (form.value.provider === 'deepseek') form.value.baseUrl = ''
  else form.value.baseUrl = DEFAULT_BASE_URLS[form.value.provider] || ''
}

function startCreate() {
  editingId.value = null
  editingProfile.value = null
  form.value = emptyForm()
  formVisible.value = true
  message.value = ''
  error.value = ''
}

function startEdit(item) {
  editingId.value = item.id
  editingProfile.value = item
  form.value = {
    name: item.name,
    provider: item.provider,
    baseUrl: item.baseUrl || DEFAULT_BASE_URLS[item.provider] || '',
    model: item.model,
    apiKey: ''
  }
  formVisible.value = true
  message.value = ''
  error.value = ''
}

function cancelForm() {
  formVisible.value = false
  editingId.value = null
  editingProfile.value = null
}

async function loadProfile() {
  try {
    const res = await api.get('/auth/profile')
    profile.value = res.data
  } catch (e) {
    error.value = e.response?.data?.error || '加载个人资料失败'
  }
}

async function loadModelProfiles() {
  try {
    const res = await api.get('/auth/model-profiles')
    modelProfiles.value = res.data || []
    if (selectedProfileId.value === null && !localStorage.getItem('modelSelection') && modelProfiles.value.length) {
      selectProfile(modelProfiles.value[0].id)
    }
    if (selectedProfileId.value !== null && !modelProfiles.value.some(item => item.id === selectedProfileId.value)) {
      selectProfile(null)
    }
  } catch (e) {
    error.value = e.response?.data?.error || '加载模型配置失败'
  }
}

async function saveProfile() {
  if (!form.value.model) {
    error.value = '请填写模型名称'
    return
  }
  saving.value = true
  message.value = ''
  error.value = ''
  try {
    const body = {
      name: form.value.name,
      provider: form.value.provider,
      baseUrl: form.value.provider === 'deepseek' ? '' : form.value.baseUrl,
      model: form.value.model,
      ...(form.value.apiKey ? { apiKey: form.value.apiKey } : {})
    }
    let res
    if (editingId.value) res = await api.put('/auth/model-profiles/' + editingId.value, body)
    else res = await api.post('/auth/model-profiles', body)
    await loadModelProfiles()
    if (res.data?.id) selectProfile(res.data.id)
    form.value.apiKey = ''
    formVisible.value = false
    message.value = '模型配置已保存'
  } catch (e) {
    error.value = e.response?.data?.error || '保存模型配置失败'
  } finally {
    saving.value = false
  }
}

async function clearProfileKey() {
  if (!editingId.value) return
  try {
    await api.put('/auth/model-profiles/' + editingId.value, {
      name: form.value.name,
      provider: 'deepseek',
      baseUrl: '',
      model: form.value.model,
      clearApiKey: 'true'
    })
    await loadModelProfiles()
    cancelForm()
    message.value = 'Key 已清除，将使用站方 DeepSeek'
  } catch (e) {
    error.value = e.response?.data?.error || '清除 Key 失败'
  }
}

async function deleteProfile(id) {
  if (!window.confirm('确定删除这条模型配置吗？')) return
  try {
    await api.delete('/auth/model-profiles/' + id)
    if (selectedProfileId.value === id) selectProfile(null)
    await loadModelProfiles()
    message.value = '模型配置已删除'
  } catch (e) {
    error.value = e.response?.data?.error || '删除模型配置失败'
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
  loadModelProfiles()
  loadUsage()
  loadKeys()
})
</script>

<style scoped>
.profile-grid { display: grid; grid-template-columns: minmax(0, 1.5fr) minmax(280px, .5fr); gap: 16px; max-width: 1120px; margin: 0 auto; }
.card { max-width: none; margin: 0; }
.model-card { min-width: 0; }
.page-title { margin-top: 0; font-weight: 600; }
.account-summary p { margin: 0 0 4px; }
.section-title { margin-top: 0; }
.hint, .field-hint { color: #777; font-size: 14px; }
.section-heading { display: flex; align-items: flex-start; justify-content: space-between; gap: 12px; }
.section-heading h3 { margin-bottom: 4px; }
.section-heading p { margin-top: 0; }
.profile-list { display: flex; flex-direction: column; gap: 8px; margin-top: 14px; }
.profile-row { display: flex; align-items: center; gap: 12px; padding: 12px 14px; border: 1px solid #e5e5e5; border-radius: 7px; cursor: pointer; }
.profile-row:hover, .profile-row.selected { border-color: #555; background: #fafafa; }
.profile-main { min-width: 0; flex: 1; }
.profile-main strong, .profile-meta, .profile-key { display: block; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.profile-meta, .profile-key { color: #777; font-size: 12px; }
.profile-key { color: #555; margin-top: 2px; }
.profile-actions { display: flex; align-items: center; gap: 6px; flex-shrink: 0; }
.selected-label { color: #333; font-size: 12px; white-space: nowrap; }
.empty-hint { color: #999; font-size: 13px; }
.profile-form { margin-top: 16px; padding: 16px; border: 1px solid #ddd; border-radius: 8px; background: #fafafa; }
.profile-form h4 { margin: 0 0 8px; }
.field-label { display: block; margin: 12px 0 6px; font-size: 14px; font-weight: 600; }
.base-url-input { min-width: 420px; }
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
  .base-url-input { min-width: 0; }
  .section-heading, .profile-row { align-items: flex-start; }
  .profile-row { flex-direction: column; }
  .profile-actions { width: 100%; }
}
</style>
