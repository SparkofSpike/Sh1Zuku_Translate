<template>
  <div class="profile-grid">
    <section class="card model-card">
      <h2 class="page-title">{{ t('nav.profile') }}</h2>
      <div class="account-summary">
        <p><strong>{{ t('profile.account.username') }}</strong>{{ profile.username }}</p>
        <p>
          <strong>{{ t('profile.account.email') }}</strong>{{ profile.email }}
          <span v-if="profile.emailVerified" class="badge badge-ok">{{ t('profile.badge.verified') }}</span>
          <span v-else-if="profile.email" class="badge badge-warn">{{ t('profile.badge.unverified') }}</span>
        </p>
        <p><strong>{{ t('profile.account.createdAt') }}</strong>{{ profile.createdAt || t('common.unknown') }}</p>
      </div>

      <div v-if="!profile.emailVerified || showEmailForm" class="email-verify">
        <div class="section-heading">
          <div>
            <h3>{{ t('profile.emailVerify.title') }}</h3>
            <p class="hint">{{ t('profile.emailVerify.hint') }}</p>
          </div>
        </div>
        <div class="email-row">
          <input v-model.trim="emailInput" type="email" :placeholder="t('profile.emailVerify.emailPlaceholder')" />
          <button class="btn-sm" type="button" @click="sendVerifyCode" :disabled="sendingCode || countdown > 0">
            {{ countdown > 0 ? t('profile.emailVerify.resendIn', { seconds: countdown }) : (sendingCode ? t('profile.emailVerify.sending') : t('profile.emailVerify.sendCode')) }}
          </button>
        </div>
        <div class="email-row">
          <input v-model.trim="verifyCode" type="text" inputmode="numeric" maxlength="6" :placeholder="t('profile.emailVerify.codePlaceholder')" />
          <button class="btn-sm btn-primary" type="button" @click="submitVerify" :disabled="verifying">
            {{ verifying ? t('profile.emailVerify.submitting') : t('profile.emailVerify.submit') }}
          </button>
        </div>
        <p v-if="emailMessage" class="success" style="margin:6px 0 0;">{{ emailMessage }}</p>
        <p v-if="emailError" class="error" style="margin:6px 0 0;">{{ emailError }}</p>
        <p class="field-hint" style="margin:6px 0 0;">{{ t('profile.emailVerify.validityHint') }}</p>
      </div>
      <div v-else class="email-actions">
        <span class="muted">{{ t('profile.emailVerify.verified') }}</span>
        <button class="btn-sm" type="button" @click="startEditEmail">{{ t('profile.emailVerify.changeEmail') }}</button>
      </div>
      <div class="usage-highlight">
        <span class="usage-label">{{ t('profile.usage.label') }}</span>
        <strong>{{ formatNumber(usage.totalTokens) }}</strong>
        <span class="usage-meta">{{ t('profile.usage.meta', { input: formatNumber(usage.promptTokens), output: formatNumber(usage.completionTokens), count: usage.requestCount }) }}</span>
      </div>

      <hr />

      <div class="section-heading">
        <div>
          <h3>{{ t('profile.model.title') }}</h3>
          <p class="hint">{{ t('profile.model.hint') }}</p>
        </div>
        <button class="btn-sm btn-primary" @click="startCreate">{{ t('profile.model.add') }}</button>
      </div>

      <div class="profile-list">
        <div
          class="profile-row site-profile"
          :class="{ selected: selectedProfileId === null }"
          @click="selectProfile(null)"
        >
          <div class="profile-main">
            <strong>{{ t('profile.model.site') }}</strong>
            <span class="profile-meta">{{ t('profile.model.siteMeta') }}</span>
          </div>
          <span v-if="selectedProfileId === null" class="selected-label">{{ t('profile.model.selected') }}</span>
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
            <span class="profile-meta">{{ providerLabel(item.provider) }}/{{ item.model }}</span>
            <span class="profile-key">{{ t('profile.model.apiKeyPrefix') + (item.hasApiKey ? item.apiKeyPreview : t('profile.model.usingSiteKey')) }}</span>
          </div>
          <div class="profile-actions">
            <span v-if="selectedProfileId === item.id" class="selected-label">{{ t('profile.model.selected') }}</span>
            <button class="btn-sm btn-remove" @click.stop="startEdit(item)">{{ t('common.edit') }}</button>
            <button class="btn-sm btn-remove" @click.stop="deleteProfile(item.id)">{{ t('common.delete') }}</button>
          </div>
        </div>
      </div>

      <p v-if="!modelProfiles.length" class="empty-hint">{{ t('profile.model.empty') }}</p>

      <div v-if="formVisible" class="profile-form">
        <h4>{{ editingId ? t('profile.model.editTitle') : t('profile.model.createTitle') }}</h4>
        <label class="field-label">{{ t('profile.form.name') }}</label>
        <input v-model.trim="form.name" type="text" />

        <label class="field-label">{{ t('profile.form.provider') }}</label>
        <select v-model="form.provider" @change="handleProviderChange">
          <option value="deepseek">DeepSeek</option>
          <option value="openai">{{ t('profile.provider.openai') }}</option>
          <option value="anthropic">{{ t('profile.provider.anthropic') }}</option>
        </select>

        <label class="field-label">{{ t('profile.form.modelName') }}</label>
        <div class="model-input-row">
          <input v-model.trim="manualModel" type="text" :placeholder="t('profile.form.modelPlaceholder')" @keydown.enter.prevent="addManualModel" />
          <button class="btn-sm" type="button" @click="addManualModel">{{ t('profile.form.addModel') }}</button>
          <button class="btn-sm" type="button" @click="detectModels" :disabled="detecting">{{ detecting ? t('profile.form.detecting') : t('profile.form.detect') }}</button>
        </div>
        <div v-if="form.models.length" class="selected-models">
          <span v-for="model in form.models" :key="model" class="selected-model">
            {{ model }}
            <button type="button" class="remove-model" @click="removeModel(model)" :aria-label="t('profile.form.removeModel', { model })">×</button>
          </span>
        </div>
        <div v-if="detectedModels.length" class="detected-models">
          <span class="field-hint detected-title">{{ t('profile.form.detectedTitle') }}</span>
          <label v-for="model in detectedModels" :key="model" class="model-option">
            <input v-model="form.models" type="checkbox" :value="model" />
            <span>{{ model }}</span>
          </label>
        </div>
        <p class="field-hint">{{ t('profile.form.multiHint') }}</p>

        <template v-if="form.provider !== 'deepseek'">
          <label class="field-label">{{ t('profile.form.baseUrl') }}</label>
          <input v-model.trim="form.baseUrl" class="base-url-input" type="url" :placeholder="baseUrlPlaceholder" />
        </template>

        <label class="field-label">{{ t('profile.form.apiKey') }}</label>
        <input v-model.trim="form.apiKey" type="password" :placeholder="editingId ? t('profile.form.apiKeyKeep') : t('profile.form.apiKeyOptional')" />
        <p v-if="editingId && editingProfile?.hasApiKey" class="configured">{{ t('profile.form.currentKeyPrefix') }}{{ editingProfile.apiKeyPreview }}</p>

        <div class="actions">
          <button @click="saveProfile" :disabled="saving">{{ saving ? t('profile.form.saving') : t('profile.form.save') }}</button>
          <button class="btn-sm btn-remove" @click="cancelForm">{{ t('common.cancel') }}</button>
          <button v-if="editingId && form.provider === 'deepseek' && editingProfile?.hasApiKey" class="btn-sm btn-remove" @click="clearProfileKey">{{ t('profile.form.clearKey') }}</button>
        </div>
      </div>

      <p v-if="message" class="success">{{ message }}</p>
      <p v-if="error" class="error">{{ error }}</p>
    </section>

    <section class="card plugin-card">
      <h3 class="section-title">{{ t('profile.plugin.title') }}</h3>
      <p class="hint">{{ t('profile.plugin.hint') }}</p>
      <button @click="createPluginKey">{{ t('profile.plugin.generate') }}</button>
      <div v-if="newKey" class="new-key">
        <code>{{ newKey }}</code>
        <button class="btn-sm btn-primary" @click="copyKey">{{ t('common.copy') }}</button>
      </div>
      <div v-if="keys.length" class="key-list">
        <div v-for="key in keys" :key="key.id" class="key-row">
          <span>{{ key.name }}</span>
          <span class="muted">{{ key.keyPrefix }}</span>
          <button class="btn-sm btn-remove" @click="deleteKey(key.id)">{{ t('common.delete') }}</button>
        </div>
      </div>
    </section>
  </div>
</template>

<script setup>
import { computed, onMounted, onUnmounted, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import api, { sendEmailCode, verifyEmail } from '../api'
import { useAuthStore } from '../stores/auth'

const authStore = useAuthStore()
const { t } = useI18n()

const PROFILE_SELECTION_KEY = 'modelProfileId'
const DEFAULT_BASE_URLS = {
  openai: 'https://api.openai.com/v1',
  anthropic: 'https://api.anthropic.com/v1'
}

const profile = ref({ username: '', email: '', createdAt: '', emailVerified: false })
const emailInput = ref('')
const verifyCode = ref('')
const sendingCode = ref(false)
const verifying = ref(false)
const countdown = ref(0)
const showEmailForm = ref(false)
let countdownTimer = null
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
const detecting = ref(false)
const detectedModels = ref([])
const manualModel = ref('')
const newKey = ref('')
const keys = ref([])

const baseUrlPlaceholder = computed(() => DEFAULT_BASE_URLS[form.value.provider] || '')

function emptyForm() {
  return { name: '', provider: 'deepseek', baseUrl: '', model: '', models: [], apiKey: '' }
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
    localStorage.setItem('modelSelection', 'site:deepseek-flash')
  } else {
    localStorage.setItem(PROFILE_SELECTION_KEY, String(id))
    localStorage.setItem('modelSelection', 'profile:' + id)
  }
  message.value = id === null ? t('profile.model.selectedSite') : t('profile.model.selectedPersonal')
  error.value = ''
}

function providerLabel(provider) {
  return provider === 'anthropic' ? t('profile.provider.anthropic') : provider === 'openai' ? t('profile.provider.openai') : t('profile.provider.deepseek')
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
    models: Array.isArray(item.models) && item.models.length ? item.models : [item.model],
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
    if (!emailInput.value) emailInput.value = res.data.email || ''
  } catch (e) {
    error.value = e.response?.data?.error || t('profile.errors.loadProfile')
  }
}

function isValidEmail(value) {
  return /^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(value)
}

const emailMessage = ref('')
const emailError = ref('')
function startEditEmail() {
  emailInput.value = profile.value.email || ''
  showEmailForm.value = true
  emailMessage.value = ''
  emailError.value = ''
}

function startCountdown() {
  if (countdownTimer) clearInterval(countdownTimer)
  countdown.value = 60
  countdownTimer = setInterval(() => {
    countdown.value -= 1
    if (countdown.value <= 0) {
      countdown.value = 0
      if (countdownTimer) clearInterval(countdownTimer)
    }
  }, 1000)
}

onUnmounted(() => {
  if (countdownTimer) clearInterval(countdownTimer)
})

async function sendVerifyCode() {
  if (!isValidEmail(emailInput.value)) {
    emailError.value = t('profile.errors.enterValidEmail')
    return
  }
  emailError.value = ''
  emailMessage.value = ''
  sendingCode.value = true
  try {
    await sendEmailCode(emailInput.value)
    emailMessage.value = t('profile.emailVerify.codeSent')
    startCountdown()
  } catch (e) {
    emailError.value = e.response?.data?.error || t('profile.errors.codeSendFailed')
  } finally {
    sendingCode.value = false
  }
}

async function submitVerify() {
  if (!isValidEmail(emailInput.value)) {
    emailError.value = t('profile.errors.enterValidEmail')
    return
  }
  if (!verifyCode.value.trim()) {
    emailError.value = t('profile.errors.enterCode')
    return
  }
  emailError.value = ''
  emailMessage.value = ''
  verifying.value = true
  try {
    await verifyEmail(emailInput.value, verifyCode.value.trim())
    authStore.setEmailVerified(true)
    await loadProfile()
    showEmailForm.value = false
    verifyCode.value = ''
    emailMessage.value = t('profile.emailVerify.success')
  } catch (e) {
    emailError.value = e.response?.data?.error || t('profile.errors.verifyFailed')
  } finally {
    verifying.value = false
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
    error.value = e.response?.data?.error || t('profile.errors.loadProfiles')
  }
}

async function detectModels() {
  if (!form.value.apiKey && !editingProfile.value?.hasApiKey) {
    error.value = t('profile.errors.apiKeyRequired')
    return
  }
  detecting.value = true
  error.value = ''
  try {
    const res = await api.post('/auth/model-profiles/detect', {
      profileId: editingId.value ? String(editingId.value) : undefined,
      provider: form.value.provider,
      baseUrl: form.value.provider === 'deepseek' && !form.value.baseUrl ? '' : form.value.baseUrl,
      apiKey: form.value.apiKey || undefined
    })
    detectedModels.value = Array.isArray(res.data) ? res.data : []
    form.value.models = [...new Set(form.value.models)]
    if (!detectedModels.value.length) message.value = t('profile.model.noModels')
    else message.value = t('profile.model.detected', { count: detectedModels.value.length })
  } catch (e) {
    error.value = e.response?.data?.error || t('profile.errors.detectFailed')
  } finally {
    detecting.value = false
  }
}

async function saveProfile() {
  form.value.models = form.value.models.filter(model => model && model.trim())
  if (!form.value.models.length && form.value.model) form.value.models = [form.value.model.trim()]
  if (!form.value.models.length) {
    error.value = t('profile.errors.modelRequired')
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
      model: form.value.models[0],
      models: form.value.models,
      ...(form.value.apiKey ? { apiKey: form.value.apiKey } : {})
    }
    let res
    if (editingId.value) {
      res = await api.put('/auth/model-profiles/' + editingId.value, body)
    } else {
      res = await api.post('/auth/model-profiles', body)
    }
    await loadModelProfiles()
    const createdProfiles = Array.isArray(res?.data) ? res.data : []
    if (createdProfiles[0]?.id) selectProfile(createdProfiles[0].id)
    form.value.apiKey = ''
    formVisible.value = false
    message.value = t('profile.model.saved')
  } catch (e) {
    error.value = e.response?.data?.error || t('profile.errors.saveFailed')
  } finally {
    saving.value = false
  }
}

function addManualModel() {
  const model = manualModel.value.trim()
  if (!model) return
  if (!form.value.models.includes(model)) form.value.models.push(model)
  form.value.model = form.value.models[0] || model
  manualModel.value = ''
}

function toggleModel(model) {
  if (form.value.models.includes(model)) removeModel(model)
  else form.value.models = [...form.value.models, model]
  form.value.model = form.value.models[0] || ''
}

function removeModel(model) {
  form.value.models = form.value.models.filter(item => item !== model)
  form.value.model = form.value.models[0] || ''
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
    message.value = t('profile.model.keyCleared')
  } catch (e) {
    error.value = e.response?.data?.error || t('profile.errors.clearKeyFailed')
  }
}

async function deleteProfile(id) {
  if (!window.confirm(t('profile.model.confirmDelete'))) return
  try {
    await api.delete('/auth/model-profiles/' + id)
    if (selectedProfileId.value === id) selectProfile(null)
    await loadModelProfiles()
    message.value = t('profile.model.deleted')
  } catch (e) {
    error.value = e.response?.data?.error || t('profile.errors.deleteFailed')
  }
}

async function loadUsage() {
  try {
    const res = await api.get('/auth/usage')
    usage.value = res.data
  } catch (e) {
    error.value = e.response?.data?.error || t('profile.errors.loadUsage')
  }
}

async function createPluginKey() {
  try {
    const res = await api.post('/auth/api-key', { name: 'pixiv-plugin' })
    newKey.value = res.data.keyValue
    await loadKeys()
  } catch (e) {
    error.value = e.response?.data?.error || t('profile.errors.generateFailed')
  }
}

async function copyKey() {
  try {
    await navigator.clipboard.writeText(newKey.value)
    message.value = t('common.copied')
  } catch (e) {
    error.value = t('profile.errors.copyFailed')
  }
}

async function loadKeys() {
  try {
    const res = await api.get('/auth/api-keys')
    keys.value = res.data.apiKeys || []
  } catch (e) {
    error.value = e.response?.data?.error || t('profile.errors.loadKeys')
  }
}

async function deleteKey(id) {
  try {
    await api.delete('/auth/api-key/' + id)
    await loadKeys()
  } catch (e) {
    error.value = e.response?.data?.error || t('profile.errors.deleteKeyFailed')
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
.model-input-row { display: flex; gap: 8px; align-items: center; }
.model-input-row input { flex: 1; }
.detected-models { display: flex; flex-wrap: wrap; gap: 8px; margin-top: 10px; padding: 10px; border: 1px solid #ddd; border-radius: 6px; background: #fff; }
.detected-title { flex-basis: 100%; margin: 0; }
.model-option { display: inline-flex; align-items: center; gap: 5px; padding: 6px 9px; border: 1px solid #ccc; border-radius: 6px; background: #f7f7f7; color: #222; cursor: pointer; font-size: 13px; }
.model-option:hover { background: #eee; border-color: #999; }
.model-option input { width: auto; margin: 0; accent-color: #1a1a1a; }
.selected-models { display: flex; flex-wrap: wrap; gap: 6px; margin-top: 10px; }
.selected-model { display: inline-flex; align-items: center; gap: 6px; padding: 5px 9px; border-radius: 999px; background: #1a1a1a; color: #fff; font-size: 13px; }
.remove-model { padding: 0; background: transparent; color: #fff; font-size: 16px; line-height: 1; }
.remove-model:hover { background: transparent; color: #ffb3b3; }
.usage-highlight { display: flex; flex-direction: column; gap: 2px; margin-top: 20px; padding: 16px; background: #f5f5f5; border: 1px solid #e6e6e6; border-radius: 8px; }
.usage-highlight strong { font-size: 30px; letter-spacing: -.5px; }
.usage-label, .usage-meta, .muted { color: #777; font-size: 13px; }
.usage-meta { font-size: 12px; }
.actions { display: flex; flex-wrap: wrap; gap: 10px; margin-top: 16px; }
.configured, .success { color: #2b8a3e; font-size: 14px; }
.error { color: #e03131; font-size: 14px; }
.badge { display: inline-block; margin-left: 6px; padding: 1px 8px; border-radius: 999px; font-size: 12px; }
.badge-ok { background: #e6f4ea; color: #2b8a3e; }
.badge-warn { background: #fff4e5; color: #e8590c; }
.email-verify { margin-top: 16px; padding: 14px 16px; border: 1px solid #f0d9a8; border-radius: 8px; background: #fdfaf3; }
.email-row { display: flex; gap: 8px; margin-bottom: 8px; }
.email-row input { flex: 1; min-width: 0; }
.email-actions { display: flex; align-items: center; gap: 12px; margin-top: 14px; padding: 10px 14px; background: #f5f5f5; border-radius: 8px; }
.email-actions button { flex-shrink: 0; }
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
