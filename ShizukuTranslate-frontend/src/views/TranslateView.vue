<template>
  <div class="translate-layout">
    <AnnouncementPanel class="announcement-left" :announcements="leftAnnouncements" />

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
      <select v-model="model" style="width:auto; min-width:200px;">
        <option value="deepseek-v4-flash">deepseek-v4-flash</option>
        <option value="deepseek-v4-pro">deepseek-v4-pro</option>
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

    <AnnouncementPanel class="announcement-right" :announcements="rightAnnouncements" />
  </div>
</template>

<script setup lang="ts">
import { computed, ref, onMounted } from 'vue'
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
const customPrompt = ref('')
const selectedPresets = ref<string[]>([])
const presetOptions = ref<string[]>([])
const announcements = ref<Announcement[]>([])
const leftAnnouncements = computed(() => announcements.value.filter((_, index) => index % 2 === 0))
const rightAnnouncements = computed(() => announcements.value.filter((_, index) => index % 2 === 1))

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
    const res = await api.get('/announcements')
    announcements.value = res.data || []
  } catch (e) {
    console.error('无法加载公告列表', e)
  }
})

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
  grid-template-columns: minmax(0, 180px) minmax(0, 800px) minmax(0, 180px);
  align-items: start;
  justify-content: center;
  gap: 16px;
}

.announcement-left,
.announcement-right {
  grid-row: 1;
}

.translation-card {
  grid-column: 2;
  margin: 0;
  width: 100%;
}

textarea {
  resize: vertical;
  width: 100%;
  box-sizing: border-box;
}

@media (max-width: 1050px) {
  .translate-layout {
    grid-template-columns: minmax(0, 800px);
  }

  .announcement-left,
  .announcement-right,
  .translation-card {
    grid-column: 1;
    grid-row: auto;
    width: 100%;
    max-width: 800px;
    margin: 0 auto;
  }

  .announcement-left {
    grid-row: 1;
  }

  .translation-card {
    grid-row: 2;
  }

  .announcement-right {
    grid-row: 3;
  }
}
</style>
