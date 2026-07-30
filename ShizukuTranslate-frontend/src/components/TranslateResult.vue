<template>
  <div style="margin-top: 24px;">
    <div style="display:flex; align-items:center; gap:12px;">
      <h3 style="margin:0; font-weight:600;">翻译结果</h3>
      <button @click="copyResult" class="btn-sm" style="background:#f0f0f0; color:#1a1a1a; border:1px solid #ccc; border-radius:6px; cursor:pointer;">复制</button>
    </div>
    <pre style="margin-top: 12px; white-space: pre-wrap;">{{ result.translatedText }}</pre>

    <div v-if="result.tokenUsage" style="margin-top: 12px; color:#777; font-size:13px;">
      Token 用量：输入 {{ result.tokenUsage.promptTokens }} | 输出 {{ result.tokenUsage.completionTokens }} | 合计 {{ result.tokenUsage.totalTokens }}
    </div>
  </div>
</template>

<script setup lang="ts">
import type { TranslateResponse } from '../types'

const props = defineProps<{
  result: TranslateResponse
}>()

function copyResult() {
  const text = props.result.translatedText
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
