<template>
  <div class="export-bar" ref="rootEl">
    <button
      class="export-btn"
      type="button"
      :disabled="busy"
      @click="toggleMenu"
    >{{ busy ? t('components.export.exporting') : t('components.export.label') }}</button>
    <div v-if="menuOpen" class="export-menu">
      <button
        v-for="fmt in formats"
        :key="fmt"
        type="button"
        class="export-item"
        :disabled="busy"
        @click="exportAs(fmt)"
      >{{ t(`components.export.format.${fmt}`) }}</button>
    </div>
    <p v-if="error" class="export-error">{{ t('components.export.failed') }}</p>
  </div>
</template>

<script setup lang="ts">
import { onBeforeUnmount, onMounted, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import type { ExportFormat } from '../utils/export'

const { t } = useI18n()

const props = defineProps<{
  /** Latest available translation; empty while streaming has not produced text yet. */
  text: string
  /** Model used for this translation; echoed in the credit line. */
  model?: string
}>()

const formats: ExportFormat[] = ['docx', 'doc', 'pdf', 'txt']
const menuOpen = ref(false)
const busy = ref(false)
const error = ref(false)
const rootEl = ref<HTMLElement | null>(null)

function toggleMenu() {
  menuOpen.value = !menuOpen.value
}

function onDocClick(e: MouseEvent) {
  if (menuOpen.value && rootEl.value && !rootEl.value.contains(e.target as Node)) {
    menuOpen.value = false
  }
}

onMounted(() => document.addEventListener('click', onDocClick))
onBeforeUnmount(() => document.removeEventListener('click', onDocClick))

async function exportAs(format: ExportFormat) {
  menuOpen.value = false
  if (!props.text.trim()) return
  busy.value = true
  error.value = false
  try {
    // Lazy-loaded so docx/jsPDF (≈1 MB) stay out of the main bundle until an export happens.
    const { exportTranslation } = await import('../utils/exportFormats')
    // The "watermark" is the credit/disclaimer block at the top of the file. Lines 2+3
    // share one output line; a blank line separates the disclaimers from the
    // sign-off, and the sign-off itself spans two lines.
    const params = { commit: import.meta.env.VITE_COMMIT || 'unknown', model: props.model || '-' }
    const creditLines = [
      t('components.export.creditLine1', params),
      t('components.export.creditLine2', params) + t('components.export.creditLine3', params),
      t('components.export.creditLine4', params),
      t('components.export.creditLine5', params),
      '',
      t('components.export.creditLine6', params),
      t('components.export.creditLine7', params)
    ]
    await exportTranslation(props.text, format, creditLines)
  } catch (e) {
    console.error('导出失败', e)
    error.value = true
  } finally {
    busy.value = false
  }
}
</script>

<style scoped>
.export-bar {
  position: relative;
  display: inline-flex;
  flex-direction: column;
  gap: 4px;
}

.export-btn {
  background: #f0f0f0;
  color: #1a1a1a;
  border: 1px solid #ccc;
  border-radius: 6px;
  cursor: pointer;
}

.export-btn:disabled {
  opacity: 0.6;
  cursor: wait;
}

.export-menu {
  position: absolute;
  top: calc(100% + 4px);
  left: 0;
  z-index: 30;
  display: flex;
  flex-direction: column;
  min-width: 120px;
  padding: 4px;
  background: #fff;
  border: 1px solid #ccc;
  border-radius: 8px;
  box-shadow: 0 4px 16px rgba(0, 0, 0, 0.12);
}

.export-item {
  padding: 6px 12px;
  border: none;
  background: transparent;
  color: #1a1a1a;
  font-size: 14px;
  text-align: left;
  border-radius: 6px;
  cursor: pointer;
  white-space: nowrap;
}

.export-item:hover:not(:disabled) {
  background: #f0f4f8;
}

.export-item:disabled {
  opacity: 0.5;
  cursor: wait;
}

.export-error {
  margin: 0;
  font-size: 12px;
  color: #e03131;
}
</style>
