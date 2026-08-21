<template>
  <div class="translate-layout" :class="{ 'has-announcements': announcements.length > 0 }">

    <div class="card translation-card">
      <h2 style="margin-top:0; font-weight:600;">图片处理</h2>

    <ImageUploader @file-selected="handleImageFile" />

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

    <h2 style="margin-top:24px; font-weight:600;">小说翻译</h2>

    <textarea
      v-model="sourceText"
      placeholder="粘贴原文，或通过上方图片上传识别文字..."
      rows="10"
      style="margin-top:12px;"
    ></textarea>

    <div style="display:flex; gap:16px; flex-wrap:wrap; margin-top:16px;">
      <select v-model="selectedModelKey" @change="handleModelChange" style="width:auto; min-width:240px;">
        <option v-for="option in modelOptions" :key="option.key" :value="option.key">{{ option.label }}</option>
      </select>
      <label style="display:flex; align-items:center; gap:4px; cursor:pointer; font-size:14px;">
        <input type="checkbox" v-model="streamingEnabled" />
        流式输出
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
import { ref, onMounted } from 'vue'
import axios from 'axios'
import api, { ocrImage, translateStream } from '../api'
import type { Announcement, TranslateResponse } from '../types'
import ImageUploader from '../components/ImageUploader.vue'
import OcrPreview from '../components/OcrPreview.vue'
import PresetSelector from '../components/PresetSelector.vue'
import TranslateResult from '../components/TranslateResult.vue'
import SseTranslateResult from '../components/SseTranslateResult.vue'
import AnnouncementPanel from '../components/AnnouncementPanel.vue'

const sourceText = ref('')
const model = ref('deepseek-v4-flash')
const modelProfileId = ref<number | null>(readSelectedProfileId())
const selectedModelKey = ref(localStorage.getItem('modelSelection') || (modelProfileId.value ? `profile:${modelProfileId.value}` : 'site:deepseek-v4-flash'))
const modelOptions = ref([
  { key: 'site:deepseek-v4-flash', id: null as number | null, model: 'deepseek-v4-flash', label: '站方 DeepSeek · deepseek-v4-flash' },
  { key: 'site:deepseek-v4-pro', id: null as number | null, model: 'deepseek-v4-pro', label: '站方 DeepSeek · deepseek-v4-pro' }
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

onMounted(async () => {
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
      { key: 'site:deepseek-v4-flash', id: null, model: 'deepseek-v4-flash', label: '站方 DeepSeek · deepseek-v4-flash' },
      { key: 'site:deepseek-v4-pro', id: null, model: 'deepseek-v4-pro', label: '站方 DeepSeek · deepseek-v4-pro' },
      ...profiles.map((item: any) => ({
        key: `profile:${item.id}`,
        id: item.id,
        model: item.model,
        label: `${item.name} · ${item.model}`
      }))
    ]
    const storedProfileId = modelProfileId.value && modelProfileId.value > 0
      ? modelProfileId.value
      : null
    const storedProfileKey = storedProfileId ? 'profile:' + storedProfileId : ''
    const storedSelection = localStorage.getItem('modelSelection') || ''
    const profileSelectionIsValid = storedProfileKey
      && modelOptions.value.some(option => option.key === storedProfileKey)
    const savedSelectionIsValid = modelOptions.value.some(option => option.key === storedSelection)

    // A profile ID is more authoritative than the legacy display-mode key.
    // This recovers users whose old localStorage still says "site" after
    // they configured a personal model profile.
    if (profileSelectionIsValid) {
      selectedModelKey.value = storedProfileKey
    } else if (savedSelectionIsValid) {
      selectedModelKey.value = storedSelection
    } else if (profiles.length) {
      selectedModelKey.value = 'profile:' + profiles[0].id
    } else {
      selectedModelKey.value = 'site:deepseek-v4-flash'
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

function handleImageFile(file: File) {
  ocrError.value = ''
  const reader = new FileReader()
  reader.onload = (e) => {
    ocrPreview.value = e.target?.result as string
  }
  reader.readAsDataURL(file)
  pendingOcrFile.value = file
}

function clearOcr() {
  ocrPreview.value = null
  pendingOcrFile.value = null
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
  if (!sourceText.value.trim()) return
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

    const source = axios.CancelToken.source()
    cancelFn = () => {
      source.cancel('用户取消')
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
      }, { cancelToken: source.token })
      result.value = res.data
    } catch (e: any) {
      if (axios.isCancel(e)) return
      error.value = e.response?.data?.error || '翻译失败'
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
