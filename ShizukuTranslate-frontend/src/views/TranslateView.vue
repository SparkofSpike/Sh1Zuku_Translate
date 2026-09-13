<template>
  <div v-if="authStore.emailVerified === false" class="card verify-gate">
    <h2 style="margin-top:0; font-weight:600;">{{ t('translate.emailGate.title') }}</h2>
    <p class="verify-desc">{{ t('translate.emailGate.desc1') }}</p>
    <p class="verify-desc">{{ t('translate.emailGate.desc2') }}</p>
    <router-link to="/profile" style="text-decoration:none;">
      <button style="margin-top:8px;">{{ t('translate.emailGate.action') }}</button>
    </router-link>
  </div>
  <div v-else class="translate-layout" :class="{ 'has-announcements': announcements.length > 0 }">

    <div class="card translation-card">
      <div class="open-source-banner">
        {{ t('translate.openSource.lead') }}<a
          href="https://github.com/SparkofSpike/Sh1Zuku_Translate"
          target="_blank"
          rel="noopener noreferrer"
        >Sh1Zuku_Translate</a>{{ t('translate.openSource.tail') }}
        <br />{{ t('translate.openSource.plugin') }}
      </div>

      <h2 style="margin-top:0; font-weight:600;">{{ t('translate.heading') }}</h2>

    <OcrPreview
      v-if="ocrPreviews.length"
      :previews="ocrPreviews"
      :loading="ocrLoading"
      :polish="ocrPolish"
      :threshold="ocrThreshold"
      :mode="imageProcessingMode"
      @ocr="doOcr"
      @clear="clearImages"
      @remove="removeImageAt"
      @update:polish="ocrPolish = $event"
      @update:threshold="ocrThreshold = $event"
    />

    <p v-if="ocrError" style="color:#e03131; margin-top:8px; font-size:14px;">{{ ocrError }}</p>

    <div
      class="source-wrap"
      :class="{ 'source-wrap--active': dragActive }"
      @dragenter.prevent="onDragEnter"
      @dragover.prevent
      @dragleave.prevent="onDragLeave"
      @drop.prevent="onDropFile"
    >
      <textarea
        v-model="sourceText"
        :placeholder="t('translate.placeholder')"
        rows="10"
        @paste="onTextareaPaste"
      ></textarea>
      <button class="upload-btn" type="button" :title="t('translate.upload.title')" @click="fileInput?.click()">{{ t('translate.upload.label') }}</button>
      <input
        ref="fileInput"
        type="file"
        accept="image/*,.txt,.md,text/plain,text/markdown"
        multiple
        style="display:none"
        @change="onPickFile"
      />
    </div>

    <div style="display:flex; gap:16px; flex-wrap:wrap; margin-top:16px;">
      <select v-model="selectedModelKey" @change="handleModelChange" style="width:auto; min-width:240px;">
        <option v-for="option in modelOptions" :key="option.key" :value="option.key">{{ optionLabel(option) }}</option>
      </select>
      <select
        v-if="languageOptions.length"
        v-model="targetLanguage"
        :title="t('translate.targetLanguage')"
        style="width:auto; min-width:160px;"
      >
        <option v-for="lang in languageOptions" :key="lang.code" :value="lang.code">{{ lang.label }}</option>
      </select>
      <label style="display:flex; align-items:center; gap:4px; cursor:pointer; font-size:14px;">
        <input type="checkbox" v-model="streamingEnabled" />
        {{ t('translate.streaming') }}
      </label>
      <label v-if="pendingImageFiles.length" style="display:flex; align-items:center; gap:4px; cursor:pointer; font-size:14px;">
        {{ t('translate.imageMode.label') }}
        <select v-model="imageProcessingMode" style="width:auto;">
          <option value="model">{{ t('translate.imageMode.model') }}</option>
          <option value="ocr">{{ t('translate.imageMode.ocr') }}</option>
        </select>
      </label>
    </div>

    <PresetSelector
      v-if="presetOptions.length"
      v-model="selectedPresets"
      :options="presetOptions"
    />

    <textarea
      v-model="customPrompt"
      :placeholder="t('translate.customPrompt')"
      rows="3"
      style="margin-top:16px;"
    ></textarea>

    <button
      @click="status === 'idle' ? translate() : cancel()"
      :disabled="false"
      :style="{
        marginTop: '16px',
        background: status === 'idle' ? undefined : '#e03131',
        borderColor: status === 'idle' ? undefined : '#e03131',
      }"
    >
      {{ status === 'idle' ? t('translate.start') : t('translate.cancel') }}
    </button>

    <p v-if="statusText" class="translate-status">{{ statusText }}</p>

    <p v-if="error" style="color:#e03131; margin-top:12px;">{{ error }}</p>

    <SseTranslateResult v-if="useStreaming" :streaming-text="streamingText" :result="streamingResult" />
    <TranslateResult v-else-if="result" :result="result" />
    </div>

    <AnnouncementPanel
      v-if="announcements.length"
      class="announcement-right"
      :announcements="announcements"
    />
  </div>
</template>

<script setup lang="ts">
import { computed, ref, watch, onMounted, onUnmounted } from 'vue'
import { useI18n } from 'vue-i18n'
import axios from 'axios'
import api, { ocrImage, translateImages, translateStream } from '../api'
import type { Announcement, LanguageOption, TranslateResponse } from '../types'
import OcrPreview from '../components/OcrPreview.vue'
import PresetSelector from '../components/PresetSelector.vue'
import TranslateResult from '../components/TranslateResult.vue'
import SseTranslateResult from '../components/SseTranslateResult.vue'
import AnnouncementPanel from '../components/AnnouncementPanel.vue'
import { useAuthStore } from '../stores/auth'

const authStore = useAuthStore()
const { t, locale } = useI18n()
const sourceText = ref('')
const model = ref('deepseek-flash')
const modelProfileId = ref<number | null>(readSelectedProfileId())

// DeepSeek retired deepseek-v4-flash / deepseek-v4-flash-vision-exp in favor of the
// merged deepseek-flash, so browsers still holding an old site key are migrated here;
// otherwise the picker would show no selection after the list change.
function normalizeSiteSelection(key: string): string {
  return key.startsWith('site:deepseek-v4-flash') ? 'site:deepseek-flash' : key
}

const selectedModelKey = ref(normalizeSiteSelection(localStorage.getItem('modelSelection') || '') || (modelProfileId.value ? `profile:${modelProfileId.value}` : 'site:deepseek-flash'))

interface ModelOption {
  key: string
  /** 个人模型配置 id；站方模型选项为 null */
  id: number | null
  model: string
  label: string
  /** 仅个人模型配置选项携带：`profile:{id}`，用于从旧版 localStorage 记录恢复选择 */
  profileKey?: string
}

const modelOptions = ref<ModelOption[]>([
  { key: 'site:deepseek-flash', id: null as number | null, model: 'deepseek-flash', label: '站方/deepseek-flash' },
  { key: 'site:deepseek-v4-pro', id: null as number | null, model: 'deepseek-v4-pro', label: '站方/deepseek-v4-pro' }
])

/** Site models carry a prefix that has to follow the UI language, so it is applied at render time. */
function optionLabel(option: ModelOption) {
  return option.id === null ? `${t('translate.siteModelPrefix')}/${option.model}` : option.label
}
const customPrompt = ref('')
const selectedPresets = ref<string[]>([])
const presetOptions = ref<string[]>([])

/**
 * Target language for the translation. By default it follows the interface language, so a reader
 * who switches the site to Vietnamese gets Vietnamese output without touching this control.
 * Choosing a *different* language here stores an explicit override; choosing the interface
 * language again clears it.
 *
 * The storage key is deliberately not the old `targetLanguage` one: the language-following logic
 * used to write that key itself, so a later visit mistook a programmatic value for a user choice
 * and left the target stuck on a language the interface no longer showed.
 */
const TARGET_LANGUAGE_OVERRIDE_KEY = 'targetLanguageOverride'
const targetOverride = ref(localStorage.getItem(TARGET_LANGUAGE_OVERRIDE_KEY) || '')

const targetLanguage = computed({
  get: () => targetOverride.value || locale.value,
  set: (value: string) => {
    targetOverride.value = value === locale.value ? '' : value
    if (targetOverride.value) {
      localStorage.setItem(TARGET_LANGUAGE_OVERRIDE_KEY, targetOverride.value)
    } else {
      localStorage.removeItem(TARGET_LANGUAGE_OVERRIDE_KEY)
    }
  }
})
const languageOptions = ref<LanguageOption[]>([])
const announcements = ref<Announcement[]>([])

const result = ref<TranslateResponse | null>(null)
const error = ref('')

const status = ref<'idle' | 'preparing' | 'ai-processing'>('idle')

/**
 * Spelled out next to the button: the button itself carries the action (cancel while a
 * translation is in flight), while this line says what is actually happening. Mirrors
 * the status message the browser extension shows in its popup.
 */
const statusText = computed(() => {
  if (status.value === 'preparing') return t('translate.status.preparing')
  if (status.value === 'ai-processing') {
    return streamingText.value
      ? t('translate.status.translatingWithCount', { count: streamingText.value.length })
      : t('translate.status.translating')
  }
  return ''
})

// SSE streaming
const streamingEnabled = ref(true)
const useStreaming = ref(false)
const streamingText = ref('')
const streamingResult = ref<TranslateResponse | null>(null)

// Cancel
let cancelFn: (() => void) | null = null

// OCR related
const ocrPreviews = ref<string[]>([])
const ocrLoading = ref(false)
const ocrError = ref('')
const ocrPolish = ref(false)
const ocrThreshold = ref(0.3)
const pendingImageFiles = ref<File[]>([])
/** Mirrors TranslationService.MAX_IMAGES_PER_REQUEST; keep the two in sync. */
const MAX_IMAGES = 10
const imageProcessingMode = ref<'model' | 'ocr'>('model')

// Inline upload (button + drag & drop)
const fileInput = ref<HTMLInputElement | null>(null)
const dragActive = ref(false)
let dragDepth = 0

interface ModelProfileOption {
  id: number
  name: string
  model: string
  models?: string[]
}

/**
 * Initial page data. These calls are independent, so they are fired together: a single slow or
 * hanging request must not hold up the rest of the page's initialisation. (An earlier version
 * awaited them one after another, so one stuck request left the page half-loaded — the language
 * list, model list and announcements would never arrive.)
 */
const INIT_REQUEST_TIMEOUT_MS = 15000

onMounted(async () => {
  const [meResult, presetsResult, languagesResult, profilesResult, announcementsResult] =
    await Promise.allSettled([
      api.get('/auth/me', { timeout: INIT_REQUEST_TIMEOUT_MS }),
      api.get('/presets', { timeout: INIT_REQUEST_TIMEOUT_MS }),
      api.get('/translation/languages', { timeout: INIT_REQUEST_TIMEOUT_MS }),
      api.get('/auth/model-profiles', { timeout: INIT_REQUEST_TIMEOUT_MS }),
      api.get('/announcements', { timeout: INIT_REQUEST_TIMEOUT_MS })
    ])

  if (meResult.status === 'fulfilled') {
    authStore.setAdmin(!!meResult.value.data.isAdmin)
    authStore.setEmailVerified(!!meResult.value.data.emailVerified)
  } else {
    console.error('无法获取认证状态', meResult.reason)
  }
  if (presetsResult.status === 'fulfilled') {
    presetOptions.value = presetsResult.value.data || []
  } else {
    console.error('无法加载预设列表', presetsResult.reason)
  }
  if (languagesResult.status === 'fulfilled') {
    languageOptions.value = languagesResult.value.data || []
  } else {
    console.error('无法加载目标语言列表', languagesResult.reason)
  }
  // Keep the stored selection valid if the backend no longer offers it.
  if (languageOptions.value.length && !languageOptions.value.some(lang => lang.code === targetLanguage.value)) {
    const fallback = languageOptions.value[0]
    if (fallback) targetLanguage.value = fallback.code
  }
  if (profilesResult.status === 'fulfilled') {
    const profiles = profilesResult.value.data || []
    modelOptions.value = [
      { key: 'site:deepseek-flash', id: null, model: 'deepseek-flash', label: '站方/deepseek-flash' },
      { key: 'site:deepseek-v4-pro', id: null, model: 'deepseek-v4-pro', label: '站方/deepseek-v4-pro' },
      ...profiles.flatMap((item: ModelProfileOption & { provider: string }) => {
        const models = Array.isArray(item.models) && item.models.length ? item.models : [item.model]
        return models.map(modelName => ({
          key: `profile:${item.id}:${modelName}`,
          profileKey: `profile:${item.id}`,
          id: item.id,
          model: modelName,
          label: `${item.name}/${modelName}`
        }))
      })
    ]
    const storedProfileId = modelProfileId.value && modelProfileId.value > 0
      ? modelProfileId.value
      : null
    const storedProfileKey = storedProfileId ? 'profile:' + storedProfileId : ''
    const storedSelection = normalizeSiteSelection(localStorage.getItem('modelSelection') || '')
    const profileSelectionIsValid = storedProfileKey
      && modelOptions.value.some(option => option.key === storedProfileKey || option.profileKey === storedProfileKey)
    const savedSelectionIsValid = modelOptions.value.some(option => option.key === storedSelection)

    // A profile ID is more authoritative than the legacy display-mode key.
    // This recovers users whose old localStorage still says "site" after
    // they configured a personal model profile.
    if (profileSelectionIsValid) {
      selectedModelKey.value = modelOptions.value.find(option => option.profileKey === storedProfileKey)?.key || storedProfileKey
    } else if (savedSelectionIsValid) {
      selectedModelKey.value = storedSelection
    } else if (profiles.length) {
      const firstProfile = profiles[0]
      const firstModels = Array.isArray(firstProfile.models) && firstProfile.models.length
        ? firstProfile.models : [firstProfile.model]
      selectedModelKey.value = `profile:${firstProfile.id}:${firstModels[0]}`
    } else {
      selectedModelKey.value = ''
    }
    handleModelChange()
  } else {
    console.error('无法加载个人模型配置', profilesResult.reason)
  }
  if (announcementsResult.status === 'fulfilled') {
    announcements.value = announcementsResult.value.data || []
  } else {
    console.error('无法加载公告列表', announcementsResult.reason)
  }
})

onUnmounted(() => {
  cancel()
})

function readSelectedProfileId(): number | null {
  const value = localStorage.getItem('modelProfileId')
  const id = value ? Number(value) : 0
  return Number.isInteger(id) && id > 0 ? id : null
}

function handleModelChange() {
  const selected = modelOptions.value.find(option => option.key === selectedModelKey.value)
  if (!selected) return
  model.value = selected.model
  modelProfileId.value = selected.id === null ? 0 : selected.id
  localStorage.setItem('modelSelection', selected.key)
  if (selected.id === null) localStorage.setItem('modelProfileId', '0')
  else localStorage.setItem('modelProfileId', String(selected.id))
}

function onPickFile(e: Event) {
  const target = e.target as HTMLInputElement
  const files = Array.from(target.files || [])
  if (files.length) handleAttachments(files)
  target.value = ''
}

function onDragEnter() {
  dragDepth++
  dragActive.value = true
}

function onDragLeave() {
  dragDepth = Math.max(0, dragDepth - 1)
  if (dragDepth === 0) dragActive.value = false
}

function onDropFile(e: DragEvent) {
  dragDepth = 0
  dragActive.value = false
  const files = Array.from(e.dataTransfer?.files || [])
  if (files.length) handleAttachments(files)
}

function onTextareaPaste(e: ClipboardEvent) {
  const items = e.clipboardData?.items
  if (!items) return
  const images: File[] = []
  for (const item of items) {
    if (item.type.startsWith('image/')) {
      const file = item.getAsFile()
      if (file) images.push(file)
    }
  }
  if (images.length) {
    e.preventDefault()
    handleAttachments(images)
  }
}

function handleAttachments(files: File[]) {
  const isText = (f: File) => /\.(txt|md)$/i.test(f.name)
  const textFiles = files.filter(isText)
  const imageFiles = files.filter(f => !isText(f))
  if (textFiles.length) {
    // A text attachment replaces the textarea; the last one wins so selecting several
    // documents at once cannot silently concatenate unrelated texts.
    const reader = new FileReader()
    reader.onload = () => { sourceText.value = String(reader.result || '') }
    reader.readAsText(textFiles[textFiles.length - 1])
  }
  if (imageFiles.length) handleImageFiles(imageFiles)
}

function handleImageFiles(files: File[]) {
  ocrError.value = ''
  const room = MAX_IMAGES - pendingImageFiles.value.length
  if (room <= 0) {
    ocrError.value = t('translate.errors.tooManyImages', { max: MAX_IMAGES })
    return
  }
  const accepted = files.slice(0, room)
  if (accepted.length < files.length) {
    ocrError.value = t('translate.errors.tooManyImagesKept', { max: MAX_IMAGES })
  }
  for (const file of accepted) {
    pendingImageFiles.value.push(file)
    const slot = ocrPreviews.value.length
    ocrPreviews.value.push('')
    const reader = new FileReader()
    reader.onload = (e) => {
      // Resolve the slot by file identity, so deleting a thumbnail while another
      // read is still in flight cannot shift the previews out of order.
      const index = pendingImageFiles.value.indexOf(file)
      if (index >= 0) ocrPreviews.value[index] = e.target?.result as string
      else ocrPreviews.value.splice(slot, 1)
    }
    reader.readAsDataURL(file)
  }
}

function removeImageAt(index: number) {
  pendingImageFiles.value.splice(index, 1)
  ocrPreviews.value.splice(index, 1)
}

function clearImages() {
  pendingImageFiles.value = []
  ocrPreviews.value = []
  ocrError.value = ''
}

async function doOcr() {
  const files = pendingImageFiles.value.slice()
  if (!files.length) return
  ocrLoading.value = true
  ocrError.value = ''
  try {
    // The OCR worker runs single-threaded (Paddle predictors cannot be shared across
    // threads), so pages go one at a time and are joined in upload order afterwards.
    const pages: string[] = []
    for (const file of files) {
      const res = await ocrImage(file, ocrPolish.value, ocrThreshold.value)
      const text = res.data.text?.trim()
      if (text) pages.push(text)
    }
    if (pages.length) {
      sourceText.value = pages.join('\n\n')
      clearImages()
    } else {
      ocrError.value = t('translate.errors.noText')
    }
  } catch (e: any) {
    const msg = e.response?.data?.error || e.message || t('translate.errors.ocrFailed')
    ocrError.value = msg
  } finally {
    ocrLoading.value = false
  }
}

function cancel() {
  if (cancelFn) cancelFn()
  status.value = 'idle'
  error.value = ''
}

async function translate() {
  if (!sourceText.value.trim() && !pendingImageFiles.value.length) return
  if (pendingImageFiles.value.length && imageProcessingMode.value === 'model') {
    try {
      const request = { sourceText: sourceText.value, model: model.value, modelProfileId: modelProfileId.value,
        customPrompt: customPrompt.value || undefined, presets: selectedPresets.value.length ? selectedPresets.value : undefined,
        targetLanguage: targetLanguage.value }
      const response = await translateImages(pendingImageFiles.value, request)
      result.value = response.data
      useStreaming.value = false
      clearImages()
      return
    } catch (e: any) {
      error.value = e.response?.data?.error || e.message || t('translate.errors.imageModelFailed')
      return
    }
  }
  if (pendingImageFiles.value.length && imageProcessingMode.value === 'ocr') {
    await doOcr()
    if (!sourceText.value.trim()) return
  }
  status.value = 'preparing'
  error.value = ''

  if (streamingEnabled.value) {
    useStreaming.value = true
    streamingText.value = ''
    streamingResult.value = null
    result.value = null

    const ctrl = translateStream(
      sourceText.value,
      model.value,
      modelProfileId.value,
      customPrompt.value || undefined,
      selectedPresets.value.length > 0 ? selectedPresets.value : undefined,
      targetLanguage.value,
      (token: string) => {
        if (status.value === 'preparing') status.value = 'ai-processing'
        streamingText.value += token
      },
      (response: TranslateResponse) => {
        streamingResult.value = response
        status.value = 'idle'
        cancelFn = null
      },
      (err: string) => {
        error.value = err
        status.value = 'idle'
        cancelFn = null
      }
    )

    cancelFn = () => {
      ctrl.abort()
      status.value = 'idle'
      cancelFn = null
    }
  } else {
    // Sync mode
    useStreaming.value = false
    streamingText.value = ''
    streamingResult.value = null
    result.value = null

    const controller = new AbortController()
    cancelFn = () => {
      controller.abort()
      status.value = 'idle'
      cancelFn = null
    }

    // Brief delay so user sees "网页处理中..."
    await new Promise(r => setTimeout(r, 300))

    try {
      if (status.value !== 'preparing') return // was cancelled during delay
      status.value = 'ai-processing'
      const res = await api.post<TranslateResponse>('/translate', {
        sourceText: sourceText.value,
        model: model.value,
        modelProfileId: modelProfileId.value,
        customPrompt: customPrompt.value || undefined,
        presets: selectedPresets.value.length > 0 ? selectedPresets.value : undefined,
        targetLanguage: targetLanguage.value
      }, { signal: controller.signal })
      result.value = res.data
    } catch (e: unknown) {
      if (axios.isCancel(e) || (e instanceof DOMException && e.name === 'AbortError')) return
      const err = e as { response?: { data?: { error?: string } } }
      error.value = err.response?.data?.error || t('translate.errors.translateFailed')
    } finally {
      status.value = 'idle'
      cancelFn = null
    }
  }
}
</script>

<style scoped>
.translate-status {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-top: 10px;
  font-size: 13px;
  color: var(--color-muted, #666);
}

.translate-status::before {
  content: '';
  flex: 0 0 auto;
  width: 11px;
  height: 11px;
  border: 2px solid #dee2e6;
  border-top-color: #1971c2;
  border-radius: 50%;
  animation: translate-spin 0.8s linear infinite;
}

@keyframes translate-spin {
  to { transform: rotate(360deg); }
}

.translate-layout {
  display: grid;
  grid-template-columns: minmax(0, 800px);
  justify-content: center;
  align-items: start;
  gap: 16px;
  width: 100%;
}

.translate-layout.has-announcements {
  grid-template-columns: minmax(0, 800px) minmax(180px, 240px);
}

.verify-gate {
  max-width: 720px;
  margin: 0 auto;
  text-align: center;
}

.verify-desc {
  color: var(--color-muted, #666);
  margin: 8px 0;
}

.translation-card {
  grid-column: 1;
  grid-row: 1;
  min-width: 0;
  width: 100%;
  max-width: 800px;
  margin: 0;
}

.announcement-right {
  grid-column: 2;
  grid-row: 1;
}

.open-source-banner {
  margin: -4px 0 16px;
  padding-bottom: 12px;
  border-bottom: 1px solid var(--color-border);
  color: var(--color-muted);
  font-size: 13px;
}

.open-source-banner a {
  color: var(--color-text);
  font-weight: 500;
  text-decoration: underline;
  text-underline-offset: 2px;
}

.open-source-banner a:hover {
  color: var(--color-muted);
}

.source-wrap {
  position: relative;
  margin-top: 12px;
}

.source-wrap--active::after {
  content: '';
  position: absolute;
  inset: 0;
  border: 2px dashed #4a9eff;
  border-radius: 8px;
  background: rgba(74, 158, 255, 0.06);
  pointer-events: none;
}

.upload-btn {
  position: absolute;
  right: 8px;
  bottom: 8px;
  padding: 2px 10px;
  font-size: 13px;
  opacity: 0.75;
}

.upload-btn:hover {
  opacity: 1;
}

textarea {
  resize: vertical;
  width: 100%;
  box-sizing: border-box;
}

@media (max-width: 720px) {
  .translate-layout.has-announcements {
    grid-template-columns: minmax(0, 1fr);
  }

  .announcement-right {
    grid-column: 1;
    grid-row: 1;
  }

  .translate-layout.has-announcements .translation-card {
    grid-column: 1;
    grid-row: 2;
  }
}

</style>
