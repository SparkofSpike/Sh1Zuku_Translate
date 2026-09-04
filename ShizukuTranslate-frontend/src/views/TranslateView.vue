<template>
  <div v-if="authStore.emailVerified === false" class="card verify-gate">
    <h2 style="margin-top:0; font-weight:600;">邮箱尚未认证</h2>
    <p class="verify-desc">为了保障服务稳定、防止账号被滥用，使用翻译功能前需要先完成邮箱认证。</p>
    <p class="verify-desc">认证只需要一分钟，不会影响你的历史记录与模型配置；浏览器插件同样需要账号完成邮箱认证后才能使用。</p>
    <router-link to="/profile" style="text-decoration:none;">
      <button style="margin-top:8px;">去「个人」页面认证邮箱</button>
    </router-link>
  </div>
  <div v-else class="translate-layout" :class="{ 'has-announcements': announcements.length > 0 }">

    <div class="card translation-card">
      <div class="open-source-banner">
        项目已开源：<a
          href="https://github.com/SparkofSpike/Sh1Zuku_Translate"
          target="_blank"
          rel="noopener noreferrer"
        >Sh1Zuku_Translate</a>（https://github.com/SparkofSpike/Sh1Zuku_Translate），你们的star和follow是我更新的动力！
        <br />浏览器插件正在锐意研发中，预计九月初正式可用……
      </div>

      <h2 style="margin-top:0; font-weight:600;">小说翻译</h2>

    <OcrPreview
      v-if="ocrPreview"
      :preview="ocrPreview"
      :loading="ocrLoading"
      :polish="ocrPolish"
      :threshold="ocrThreshold"
      @ocr="doOcr"
      @clear="clearOcr"
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
        placeholder="粘贴原文，或拖入 TXT / MD / 图片，也可点击右下角按钮上传..."
        rows="10"
        @paste="onTextareaPaste"
      ></textarea>
      <button class="upload-btn" type="button" title="上传图片 / TXT / MD" @click="fileInput?.click()">📎 上传</button>
      <input
        ref="fileInput"
        type="file"
        accept="image/*,.txt,.md,text/plain,text/markdown"
        style="display:none"
        @change="onPickFile"
      />
    </div>

    <div style="display:flex; gap:16px; flex-wrap:wrap; margin-top:16px;">
      <select v-model="selectedModelKey" @change="handleModelChange" style="width:auto; min-width:240px;">
        <option v-for="option in modelOptions" :key="option.key" :value="option.key">{{ option.label }}</option>
      </select>
      <label style="display:flex; align-items:center; gap:4px; cursor:pointer; font-size:14px;">
        <input type="checkbox" v-model="streamingEnabled" />
        流式输出
      </label>
      <label v-if="pendingImageFile" style="display:flex; align-items:center; gap:4px; cursor:pointer; font-size:14px;">
        图片处理：
        <select v-model="imageProcessingMode" style="width:auto;">
          <option value="model">模型处理</option>
          <option value="ocr">OCR处理</option>
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
      placeholder="自定义附加Prompt（可选）"
      rows="3"
      style="margin-top:16px;"
    ></textarea>

    <button
      @click="status === 'idle' ? translate() : cancel()"
      :disabled="false"
      :style="{
        marginTop: '16px',
        background: status === 'preparing' ? '#e03131' : (status === 'ai-processing' ? '#1971c2' : undefined),
        borderColor: status === 'preparing' ? '#e03131' : (status === 'ai-processing' ? '#1971c2' : undefined),
      }"
    >
      <template v-if="status === 'preparing'">网页处理中</template>
      <template v-else-if="status === 'ai-processing'">AI 处理中</template>
      <template v-else>开始翻译</template>
    </button>

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
import { ref, onMounted, onUnmounted } from 'vue'
import axios from 'axios'
import api, { ocrImage, translateImage, translateStream } from '../api'
import type { Announcement, TranslateResponse } from '../types'
import OcrPreview from '../components/OcrPreview.vue'
import PresetSelector from '../components/PresetSelector.vue'
import TranslateResult from '../components/TranslateResult.vue'
import SseTranslateResult from '../components/SseTranslateResult.vue'
import AnnouncementPanel from '../components/AnnouncementPanel.vue'
import { useAuthStore } from '../stores/auth'

const authStore = useAuthStore()
const sourceText = ref('')
const model = ref('deepseek-v4-flash')
const modelProfileId = ref<number | null>(readSelectedProfileId())
const selectedModelKey = ref(localStorage.getItem('modelSelection') || (modelProfileId.value ? `profile:${modelProfileId.value}` : 'site:deepseek-v4-flash'))
const modelOptions = ref([
  { key: 'site:deepseek-v4-flash', id: null as number | null, model: 'deepseek-v4-flash', label: '站方/deepseek-v4-flash' },
  { key: 'site:deepseek-v4-pro', id: null as number | null, model: 'deepseek-v4-pro', label: '站方/deepseek-v4-pro' },
  { key: 'site:deepseek-v4-flash-vision-exp', id: null as number | null, model: 'deepseek-v4-flash-vision-exp', label: '站方/deepseek-v4-flash-vision-exp' }
])
const customPrompt = ref('')
const selectedPresets = ref<string[]>([])
const presetOptions = ref<string[]>([])
const announcements = ref<Announcement[]>([])

const result = ref<TranslateResponse | null>(null)
const error = ref('')

const status = ref<'idle' | 'preparing' | 'ai-processing'>('idle')

// SSE streaming
const streamingEnabled = ref(true)
const useStreaming = ref(false)
const streamingText = ref('')
const streamingResult = ref<TranslateResponse | null>(null)

// Cancel
let cancelFn: (() => void) | null = null

// OCR related
const ocrPreview = ref<string | null>(null)
const ocrLoading = ref(false)
const ocrError = ref('')
const ocrPolish = ref(false)
const ocrThreshold = ref(0.3)
const pendingOcrFile = ref<File | null>(null)
const pendingImageFile = ref<File | null>(null)
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

onMounted(async () => {
  try {
    const me = await api.get('/auth/me')
    authStore.setAdmin(!!me.data.isAdmin)
    authStore.setEmailVerified(!!me.data.emailVerified)
  } catch (e) {
    console.error('无法获取认证状态', e)
  }
  try {
    const res = await api.get('/presets')
    presetOptions.value = res.data || []
  } catch (e) {
    console.error('无法加载预设列表', e)
  }
  try {
    const res = await api.get('/auth/model-profiles')
    const profiles = res.data || []
    modelOptions.value = [
      { key: 'site:deepseek-v4-flash', id: null, model: 'deepseek-v4-flash', label: '站方/deepseek-v4-flash' },
      { key: 'site:deepseek-v4-pro', id: null, model: 'deepseek-v4-pro', label: '站方/deepseek-v4-pro' },
      { key: 'site:deepseek-v4-flash-vision-exp', id: null, model: 'deepseek-v4-flash-vision-exp', label: '站方/deepseek-v4-flash-vision-exp' },
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
    const storedSelection = localStorage.getItem('modelSelection') || ''
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
  } catch (e) {
    console.error('无法加载个人模型配置', e)
  }
  try {
    const res = await api.get('/announcements')
    announcements.value = res.data || []
  } catch (e) {
    console.error('无法加载公告列表', e)
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
  const file = target.files?.[0]
  if (file) handleAttachment(file)
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
  const file = e.dataTransfer?.files?.[0]
  if (file) handleAttachment(file)
}

function onTextareaPaste(e: ClipboardEvent) {
  const items = e.clipboardData?.items
  if (!items) return
  for (const item of items) {
    if (item.type.startsWith('image/')) {
      const file = item.getAsFile()
      if (file) {
        e.preventDefault()
        handleAttachment(file)
        return
      }
    }
  }
}

function handleAttachment(file: File) {
  if (file.name.toLowerCase().endsWith('.txt') || file.name.toLowerCase().endsWith('.md')) {
    const reader = new FileReader()
    reader.onload = () => { sourceText.value = String(reader.result || '') }
    reader.readAsText(file)
    return
  }
  handleImageFile(file)
}

function handleImageFile(file: File) {
  ocrError.value = ''
  const reader = new FileReader()
  reader.onload = (e) => {
    ocrPreview.value = e.target?.result as string
  }
  reader.readAsDataURL(file)
  pendingOcrFile.value = file
  pendingImageFile.value = file
}

function clearOcr() {
  ocrPreview.value = null
  pendingOcrFile.value = null
  pendingImageFile.value = null
  ocrError.value = ''
}

async function doOcr() {
  if (!pendingOcrFile.value) return
  ocrLoading.value = true
  ocrError.value = ''
  try {
    const res = await ocrImage(pendingOcrFile.value, ocrPolish.value, ocrThreshold.value)
    const text = res.data.text
    if (text) {
      sourceText.value = text
      clearOcr()
    } else {
      ocrError.value = '未识别到文字'
    }
  } catch (e: any) {
    const msg = e.response?.data?.error || e.message || 'OCR 请求失败'
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
  if (!sourceText.value.trim() && !pendingImageFile.value) return
  if (pendingImageFile.value && imageProcessingMode.value === 'model') {
    try {
      const request = { sourceText: sourceText.value, model: model.value, modelProfileId: modelProfileId.value,
        customPrompt: customPrompt.value || undefined, presets: selectedPresets.value.length ? selectedPresets.value : undefined }
      const response = await translateImage(pendingImageFile.value, request)
      result.value = response.data
      useStreaming.value = false
      clearOcr()
      return
    } catch (e: any) {
      error.value = e.response?.data?.error || e.message || '图片模型处理失败'
      return
    }
  }
  if (pendingImageFile.value && imageProcessingMode.value === 'ocr') {
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
        presets: selectedPresets.value.length > 0 ? selectedPresets.value : undefined
      }, { signal: controller.signal })
      result.value = res.data
    } catch (e: unknown) {
      if (axios.isCancel(e) || (e instanceof DOMException && e.name === 'AbortError')) return
      const err = e as { response?: { data?: { error?: string } } }
      error.value = err.response?.data?.error || '翻译失败'
    } finally {
      status.value = 'idle'
      cancelFn = null
    }
  }
}
</script>

<style scoped>
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
