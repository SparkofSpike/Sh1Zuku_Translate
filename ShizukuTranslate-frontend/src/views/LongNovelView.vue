<template>
  <!-- First frame: the job is taken in onMounted; render nothing until then to avoid a flash. -->
  <div v-if="ready" class="ln-page">
    <!-- No pending job (direct visit or a refresh after the handoff): a quiet empty state. -->
    <div v-if="!job" class="card ln-empty">
      <p class="ln-empty-text">{{ t('longNovel.empty') }}</p>
      <button class="ln-btn ln-btn--ghost" @click="goHome">{{ t('longNovel.back') }}</button>
    </div>

    <template v-else>
      <header class="ln-header">
        <p class="ln-overline">{{ t('longNovel.overline') }}</p>
        <h1 class="ln-title">{{ t('longNovel.title') }}</h1>
        <p class="ln-sub">{{ t('longNovel.subtitle', { model: jobModel }) }}</p>
      </header>

      <ol class="ln-steps">
        <li
          v-for="(step, i) in steps"
          :key="step"
          :class="{
            'is-done': i < progressIndex,
            'is-active': i === progressIndex && running,
            'is-frozen': i === progressIndex && !running
          }"
        >
          <span class="ln-dot" aria-hidden="true"></span>
          <span class="ln-label">{{ t('longNovel.steps.' + step) }}</span>
          <span
            v-if="i < steps.length - 1"
            class="ln-connector"
            :class="{ 'is-done': i < progressIndex }"
            aria-hidden="true"
          ></span>
        </li>
      </ol>

      <p class="ln-status">
        <span v-if="running" class="ln-spinner" aria-hidden="true"></span>
        <span>{{ statusText }}</span>
      </p>

      <div ref="panelEl" class="ln-panel" @scroll="onPanelScroll">
        <pre v-if="streamedText" class="ln-text">{{ streamedText }}</pre>
        <p v-else class="ln-waiting">{{ t('longNovel.waiting') }}</p>
      </div>

      <div class="ln-actions">
        <button v-if="running" class="ln-btn ln-btn--danger" @click="cancel">{{ t('longNovel.cancel') }}</button>
        <template v-else>
          <button v-if="streamedText" class="ln-btn ln-btn--primary" @click="copyText">
            {{ copied ? t('longNovel.copied') : t('longNovel.copy') }}
          </button>
          <button class="ln-btn ln-btn--ghost" @click="goHome">{{ t('longNovel.back') }}</button>
        </template>
      </div>
      <p v-if="errorMsg" class="ln-error">{{ errorMsg }}</p>
    </template>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, onUnmounted, ref, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import { useRouter } from 'vue-router'
import { translateStream } from '../api'
import type { SseStatus } from '../api'
import { useLongNovelStore, type LongNovelJob } from '../stores/longNovel'
import type { TranslateResponse } from '../types'

type Phase = 'connecting' | 'thinking' | 'chunking' | 'output' | 'audit' | 'done' | 'error' | 'cancelled'

const { t } = useI18n()
const router = useRouter()
const longNovelStore = useLongNovelStore()

/** The four stages users see; the pipeline reports richer steps that map onto these. */
const steps = ['think', 'chunk', 'output', 'audit'] as const

const ready = ref(false)
const job = ref<LongNovelJob | null>(null)
const jobModel = computed(() => job.value?.model || '')

const phase = ref<Phase>('connecting')
/** Index of the currently lit step; -1 before the first stage, 4 once everything finished. */
const progressIndex = ref(-1)
const running = ref(false)
const streamedText = ref('')
const errorMsg = ref('')
const copied = ref(false)
const termsCount = ref(0)
const chunkCurrent = ref(0)
const chunkTotal = ref(0)
const auditFixes = ref(0)

const panelEl = ref<HTMLElement | null>(null)
let stickToBottom = true
let cancelFn: (() => void) | null = null

function setPhase(next: Phase) {
  phase.value = next
  switch (next) {
    case 'connecting': progressIndex.value = -1; break
    case 'thinking': progressIndex.value = 0; break
    case 'chunking': progressIndex.value = 1; break
    case 'output': progressIndex.value = 2; break
    case 'audit': progressIndex.value = 3; break
    case 'done': progressIndex.value = 4; break
    // error / cancelled keep the index they stopped at.
  }
}

const statusText = computed(() => {
  switch (phase.value) {
    case 'connecting': return t('longNovel.detail.connecting')
    case 'thinking': return t('longNovel.detail.thinking')
    case 'chunking':
      return termsCount.value > 0
        ? t('longNovel.detail.termsExtracted', { count: termsCount.value })
        : t('longNovel.detail.chunking')
    case 'output': return t('longNovel.detail.output', { current: chunkCurrent.value, total: chunkTotal.value })
    case 'audit': return t('longNovel.detail.audit')
    case 'done':
      return auditFixes.value > 0
        ? t('longNovel.detail.doneWithFixes', { count: auditFixes.value })
        : t('longNovel.detail.done')
    case 'error': return t('longNovel.detail.error')
    case 'cancelled': return t('longNovel.detail.cancelled')
    default: return ''
  }
})

/** Pipeline stage events arrive as extract → extract-done → translate (n×) → audit → audit-done. */
function handleStatus(status: SseStatus) {
  switch (status.stage) {
    case 'extract':
      setPhase('thinking')
      break
    case 'extract-done':
      termsCount.value = status.count ?? 0
      setPhase('chunking')
      break
    case 'translate':
      chunkCurrent.value = status.current ?? 0
      chunkTotal.value = status.total ?? 0
      setPhase('output')
      break
    case 'audit':
      setPhase('audit')
      break
    case 'audit-done':
      auditFixes.value = status.count ?? 0
      break
    case 'audit-skipped':
      break
  }
}

onMounted(() => {
  const pending = longNovelStore.take()
  ready.value = true
  if (!pending) return
  job.value = pending
  running.value = true
  setPhase('connecting')

  const ctrl = translateStream(
    pending.sourceText,
    pending.model,
    pending.modelProfileId,
    pending.customPrompt,
    pending.presets,
    pending.targetLanguage,
    token => { streamedText.value += token },
    (response: TranslateResponse) => {
      // The audit stage may repair terminology after tokens were streamed; the final text wins.
      if (response.translatedText) streamedText.value = response.translatedText
      running.value = false
      cancelFn = null
      setPhase('done')
    },
    err => {
      errorMsg.value = err
      running.value = false
      cancelFn = null
      setPhase('error')
    },
    !!pending.skipCache,
    pending.retranslatedFrom,
    true,
    pending.thinkingType,
    handleStatus
  )
  cancelFn = () => ctrl.abort()
})

onUnmounted(() => {
  // Leaving the page abandons the run, matching the translate page's own behaviour.
  cancelFn?.()
})

function cancel() {
  cancelFn?.()
  cancelFn = null
  running.value = false
  setPhase('cancelled')
}

// Keep the output pinned to the newest text, unless the user scrolled up to read.
watch(streamedText, () => {
  if (!stickToBottom) return
  requestAnimationFrame(() => {
    const el = panelEl.value
    if (el) el.scrollTop = el.scrollHeight
  })
})

function onPanelScroll() {
  const el = panelEl.value
  if (!el) return
  stickToBottom = el.scrollHeight - el.scrollTop - el.clientHeight < 80
}

function copyText() {
  const text = streamedText.value
  if (!text) return
  if (navigator.clipboard && window.isSecureContext) {
    navigator.clipboard.writeText(text).then(() => {
      copied.value = true
      setTimeout(() => { copied.value = false }, 2000)
    }).catch(() => {})
  } else {
    const textArea = document.createElement('textarea')
    textArea.value = text
    textArea.style.position = 'fixed'
    textArea.style.opacity = '0'
    document.body.appendChild(textArea)
    textArea.select()
    document.execCommand('copy')
    document.body.removeChild(textArea)
    copied.value = true
    setTimeout(() => { copied.value = false }, 2000)
  }
}

function goHome() {
  router.push('/')
}
</script>

<style scoped>
.ln-page {
  max-width: 760px;
  margin: 0 auto;
}

.ln-empty {
  text-align: center;
}

.ln-empty-text {
  margin: 0 0 16px;
  color: var(--color-muted, #777);
}

.ln-header {
  margin: 4px 0 26px;
}

.ln-overline {
  margin: 0 0 8px;
  font-size: 11px;
  letter-spacing: 0.14em;
  text-transform: uppercase;
  color: #9a9a9a;
}

.ln-title {
  margin: 0;
  font-size: 26px;
  font-weight: 600;
  letter-spacing: 0.01em;
  color: #111;
}

.ln-sub {
  margin: 8px 0 0;
  font-size: 13px;
  color: var(--color-muted, #777);
}

/* ── Step strip: think → chunk → output → audit ─────────────────────── */

.ln-steps {
  display: flex;
  align-items: center;
  margin: 0 0 18px;
  padding: 0;
  list-style: none;
}

.ln-steps li {
  display: flex;
  align-items: center;
}

.ln-dot {
  width: 10px;
  height: 10px;
  border-radius: 50%;
  border: 1.5px solid #cfcfcf;
  background: #fff;
  flex: 0 0 auto;
  transition: background 0.25s, border-color 0.25s, box-shadow 0.25s;
}

.ln-steps li.is-done .ln-dot {
  background: #1a1a1a;
  border-color: #1a1a1a;
}

.ln-steps li.is-active .ln-dot {
  background: #1a1a1a;
  border-color: #1a1a1a;
  animation: ln-pulse 1.8s ease-in-out infinite;
}

.ln-steps li.is-frozen .ln-dot {
  background: #8a8a8a;
  border-color: #8a8a8a;
}

.ln-label {
  margin: 0 12px;
  font-size: 13px;
  color: #a0a0a0;
  transition: color 0.25s;
}

.ln-steps li.is-done .ln-label {
  color: #555;
}

.ln-steps li.is-active .ln-label {
  color: #111;
  font-weight: 600;
}

.ln-connector {
  width: 44px;
  height: 1px;
  background: #e6e6e6;
  transition: background 0.25s;
}

.ln-connector.is-done {
  background: #1a1a1a;
}

@keyframes ln-pulse {
  0%, 100% { box-shadow: 0 0 0 3px rgba(26, 26, 26, 0.10); }
  50% { box-shadow: 0 0 0 6px rgba(26, 26, 26, 0.04); }
}

/* ── Current status line ────────────────────────────────────────────── */

.ln-status {
  display: flex;
  align-items: center;
  gap: 10px;
  min-height: 22px;
  margin: 0 0 14px;
  font-size: 13px;
  color: #555;
}

.ln-spinner {
  flex: 0 0 auto;
  width: 12px;
  height: 12px;
  border: 2px solid #e0e0e0;
  border-top-color: #1a1a1a;
  border-radius: 50%;
  animation: ln-spin 0.8s linear infinite;
}

@keyframes ln-spin {
  to { transform: rotate(360deg); }
}

/* ── Streaming output panel ─────────────────────────────────────────── */

.ln-panel {
  min-height: 280px;
  max-height: 54vh;
  overflow-y: auto;
  padding: 22px 24px;
  border: 1px solid #e8e8e8;
  border-radius: 10px;
  background: #fcfcfc;
}

.ln-text {
  margin: 0;
  font-family: inherit;
  font-size: 15px;
  line-height: 1.9;
  color: #222;
  white-space: pre-wrap;
  word-break: break-word;
}

.ln-waiting {
  margin: 0;
  font-size: 14px;
  color: #b0b0b0;
}

/* ── Actions ────────────────────────────────────────────────────────── */

.ln-actions {
  display: flex;
  gap: 10px;
  margin-top: 18px;
}

.ln-btn {
  padding: 8px 20px;
  font-size: 14px;
  border-radius: 6px;
  transition: background 0.2s, border-color 0.2s, color 0.2s;
}

.ln-btn--primary {
  background: #1a1a1a;
  color: #fff;
  border: 1px solid #1a1a1a;
}

.ln-btn--primary:hover {
  background: #444;
}

.ln-btn--ghost {
  background: #fff;
  color: #555;
  border: 1px solid #ddd;
}

.ln-btn--ghost:hover {
  background: #f5f5f5;
}

.ln-btn--danger {
  background: #fff;
  color: #e03131;
  border: 1px solid #f0b4b4;
}

.ln-btn--danger:hover {
  background: #fff5f5;
}

.ln-error {
  margin-top: 12px;
  font-size: 13px;
  color: #e03131;
}

@media (max-width: 640px) {
  .ln-connector {
    width: 18px;
  }

  .ln-label {
    margin: 0 8px;
  }
}
</style>
