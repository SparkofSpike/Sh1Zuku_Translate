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

    <!-- Page-top notice, then the import panel, then the translate card: the page reads
         notice → import → translate, top to bottom. -->
    <div class="card open-source-banner">
      {{ t('translate.openSource.lead') }}<a
        href="https://github.com/SparkofSpike/Sh1Zuku_Translate"
        target="_blank"
        rel="noopener noreferrer"
      >Sh1Zuku_Translate</a>{{ t('translate.openSource.tail') }}
      <br />{{ t('translate.openSource.plugin') }}
    </div>

    <PixivImportPanel
      class="pixiv-panel"
      :include-metadata="includePixivMetadata"
      :model="model"
      :model-profile-id="modelProfileId"
      :custom-prompt="customPrompt"
      :presets="selectedPresets"
      :target-language="targetLanguage"
      :busy="status !== 'idle'"
      @update:include-metadata="includePixivMetadata = $event"
      @imported="onPixivImported"
      @cleared="onPixivCleared"
    />

    <div class="card translation-card">

      <h2 style="margin-top:0; font-weight:600;">{{ t('translate.heading') }}</h2>

    <ImagePreview
      v-if="imagePreviews.length"
      :previews="imagePreviews"
      @clear="clearImages"
      @remove="removeImageAt"
    />

    <p v-if="imageError" style="color:#e03131; margin-top:8px; font-size:14px;">{{ imageError }}</p>

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
      <label style="display:flex; align-items:center; gap:4px; cursor:pointer; font-size:14px;">
        <input type="checkbox" v-model="novelTermFix" />
        {{ t('translate.termFix.label') }}
      </label>
      <label style="display:flex; align-items:center; gap:4px; cursor:pointer; font-size:14px;">
        <input type="checkbox" v-model="thinkingEnabled" />
        {{ t('translate.thinking.label') }}
      </label>
    </div>
    <p v-if="novelTermFix" class="term-fix-hint">{{ t('translate.termFix.hint') }}</p>
    <p v-if="thinkingEnabled" class="term-fix-hint">{{ t('translate.thinking.hint') }}</p>

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

    <div style="display:flex; gap:8px; flex-wrap:wrap;">
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
      <button
        v-if="status === 'idle' && hasReusableResult"
        @click="translate(true)"
        class="retranslate-btn"
      >
        {{ t('translate.retranslate') }}
      </button>
    </div>
    <p v-if="status === 'idle' && hasReusableResult" class="retranslate-hint">{{ t('translate.retranslateHint') }}</p>

    <p v-if="statusText" class="translate-status">{{ statusText }}</p>
    <p v-if="pipelineText" class="pipeline-status">{{ pipelineText }}</p>

    <p v-if="error" style="color:#e03131; margin-top:12px;">{{ error }}</p>

    <SseTranslateResult v-if="useStreaming" :streaming-text="streamingText" :result="streamingResult" :model="model" />
    <TranslateResult v-else-if="result" :result="result" :model="model" />
    </div>

    <AnnouncementPanel
      v-if="announcements.length"
      class="announcement-right"
      :announcements="announcements"
    />

    <LongTextNoticeDialog
      :visible="showLongTextDialog"
      :char-count="longTextLength"
      @confirm="onLongTextConfirm"
      @decline="onLongTextDecline"
    />
  </div>
</template>

<script setup lang="ts">
import { computed, ref, watch, onMounted, onUnmounted } from 'vue'
import { useI18n } from 'vue-i18n'
import { useRouter } from 'vue-router'
import axios from 'axios'
import api, { translateImages, translateImagesStream, translateStream } from '../api'
import type { Announcement, LanguageOption, PixivNovelResponse, TokenUsage, TranslateResponse } from '../types'
import ImagePreview from '../components/ImagePreview.vue'
import PixivImportPanel from '../components/PixivImportPanel.vue'
import PresetSelector from '../components/PresetSelector.vue'
import TranslateResult from '../components/TranslateResult.vue'
import SseTranslateResult from '../components/SseTranslateResult.vue'
import AnnouncementPanel from '../components/AnnouncementPanel.vue'
import LongTextNoticeDialog from '../components/LongTextNoticeDialog.vue'
import { useAuthStore } from '../stores/auth'
import { useLongNovelStore } from '../stores/longNovel'

const authStore = useAuthStore()
const longNovelStore = useLongNovelStore()
const { t, locale } = useI18n()
const router = useRouter()
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
  { key: 'site:deepseek-v4-pro', id: null as number | null, model: 'deepseek-v4-pro', label: '站方/deepseek-v4-pro' },
  { key: 'site:index-translate', id: null as number | null, model: 'Index-Translate-35B-A3B', label: '站方/Index-Translate-35B-A3B' }
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

/**
 * 最近一次成功翻译的上下文。打分与埋点全靠 requestId 关联，所以它必须活过刷新：
 * 结果正文可以从 localStorage 回填，重译时则用它填 retranslatedFrom。
 */
interface TranslateContext {
  requestId: string
  sourceText: string
  translatedText: string
  model: string
  tokenUsage?: TokenUsage
  createdAt: string
  fromCache?: boolean
  fromSharedTranslation?: boolean
  targetLanguage: string
  savedAt: number
}

const LAST_REQUEST_ID_KEY = 'lastRequestId'
const LAST_TRANSLATE_CONTEXT_KEY = 'lastTranslateContext'
/** 超过一周的旧结果不再回填：模型、目标语言与站点本身都可能已经变了。 */
const CONTEXT_MAX_AGE_MS = 7 * 24 * 60 * 60 * 1000
/** localStorage 容量有限，正文按约定上限截断后再存。 */
const CONTEXT_SOURCE_LIMIT = 4000
const CONTEXT_TRANSLATED_LIMIT = 200000

/** 当前结果（含刚回填的结果）的 requestId：重译时作为 retranslatedFrom 上报。 */
const currentRequestId = ref('')

/**
 * 记录最近一次成功翻译的上下文。整段用 try/catch 包住：localStorage 写满或被禁用时静默放弃，
 * 不能影响已经拿到的翻译结果。
 */
function rememberTranslateContext(response: TranslateResponse) {
  if (!response?.requestId) return
  currentRequestId.value = response.requestId
  try {
    const savedAt = Date.now()
    localStorage.setItem(LAST_REQUEST_ID_KEY, JSON.stringify({ requestId: response.requestId, savedAt }))
    const context: TranslateContext = {
      requestId: response.requestId,
      sourceText: effectiveSourceText().slice(0, CONTEXT_SOURCE_LIMIT),
      translatedText: (response.translatedText || '').slice(0, CONTEXT_TRANSLATED_LIMIT),
      model: response.model,
      tokenUsage: response.tokenUsage,
      createdAt: response.createdAt,
      fromCache: response.fromCache,
      fromSharedTranslation: response.fromSharedTranslation,
      targetLanguage: targetLanguage.value,
      savedAt
    }
    localStorage.setItem(LAST_TRANSLATE_CONTEXT_KEY, JSON.stringify(context))
  } catch (e) {
    console.debug('无法保存上次翻译上下文', e)
  }
}

/**
 * 刷新后回填上一次的结果：翻译结果本身无法重算，但打分 UI 必须仍然可用，
 * requestId 也要继续对得上（否则刷新即丢失反馈入口）。
 */
function restoreLastTranslateContext() {
  try {
    const raw = localStorage.getItem(LAST_TRANSLATE_CONTEXT_KEY)
    if (!raw) return
    const saved = JSON.parse(raw) as Partial<TranslateContext> | null
    if (!saved?.requestId || !saved.translatedText) return
    if (!saved.savedAt || Date.now() - saved.savedAt > CONTEXT_MAX_AGE_MS) return
    currentRequestId.value = saved.requestId
    result.value = {
      id: undefined as unknown as number,
      translatedText: saved.translatedText,
      model: saved.model || '',
      createdAt: saved.createdAt || '',
      tokenUsage: saved.tokenUsage,
      fromSharedTranslation: saved.fromSharedTranslation,
      fromCache: saved.fromCache,
      requestId: saved.requestId
    }
    useStreaming.value = false
    status.value = 'idle'
    hasReusableResult.value = false
  } catch (e) {
    // 旧格式或损坏的 JSON：当作没有历史结果，不影响其它初始化。
    console.debug('无法恢复上次翻译上下文', e)
  }
}

/**
 * True while the finished result on screen came from someone else's translation or the
 * personal cache — the two cases where "re-translate" adds something the start button alone
 * cannot express. Cleared whenever a new translation starts or the source text changes.
 */
const hasReusableResult = ref(false)

const status = ref<'idle' | 'preparing' | 'ai-processing'>('idle')

/**
 * Whether the request currently in flight is an image upload. Only the status wording depends
 * on it: an image request spends the preparing phase uploading the files, not waiting on the
 * server to answer.
 */
const imageRequestInFlight = ref(false)

/**
 * Spelled out next to the button: the button itself carries the action (cancel while a
 * translation is in flight), while this line says what is actually happening. Mirrors
 * the status message the browser extension shows in its popup.
 */
const statusText = computed(() => {
  if (status.value === 'preparing') {
    return imageRequestInFlight.value ? t('translate.status.uploadingImages') : t('translate.status.preparing')
  }
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

// Super-long-novel term correction switch (slower; keeps coined terms consistent).
const novelTermFix = ref(false)

// DeepSeek reasoning before translating (slower, higher quality). Feeds `thinkingType` on the
// request; the backend serialises it into its cache key and skips the shared replay for it.
const thinkingEnabled = ref(false)

/**
 * Texts above this many characters don't run in this card:
 * - with the term correction on, they open the focus page (`/long-novel`);
 * - with it off, the user is first asked whether to enable the correction — long novels without
 *   it are exactly where terminology drifts (misspelled names, changed years) shows up.
 */
const LONG_TEXT_THRESHOLD = 30000
const showLongTextDialog = ref(false)
/** Character count shown in the dialog; captured when the gate opens. */
const longTextLength = ref(0)
/** The exact text the user answered "translate anyway" for; a changed text asks again. */
let longTextPromptSkippedFor = ''
/** Whether the gated request was a re-translate; passed through to the focus page. */
let longTextPendingForce = false

/**
 * Hands the current request to the focus page and navigates there. Used when the term
 * correction is already on, and right after the user enables it from the long-text dialog.
 */
function startLongNovel(forceRetranslate: boolean) {
  longNovelStore.start({
    sourceText: effectiveSourceText(),
    model: model.value,
    modelProfileId: modelProfileId.value,
    customPrompt: customPrompt.value || undefined,
    presets: selectedPresets.value.length ? selectedPresets.value : undefined,
    targetLanguage: targetLanguage.value,
    skipCache: forceRetranslate || undefined,
    retranslatedFrom: forceRetranslate && currentRequestId.value ? currentRequestId.value : undefined,
    thinkingType: thinkingEnabled.value ? 'enabled' : undefined
  })
  router.push('/long-novel')
}

function onLongTextConfirm() {
  showLongTextDialog.value = false
  novelTermFix.value = true
  startLongNovel(longTextPendingForce)
}

function onLongTextDecline() {
  showLongTextDialog.value = false
  // Remember the answer for this exact text: pressing start again must not re-ask.
  longTextPromptSkippedFor = effectiveSourceText()
  translate(longTextPendingForce)
}

// Long-novel pipeline progress reported by the backend (extract → translate → audit).
const pipelineStage = ref('')
const pipelineCurrent = ref(0)
const pipelineTotal = ref(0)
const pipelineCount = ref<number | null>(null)

const pipelineText = computed(() => {
  switch (pipelineStage.value) {
    case 'extract':
      return t('translate.pipeline.extractRunning')
    case 'extract-done':
      return t('translate.pipeline.extractDone', { count: pipelineCount.value ?? 0 })
    case 'translate':
      return t('translate.pipeline.translating', { current: pipelineCurrent.value, total: pipelineTotal.value })
    case 'audit':
      return t('translate.pipeline.auditRunning')
    case 'audit-done':
      return (pipelineCount.value ?? 0) > 0
        ? t('translate.pipeline.auditDone', { count: pipelineCount.value })
        : t('translate.pipeline.auditClean')
    case 'audit-skipped':
      return t('translate.pipeline.auditSkipped')
    default:
      return ''
  }
})

// Cancel
let cancelFn: (() => void) | null = null

// Image attachments for the multimodal translation path
const imagePreviews = ref<string[]>([])
const imageError = ref('')
const pendingImageFiles = ref<File[]>([])
/** Mirrors TranslationService.MAX_IMAGES_PER_REQUEST; keep the two in sync. */
const MAX_IMAGES = 10

// Pixiv import lives in its own card (PixivImportPanel); the parent only keeps the metadata of
// the imported work, because it is what the translate request prepends when the toggle is on.
/** Title / author / tags / description of the work imported through the panel. */
const pixivMeta = ref<PixivNovelResponse | null>(null)
/** When on, the imported text is prefixed with the labelled metadata block before translating. */
const includePixivMetadata = ref(false)

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
  // 先回填上一次的翻译结果，让刷新后的打分 UI 与 requestId 关联仍然可用；
  // 这一步只读 localStorage，不参与下面的网络初始化。
  restoreLastTranslateContext()

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
      { key: 'site:index-translate', id: null, model: 'Index-Translate-35B-A3B', label: '站方/Index-Translate-35B-A3B' },
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

// Any input that changes what a re-translation would mean (the text itself, the model or the
// target language) invalidates the "re-translate" affordance until the next completed result.
watch(
  [sourceText, selectedModelKey, customPrompt, selectedPresets, targetLanguage],
  () => { hasReusableResult.value = false },
  { deep: true }
)

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

/**
 * The text a translate request actually sends. When the Pixiv metadata toggle is on, the
 * labelled metadata block (title / author / tags / description) is prepended so the model
 * translates it along with the body in one request.
 */
function effectiveSourceText(): string {
  if (includePixivMetadata.value && pixivMeta.value?.metadataText) {
    return pixivMeta.value.metadataText + '\n\n' + sourceText.value
  }
  return sourceText.value
}

/**
 * Import succeeded in the panel (work link or a candidate picked after screenshot recognition):
 * the novel text replaces the textarea contents, matching the text-file upload behaviour. The
 * metadata toggle stays off, because importing text alone must not silently change the request.
 */
function onPixivImported(novel: PixivNovelResponse) {
  sourceText.value = novel.text
  pixivMeta.value = novel
  includePixivMetadata.value = false
}

/** The panel's own clear button dropped its import state; the parent drops its metadata copy. */
function onPixivCleared() {
  pixivMeta.value = null
  includePixivMetadata.value = false
}

function handleImageFiles(files: File[]) {
  imageError.value = ''
  const room = MAX_IMAGES - pendingImageFiles.value.length
  if (room <= 0) {
    imageError.value = t('translate.errors.tooManyImages', { max: MAX_IMAGES })
    return
  }
  const accepted = files.slice(0, room)
  if (accepted.length < files.length) {
    imageError.value = t('translate.errors.tooManyImagesKept', { max: MAX_IMAGES })
  }
  for (const file of accepted) {
    pendingImageFiles.value.push(file)
    const slot = imagePreviews.value.length
    imagePreviews.value.push('')
    const reader = new FileReader()
    reader.onload = (e) => {
      // Resolve the slot by file identity, so deleting a thumbnail while another
      // read is still in flight cannot shift the previews out of order.
      const index = pendingImageFiles.value.indexOf(file)
      if (index >= 0) imagePreviews.value[index] = e.target?.result as string
      else imagePreviews.value.splice(slot, 1)
    }
    reader.readAsDataURL(file)
  }
}

function removeImageAt(index: number) {
  pendingImageFiles.value.splice(index, 1)
  imagePreviews.value.splice(index, 1)
}

function clearImages() {
  pendingImageFiles.value = []
  imagePreviews.value = []
  imageError.value = ''
}

function cancel() {
  if (cancelFn) cancelFn()
  status.value = 'idle'
  error.value = ''
}

/**
 * Drops only the pages that were just sent. Images the user adds while a request is in flight
 * are kept, together with their preview slots.
 */
function clearSentImages(sent: File[]) {
  if (pendingImageFiles.value.length === sent.length) {
    clearImages()
    return
  }
  const sentSet = new Set(sent)
  const keptFiles: File[] = []
  const keptPreviews: string[] = []
  pendingImageFiles.value.forEach((file, index) => {
    if (!sentSet.has(file)) {
      keptFiles.push(file)
      keptPreviews.push(imagePreviews.value[index] ?? '')
    }
  })
  pendingImageFiles.value = keptFiles
  imagePreviews.value = keptPreviews
}

/**
 * Image translation. Streaming is opt-in like the text path, and both modes drive the same
 * status machine: the button turns into a red cancel action, the status line narrates the
 * phase, and cancelling aborts the upload or the stream. The image path used to run
 * fire-and-forget, so a slow vision call looked like a frozen page.
 */
async function translatePendingImages() {
  const files = [...pendingImageFiles.value]
  const request = {
    sourceText: sourceText.value,
    model: model.value,
    modelProfileId: modelProfileId.value,
    customPrompt: customPrompt.value || undefined,
    presets: selectedPresets.value.length ? selectedPresets.value : undefined,
    targetLanguage: targetLanguage.value
  }

  status.value = 'preparing'
  error.value = ''
  hasReusableResult.value = false
  imageRequestInFlight.value = true

  if (streamingEnabled.value) {
    useStreaming.value = true
    streamingText.value = ''
    streamingResult.value = null
    result.value = null

    const ctrl = translateImagesStream(
      files,
      request,
      (token: string) => {
        if (status.value === 'preparing') status.value = 'ai-processing'
        streamingText.value += token
      },
      (response: TranslateResponse) => {
        streamingResult.value = response
        status.value = 'idle'
        cancelFn = null
        imageRequestInFlight.value = false
        clearSentImages(files)
        rememberTranslateContext(response)
      },
      (err: string) => {
        error.value = err
        status.value = 'idle'
        cancelFn = null
        imageRequestInFlight.value = false
      }
    )

    cancelFn = () => {
      ctrl.abort()
      status.value = 'idle'
      cancelFn = null
      imageRequestInFlight.value = false
    }
    return
  }

  // Non-streaming fallback: the same phases and cancel affordance, one response at the end.
  // The streaming refs are reset here as well, otherwise a previous streaming result would
  // stay on screen while this request runs (and after a cancel).
  useStreaming.value = false
  streamingText.value = ''
  streamingResult.value = null
  result.value = null
  const controller = new AbortController()
  cancelFn = () => {
    controller.abort()
    status.value = 'idle'
    cancelFn = null
    imageRequestInFlight.value = false
  }

  // Brief delay so the upload phase is visible instead of flashing past.
  await new Promise(r => setTimeout(r, 300))

  try {
    if (status.value !== 'preparing') return // was cancelled during the delay
    status.value = 'ai-processing'
    const response = await translateImages(files, request, controller.signal)
    result.value = response.data
    useStreaming.value = false
    clearSentImages(files)
    rememberTranslateContext(response.data)
  } catch (e: unknown) {
    if (axios.isCancel(e) || (e instanceof DOMException && e.name === 'AbortError')) return
    const err = e as { response?: { data?: { error?: string } }; message?: string }
    error.value = err.response?.data?.error || err.message || t('translate.errors.imageModelFailed')
  } finally {
    status.value = 'idle'
    cancelFn = null
    imageRequestInFlight.value = false
  }
}

async function translate(forceRetranslate = false) {
  const requestText = effectiveSourceText()
  if (!requestText.trim() && !pendingImageFiles.value.length) return

  // Long-text gate (text path only; image requests use their own multimodal pipeline).
  if (!pendingImageFiles.value.length && requestText.length > LONG_TEXT_THRESHOLD) {
    if (novelTermFix.value) {
      startLongNovel(forceRetranslate)
      return
    }
    if (longTextPromptSkippedFor !== requestText) {
      longTextPendingForce = forceRetranslate
      longTextLength.value = requestText.length
      showLongTextDialog.value = true
      return
    }
  }

  // 重译埋点：必须在发起前取旧的 requestId（成功响应后 rememberTranslateContext 会覆盖它），
  // 仅在确实存在上一次结果时携带。
  const retranslatedFrom = forceRetranslate && currentRequestId.value ? currentRequestId.value : undefined
  if (pendingImageFiles.value.length) {
    await translatePendingImages()
    return
  }
  imageRequestInFlight.value = false
  status.value = 'preparing'
  error.value = ''
  hasReusableResult.value = false

  // 超长文本名词更正依赖流式管道（提取/审计阶段），勾选它时强制走流式路径。
  if (streamingEnabled.value || novelTermFix.value) {
    useStreaming.value = true
    streamingText.value = ''
    streamingResult.value = null
    result.value = null
    pipelineStage.value = ''
    pipelineCount.value = null

    const ctrl = translateStream(
      requestText,
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
        // The audit stage may repair terminology after tokens were streamed; show the final text.
        if (response.translatedText) streamingText.value = response.translatedText
        // The pipeline strip only reports progress while it runs; hide it once finished.
        pipelineStage.value = ''
        pipelineCount.value = null
        streamingResult.value = response
        hasReusableResult.value = true
        status.value = 'idle'
        cancelFn = null
        rememberTranslateContext(response)
      },
      (err: string) => {
        error.value = err
        status.value = 'idle'
        cancelFn = null
      },
      forceRetranslate,
      retranslatedFrom,
      novelTermFix.value,
      thinkingEnabled.value ? 'enabled' : undefined,
      (s) => {
        if (!s.stage) return
        pipelineStage.value = s.stage
        pipelineCurrent.value = s.current ?? 0
        pipelineTotal.value = s.total ?? 0
        pipelineCount.value = s.count ?? null
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
        sourceText: requestText,
        model: model.value,
        modelProfileId: modelProfileId.value,
        customPrompt: customPrompt.value || undefined,
        presets: selectedPresets.value.length > 0 ? selectedPresets.value : undefined,
        targetLanguage: targetLanguage.value,
        skipCache: forceRetranslate || undefined,
        retranslatedFrom,
        thinkingType: thinkingEnabled.value ? 'enabled' : undefined
      }, { signal: controller.signal })
      result.value = res.data
      hasReusableResult.value = true
      rememberTranslateContext(res.data)
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
.term-fix-hint {
  margin-top: 6px;
  color: #8a6d00;
  font-size: 12px;
}

.pipeline-status {
  margin-top: 10px;
  color: #1864ab;
  font-size: 13px;
}

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

.retranslate-btn {
  margin-top: 16px;
  background: #f0f0f0;
  color: #1a1a1a;
  border: 1px solid #ccc;
}

.retranslate-btn:hover {
  background: #e6e6e6;
}

.retranslate-hint {
  margin-top: 6px;
  font-size: 12px;
  color: var(--color-muted, #666);
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

/* The Pixiv import panel is a card of its own, rendered below the translate card; the
   announcements column simply spans both rows so the sidebar starts at the top of the page. */
.pixiv-panel {
  grid-column: 1;
  grid-row: 2;
  width: 100%;
  max-width: 800px;
  margin: 0;
}

.translation-card {
  grid-column: 1;
  grid-row: 3;
  min-width: 0;
  width: 100%;
  max-width: 800px;
  margin: 0;
}

.announcement-right {
  grid-column: 2;
  grid-row: 1 / span 3;
}

/* Page-top notice: an independent card above the import panel and the translate card. */
.open-source-banner {
  grid-column: 1;
  grid-row: 1;
  width: 100%;
  max-width: 800px;
  margin: 0;
  padding: 14px 20px;
  box-sizing: border-box;
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

  .translate-layout.has-announcements .open-source-banner {
    grid-column: 1;
    grid-row: 2;
  }

  .translate-layout.has-announcements .pixiv-panel {
    grid-column: 1;
    grid-row: 3;
  }

  .translate-layout.has-announcements .translation-card {
    grid-column: 1;
    grid-row: 4;
  }
}

</style>
