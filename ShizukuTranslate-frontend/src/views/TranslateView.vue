<template>
  <div class="card">
    <h2 style="margin-top:0; font-weight:600;">图片处理</h2>

    <!-- ====== 图片拖拽/粘贴上传区 ====== -->
    <ImageUploader @file-selected="handleImageFile" />

    <!-- ====== OCR 预览 ====== -->
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

    <!-- ====== 小说翻译 ====== -->
    <h2 style="margin-top:24px; font-weight:600;">小说翻译</h2>

    <textarea
      v-model="sourceText"
      placeholder="粘贴原文，或通过上方图片上传识别文字..."
      rows="10"
      style="margin-top:12px;"
    ></textarea>

    <div style="display:flex; gap:16px; flex-wrap:wrap; margin-top:16px;">
      <select v-model="model" style="width: auto; min-width: 200px;">
        <option value="deepseek-v4-flash">deepseek-v4-flash</option>
        <option value="deepseek-v4-pro">deepseek-v4-pro</option>
      </select>
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

    <button @click="translate" :disabled="loading || !sourceText.trim()" style="margin-top:16px;">
      {{ loading ? '翻译中...' : '开始翻译' }}
    </button>

    <p v-if="error" style="color:#e03131; margin-top:12px;">{{ error }}</p>

    <!-- ====== 翻译结果 ====== -->
    <TranslateResult v-if="result" :result="result" />
  </div>
</template>

<script setup lang="ts">
import { ref, onMounted } from 'vue'
import api, { ocrImage } from '../api'
import type { TranslateResponse } from '../types'
import ImageUploader from '../components/ImageUploader.vue'
import OcrPreview from '../components/OcrPreview.vue'
import PresetSelector from '../components/PresetSelector.vue'
import TranslateResult from '../components/TranslateResult.vue'

const sourceText = ref('')
const model = ref('deepseek-v4-flash')
const customPrompt = ref('')
const selectedPresets = ref<string[]>([])
const presetOptions = ref<string[]>([])

const result = ref<TranslateResponse | null>(null)
const loading = ref(false)
const error = ref('')

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

async function translate() {
  if (!sourceText.value.trim()) return
  loading.value = true
  error.value = ''
  try {
    const res = await api.post<TranslateResponse>('/translate', {
      sourceText: sourceText.value,
      model: model.value,
      customPrompt: customPrompt.value || undefined,
      presets: selectedPresets.value.length > 0 ? selectedPresets.value : undefined
    })
    result.value = res.data
  } catch (e: any) {
    error.value = e.response?.data?.error || '翻译失败'
  } finally {
    loading.value = false
  }
}
</script>

<style scoped>
textarea {
  resize: vertical;
  width: 100%;
  box-sizing: border-box;
}
</style>
