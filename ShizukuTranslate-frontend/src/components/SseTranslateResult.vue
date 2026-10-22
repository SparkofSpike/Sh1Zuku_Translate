<template>
  <div style="margin-top: 24px;">
    <div style="display:flex; align-items:center; gap:12px; flex-wrap:wrap;">
      <h3 style="margin:0; font-weight:600;">{{ t('components.translateResult.heading') }}</h3>
      <span v-if="showSharedBadge" class="origin-badge origin-badge--shared">{{ t('components.translateResult.sharedBadge') }}</span>
      <span v-else-if="showCacheBadge" class="origin-badge origin-badge--cache">{{ t('components.translateResult.cacheBadge') }}</span>
      <span v-if="showTruncatedBadge" class="origin-badge origin-badge--truncated">{{ t('components.translateResult.truncatedBadge') }}</span>
      <div style="display:flex; align-items:center; gap:8px;">
        <button v-if="streamingText" @click="copyStreaming" class="btn-sm" style="background:#f0f0f0; color:#1a1a1a; border:1px solid #ccc; border-radius:6px; cursor:pointer;">{{ t('common.copy') }}</button>
        <ExportBar v-if="exportText" :text="exportText" :model="model" />
      </div>
      <FeedbackRating v-if="finalRequestId" :request-id="finalRequestId" :model="model" />
    </div>
    <pre v-if="streamingText" style="margin-top: 12px; white-space: pre-wrap;">{{ streamingText }}</pre>
    <pre v-else-if="result" style="margin-top: 12px; white-space: pre-wrap;">{{ result.translatedText }}</pre>

    <p v-if="showTruncatedBadge" class="truncated-hint">{{ t('components.translateResult.truncatedHint') }}</p>

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
import FeedbackRating from './FeedbackRating.vue'
import { reportFeedbackEvent } from '../utils/feedback'

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

// Badges only make sense for an instantly-served result: while tokens are still arriving
// the stream looks identical for a live translation, a cache replay and a shared replay.
const badgesFinal = computed(() => !!props.result && (!props.streamingText || props.streamingText === props.result.translatedText))
const showSharedBadge = computed(() => badgesFinal.value && !!props.result?.fromSharedTranslation)
const showCacheBadge = computed(() => badgesFinal.value && !props.result?.fromSharedTranslation && !!props.result?.fromCache)
// A capped answer only exists once the `done` event arrived, so unlike the origin badges this
// flag does not depend on the streaming text matching the final text.
const showTruncatedBadge = computed(() => !!props.result?.truncated)

// Rating needs a finished result: mid-stream the `done` event (and with it the requestId) has
// not arrived yet, so there is nothing to attach a score to.
const finalRequestId = computed(() => (badgesFinal.value && props.result?.requestId) || '')

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
  // Usage signal for the feedback pipeline; only possible once the final result is known.
  if (props.result?.requestId) reportFeedbackEvent(props.result.requestId, 'copy')
}
</script>

<style scoped>
.origin-badge {
  display: inline-flex;
  align-items: center;
  padding: 2px 8px;
  border-radius: 10px;
  font-size: 12px;
  line-height: 1.6;
  white-space: nowrap;
}

.origin-badge--shared {
  background: #fff3bf;
  color: #8a6d00;
  border: 1px solid #f0c93a;
}

.origin-badge--cache {
  background: #e7f5ff;
  color: #1864ab;
  border: 1px solid #a5c8f0;
}

.origin-badge--truncated {
  background: #fff0f0;
  color: #c92a2a;
  border: 1px solid #f5b5b5;
}

.truncated-hint {
  margin: 10px 0 0;
  padding: 8px 10px;
  border-left: 3px solid #e03131;
  background: #fff5f5;
  color: #8a1f1f;
  font-size: 13px;
  line-height: 1.6;
}
</style>
