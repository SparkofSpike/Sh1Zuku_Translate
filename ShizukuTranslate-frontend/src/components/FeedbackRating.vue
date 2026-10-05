<template>
  <div class="feedback-rating">
    <span class="feedback-rating__label">{{ t('components.feedbackRating.label') }}</span>
    <div class="feedback-rating__stars" role="group" :aria-label="t('components.feedbackRating.label')">
      <button
        v-for="star in STARS"
        :key="star"
        type="button"
        class="feedback-rating__star"
        :class="{ 'is-on': star <= activeRating }"
        :aria-label="String(star)"
        @click="submitRating(star)"
        @mouseenter="hoverRating = star"
        @mouseleave="hoverRating = 0"
      >★</button>
    </div>
    <!-- Only low scores get asked why; the row is optional and can be skipped entirely. -->
    <div v-if="rating >= 1 && rating <= 3" class="feedback-rating__tags">
      <button
        v-for="tag in TAG_KEYS"
        :key="tag.value"
        type="button"
        class="feedback-rating__tag"
        :class="{ 'is-selected': tags.includes(tag.value) }"
        @click="toggleTag(tag.value)"
      >{{ t(tag.labelKey) }}</button>
    </div>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import api from '../api'
import { getDeviceFp } from '../utils/feedback'

const props = defineProps<{
  /** requestId of the translation being rated; also the local persistence key. */
  requestId: string
  /**
   * Model that produced the translation. Kept in the component contract for the parent's sake:
   * the backend already knows the model behind a requestId, so it is not sent in the body.
   */
  model?: string
}>()

const { t } = useI18n()

const STARS = [1, 2, 3, 4, 5]

/** The five reason tags, in display order. Values are the contract sent to the backend. */
const TAG_KEYS: { value: string; labelKey: string }[] = [
  { value: 'accuracy', labelKey: 'components.feedbackRating.accuracy' },
  { value: 'terminology', labelKey: 'components.feedbackRating.terminology' },
  { value: 'format', labelKey: 'components.feedbackRating.format' },
  { value: 'tone', labelKey: 'components.feedbackRating.tone' },
  { value: 'other', labelKey: 'components.feedbackRating.other' }
]

const rating = ref(0)
const tags = ref<string[]>([])
/** Hover preview only; the submitted value always comes from `rating`. */
const hoverRating = ref(0)

const activeRating = computed(() => hoverRating.value || rating.value)

function storageKey(): string {
  return `feedbackRating:${props.requestId}`
}

// Restore a previous vote for this exact result, so a re-render or a page reload still shows it.
onMounted(() => {
  try {
    const raw = localStorage.getItem(storageKey())
    if (!raw) return
    const saved = JSON.parse(raw) as { rating?: number; tags?: string[] } | null
    if (saved && typeof saved.rating === 'number' && saved.rating >= 1 && saved.rating <= 5) {
      rating.value = saved.rating
    }
    if (Array.isArray(saved?.tags)) {
      tags.value = saved.tags.filter(tag => TAG_KEYS.some(known => known.value === tag))
    }
  } catch (e) {
    // A stale or hand-edited record is treated as "not rated yet".
  }
})

/** One click finishes the rating: no dialog, no confirmation, nothing blocking the reading. */
function submitRating(value: number) {
  rating.value = value
  submit()
}

/** Toggling a tag re-submits the same rating with the new tag set (the backend overwrites). */
function toggleTag(tag: string) {
  tags.value = tags.value.includes(tag) ? tags.value.filter(item => item !== tag) : [...tags.value, tag]
  submit()
}

/**
 * Sends the current vote. Repeats are expected and overwrite server-side, so every interaction
 * just re-posts. Failures are swallowed to a debug log: feedback is a side channel and must
 * never interrupt the translation flow with an error.
 */
function submit() {
  api
    .post('/feedback/rate', {
      requestId: props.requestId,
      rating: rating.value,
      tags: tags.value,
      deviceFp: getDeviceFp(),
      permalink: window.location.pathname
    })
    .then(() => {
      try {
        localStorage.setItem(storageKey(), JSON.stringify({ rating: rating.value, tags: tags.value }))
      } catch (e) {
        // Storage full or disabled: the vote is already recorded server-side.
      }
    })
    .catch(err => {
      console.debug('反馈评分提交失败', err)
    })
}
</script>

<style scoped>
/* Kept small and muted: the rating sits next to the result's own controls, not above them. */
.feedback-rating {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 8px;
  font-size: 12px;
  line-height: 1.6;
  color: var(--color-muted, #777);
}

.feedback-rating__label {
  white-space: nowrap;
}

.feedback-rating__stars {
  display: inline-flex;
  align-items: center;
  gap: 1px;
}

.feedback-rating__star {
  padding: 0 2px;
  border: none;
  background: transparent;
  font-size: 15px;
  line-height: 1;
  cursor: pointer;
  color: #d0d0d0;
  transition: color 0.15s, transform 0.15s;
}

.feedback-rating__star:hover {
  transform: scale(1.15);
}

.feedback-rating__star.is-on {
  color: #f0a020;
}

.feedback-rating__tags {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
  width: 100%;
}

.feedback-rating__tag {
  padding: 1px 8px;
  border: 1px solid var(--color-border, #dee2e6);
  border-radius: 10px;
  background: transparent;
  color: inherit;
  font-size: 12px;
  line-height: 1.6;
  cursor: pointer;
}

.feedback-rating__tag:hover {
  border-color: #a5c8f0;
}

.feedback-rating__tag.is-selected {
  background: #e7f5ff;
  border-color: #a5c8f0;
  color: #1864ab;
}
</style>
