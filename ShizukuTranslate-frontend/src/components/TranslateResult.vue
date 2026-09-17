<template>
  <div style="margin-top: 24px;">
    <div style="display:flex; align-items:center; gap:12px;">
      <h3 style="margin:0; font-weight:600;">{{ t('components.translateResult.heading') }}</h3>
      <div style="display:flex; align-items:center; gap:8px;">
        <button @click="copyResult" class="btn-sm" style="background:#f0f0f0; color:#1a1a1a; border:1px solid #ccc; border-radius:6px; cursor:pointer;">{{ t('common.copy') }}</button>
        <ExportBar v-if="result.translatedText" :text="result.translatedText" :model="model" />
      </div>
    </div>
    <pre style="margin-top: 12px; white-space: pre-wrap;">{{ result.translatedText }}</pre>

    <div v-if="result.tokenUsage" style="margin-top: 12px; color:#777; font-size:13px;">
      {{ t('components.translateResult.tokenUsage', { prompt: result.tokenUsage.promptTokens, completion: result.tokenUsage.completionTokens, total: result.tokenUsage.totalTokens }) }}
    </div>
  </div>
</template>

<script setup lang="ts">
import { useI18n } from 'vue-i18n'
import type { TranslateResponse } from '../types'
import ExportBar from './ExportBar.vue'

const { t } = useI18n()

const props = defineProps<{
  result: TranslateResponse
  /** Model used for this translation; echoed in the export credit line. */
  model?: string
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
