<template>
  <div class="card">
    <h2 style="margin-top:0; font-weight:600;">图片处理</h2>

    <!-- ====== 图片拖拽/粘贴上传区 ====== -->
    <div
      class="drop-zone"
      :class="{ 'drop-zone--active': dragging, 'drop-zone--has-image': ocrPreview }"
      @dragenter.prevent="dragging = true"
      @dragover.prevent="dragging = true"
      @dragleave.prevent="dragging = false"
      @drop.prevent="onDrop"
      @paste.prevent="onPaste"
      tabindex="0"
    >
      <input
        ref="fileInput"
        type="file"
        accept="image/*"
        style="display:none"
        @change="onFileSelected"
      />

      <template v-if="ocrPreview">
        <!-- 图片预览 -->
        <div class="ocr-preview">
          <img :src="ocrPreview" alt="上传的图片" />
          <div class="ocr-preview-actions">
            <button class="btn-sm btn-remove" @click="clearOcr">移除</button>
            <button class="btn-sm btn-primary" @click="doOcr" :disabled="ocrLoading">
              {{ ocrLoading ? '识别中...' : 'PaddleOCR' }}
            </button>
          </div>
          <label style="margin-top:8px;display:inline-flex;align-items:center;gap:6px;font-size:13px;cursor:pointer;">
            <input type="checkbox" v-model="ocrPolish">
            修复分段
          </label>
          <div style="margin-top:8px;display:flex;align-items:center;gap:8px;font-size:13px;">
            <span>置信度:</span>
            <input type="range" min="0.1" max="0.9" step="0.05" v-model.number="ocrThreshold" style="width:100px;">
            <input type="number" min="0.1" max="0.9" step="0.05" v-model.number="ocrThreshold" style="width:55px;padding:2px 4px;border:1px solid #ccc;border-radius:4px;font-size:13px;">
          </div>
        </div>
      </template>
      <template v-else>
        <!-- 空状态提示 -->
        <div class="drop-zone-hint">
          <p style="font-size:40px; margin:0; font-weight:200; color:#aaa;">+</p>
          <p>拖拽图片到此处，或 <a @click="clickFileInput">点击选择文件</a></p>
          <p style="font-size:13px; color:#999;">支持竖排日文小说截图，Ctrl+V 粘贴也可</p>
        </div>
      </template>
    </div>

    <!-- OCR 错误提示 -->
    <p v-if="ocrError" style="color:#e03131; margin-top:8px; font-size:14px;">{{ ocrError }}</p>

    <!-- ====== 小说翻译 ====== -->
    <h2 style="margin-top:24px; font-weight:600;">小说翻译</h2>

    <!-- ====== 文本输入区 ====== -->
    <textarea
      v-model="sourceText"
      placeholder="粘贴原文，或通过上方图片上传识别文字..."
      rows="10"
      style="margin-top:12px;"
    ></textarea>

    <!-- ====== 翻译设置 ====== -->
    <div style="display:flex; gap:16px; flex-wrap:wrap; margin-top:16px;">
      <select v-model="model" style="width: auto; min-width: 200px;">
        <option value="deepseek-v4-flash">deepseek-v4-flash</option>
        <option value="deepseek-v4-pro">deepseek-v4-pro</option>
      </select>
    </div>

    <div v-if="presetOptions.length" style="margin-top: 16px;">
      <p style="margin-bottom: 6px; font-size:14px; color:#555;">附加预设（可多选）</p>
      <div class="presets-group">
        <label v-for="preset in presetOptions" :key="preset">
          <input type="checkbox" :value="preset" v-model="selectedPresets" />
          {{ preset }}
        </label>
      </div>
    </div>

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
    <div v-if="result" style="margin-top: 24px;">
      <div style="display:flex; align-items:center; gap:12px;">
        <h3 style="margin:0; font-weight:600;">翻译结果</h3>
        <button @click="copyResult" style="background:#f0f0f0; color:#1a1a1a; padding:6px 14px; border:1px solid #ccc; border-radius:6px; cursor:pointer;">复制</button>
      </div>
      <pre style="margin-top: 12px; white-space: pre-wrap;">{{ result.translatedText }}</pre>

      <div v-if="result.tokenUsage" style="margin-top: 12px; color:#777; font-size:13px;">
        Token 用量：输入 {{ result.tokenUsage.promptTokens }} | 输出 {{ result.tokenUsage.completionTokens }} | 合计 {{ result.tokenUsage.totalTokens }}
      </div>
    </div>
  </div>
</template>

<script setup>
import { ref, onMounted } from 'vue'
import api, { ocrImage } from '../api'

const sourceText = ref('')
const model = ref('deepseek-v4-flash')
const customPrompt = ref('')
const selectedPresets = ref([])
const presetOptions = ref([])

const result = ref(null)
const loading = ref(false)
const error = ref('')

// OCR 相关状态
const fileInput = ref(null)
const ocrPreview = ref(null)   // 图片预览的 data URL
const ocrLoading = ref(false)
const ocrError = ref('')
const ocrPolish = ref(false)  // fix formatting with DeepSeek
const ocrThreshold = ref(0.3)  // OCR confidence threshold
const dragging = ref(false)

onMounted(async () => {
  try {
    const res = await api.get('/presets')
    presetOptions.value = res.data || []
  } catch (e) {
    console.error('无法加载预设列表', e)
  }
})

// ====== 图片上传 ======
function clickFileInput() {
  fileInput.value?.click()
}

function onFileSelected(e) {
  const file = e.target.files?.[0]
  if (file) handleImageFile(file)
}

function onDrop(e) {
  dragging.value = false
  const file = e.dataTransfer?.files?.[0]
  if (file && file.type.startsWith('image/')) {
    handleImageFile(file)
  }
}

function onPaste(e) {
  const items = e.clipboardData?.items
  if (!items) return
  for (const item of items) {
    if (item.type.startsWith('image/')) {
      const file = item.getAsFile()
      if (file) handleImageFile(file)
      break
    }
  }
}

function handleImageFile(file) {
  ocrError.value = ''

  // 生成预览
  const reader = new FileReader()
  reader.onload = (e) => {
    ocrPreview.value = e.target.result
  }
  reader.readAsDataURL(file)

  // 存下来给 OCR 用
  pendingOcrFile.value = file
}

// 暂存待 OCR 的文件
const pendingOcrFile = ref(null)

function clearOcr() {
  ocrPreview.value = null
  pendingOcrFile.value = null
  ocrError.value = ''
  if (fileInput.value) fileInput.value.value = ''
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
      clearOcr() // 识别成功，清除预览
    } else {
      ocrError.value = '未识别到文字'
    }
  } catch (e) {
    const msg = e.response?.data?.error || e.message || 'OCR 请求失败'
    ocrError.value = msg
  } finally {
    ocrLoading.value = false
  }
}

// ====== 翻译 ======
async function translate() {
  if (!sourceText.value.trim()) return
  loading.value = true
  error.value = ''
  try {
    const res = await api.post('/translate', {
      sourceText: sourceText.value,
      model: model.value,
      customPrompt: customPrompt.value || undefined,
      presets: selectedPresets.value.length > 0 ? selectedPresets.value : undefined
    })
    result.value = res.data
  } catch (e) {
    error.value = e.response?.data?.error || '翻译失败'
  } finally {
    loading.value = false
  }
}

function copyResult() {
  const text = result.value?.translatedText
  if (!text) return
  if (navigator.clipboard && window.isSecureContext) {
    navigator.clipboard.writeText(text).catch(() => {})
  } else {
    const textArea = document.createElement('textarea')
    textArea.value = text
    textArea.style.position = 'fixed'
    textArea.style.left = '-9999px'
    document.body.appendChild(textArea)
    textArea.select()
    document.execCommand('copy')
    document.body.removeChild(textArea)
  }
}
</script>

<style scoped>
textarea {
  resize: vertical;
  width: 100%;
  box-sizing: border-box;
}

/* ====== 拖拽上传区 ====== */
.drop-zone {
  border: 2px dashed #ccc;
  border-radius: 10px;
  padding: 20px;
  text-align: center;
  cursor: pointer;
  transition: all 0.2s;
  outline: none;
}
.drop-zone:hover {
  border-color: #888;
  background: #fafafa;
}
.drop-zone--active {
  border-color: #4a9eff;
  background: #eef6ff;
}
.drop-zone--has-image {
  border-style: solid;
  border-color: #ddd;
  padding: 12px;
}
.drop-zone-hint p {
  margin: 6px 0;
}
.drop-zone-hint a {
  color: #4a9eff;
  cursor: pointer;
  text-decoration: underline;
}

/* ====== 图片预览 ====== */
.ocr-preview {
  max-width: 100%;
}
.ocr-preview img {
  max-height: 300px;
  max-width: 100%;
  border-radius: 6px;
  box-shadow: 0 2px 8px rgba(0,0,0,0.1);
}
.ocr-preview-actions {
  margin-top: 10px;
  display: flex;
  gap: 8px;
  justify-content: center;
}

/* ====== 通用按钮 ====== */
.btn-sm {
  padding: 6px 16px;
  border-radius: 6px;
  border: 1px solid #ccc;
  cursor: pointer;
  font-size: 14px;
}
/* 移除按钮 */
.btn-remove {
  background: #f5f5f5;
  color: #555;
}
.btn-remove:hover {
  background: #e8e8e8;
}
/* 主要按钮 */
.btn-primary {
  background: #4a9eff;
  color: white;
  border-color: #4a9eff;
}
.btn-primary:disabled {
  opacity: 0.6;
  cursor: not-allowed;
}

.presets-group {
  display: flex;
  flex-wrap: wrap;
  gap: 12px;
}
.presets-group label {
  display: flex;
  align-items: center;
  gap: 4px;
  font-size: 14px;
  cursor: pointer;
}
</style>
