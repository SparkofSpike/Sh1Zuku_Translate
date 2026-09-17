<template>
  <div style="margin-top: 24px;">
    <div style="display:flex; align-items:center; gap:12px;">
      <h3 style="margin:0; font-weight:600;">{{ t('components.translateResult.heading') }}</h3>
      <div style="display:flex; align-items:center; gap:8px;">
        <button v-if="streamingText" @click="copyStreaming" class="btn-sm" style="background:#f0f0f0; color:#1a1a1a; border:1px solid #ccc; border-radius:6px; cursor:pointer;">{{ t('common.copy') }}</button>
        <ExportBar v-if="exportText" :text="exportText" :model="model" />
      </div>
    </div>
    <pre v-if="streamingText" style="margin-top: 12px; white-space: pre-wrap;">{{ streamingText }}</pre>
    <pre v-else-if="result" style="margin-top: 12px; white-space: pre-wrap;">{{ result.translatedText }}</pre>

    <div v-if="result?.tokenUsage" style="margin-top: 12px; color:#777; font-size:13px;">
      {{ t('components.translateResult.tokenUsage', { prompt: result.tokenUsage.promptTokens, completion: result.tokenUsage.completionTokens, total: result.tokenUsage.totalTokens }) }}
    </div>
  </div>
</template>

<script setup lang="ts">
import { computed } from 'vue'
import { useI18n } from 'vue-i18n'
import type { TranslateResponse } from '../types'
import ExportBar from './ExportBar.vue'

// Shares the `components.translateResult.*` keys with TranslateResult.vue: same result block.
const { t } = useI18n()

const props = defineProps<{
  streamingText: string
  result?: TranslateResponse | null
  /** Model used for this translation; echoed in the export credit line. */
  model?: string
}>()

// While streaming, the partial text is exported as-is; once finished, the final result wins.
const exportText = computed(() => props.streamingText || props.result?.translatedText || '')

function copyStreaming() {
  const text = props.streamingText
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
