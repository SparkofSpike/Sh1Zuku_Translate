<template>
  <div class="feedback-panel">
    <div class="feedback-heading">
      <div>
        <h3 class="feedback-title">{{ t('admin.feedback.title') }}</h3>
        <p class="feedback-subtitle">{{ t('admin.feedback.subtitle', { days: DAYS }) }}</p>
      </div>
      <button type="button" class="btn-sm feedback-refresh" :disabled="loading" @click="loadAll">
        {{ t('admin.feedback.refresh') }}
      </button>
    </div>

    <p v-if="loading" class="feedback-muted">{{ t('admin.feedback.loading') }}</p>
    <p v-if="error" class="feedback-error">{{ error }}</p>

    <template v-if="summary">
      <div class="feedback-metrics">
        <div class="feedback-metric"><span>{{ t('admin.feedback.summary.sampleCount') }}</span><strong>{{ formatNumber(summary.sampleCount) }}</strong></div>
        <div class="feedback-metric"><span>{{ t('admin.feedback.summary.eventCount') }}</span><strong>{{ formatNumber(summary.eventCount) }}</strong></div>
        <div class="feedback-metric"><span>{{ t('admin.feedback.summary.ratingCount') }}</span><strong>{{ formatNumber(summary.rating.count) }}</strong></div>
        <div class="feedback-metric"><span>{{ t('admin.feedback.summary.ratingAverage') }}</span><strong>{{ formatAverage(summary.rating.average) }}</strong></div>
        <div class="feedback-metric"><span>{{ t('admin.feedback.summary.lowRate') }}</span><strong>{{ formatPercent(summary.rating.lowRate) }}</strong></div>
      </div>

      <div class="feedback-boxes">
        <div class="feedback-box">
          <h4>{{ t('admin.feedback.summary.distribution') }}</h4>
          <div class="feedback-dist">
            <div v-for="row in distributionRows" :key="row.star" class="feedback-dist-row">
              <span class="feedback-dist-star">★{{ row.star }}</span>
              <div class="feedback-dist-track"><div class="feedback-dist-fill" :style="{ width: row.percent + '%' }"></div></div>
              <strong>{{ formatNumber(row.count) }}</strong>
            </div>
          </div>
        </div>
        <div v-for="group in countGroups" :key="group.label" class="feedback-box">
          <h4>{{ group.label }}</h4>
          <ul v-if="group.entries.length" class="feedback-counts">
            <li v-for="entry in group.entries" :key="entry.name" :title="entry.name">
              <span class="feedback-count-name">{{ entry.name }}</span>
              <strong>{{ formatNumber(entry.value) }}</strong>
            </li>
          </ul>
          <p v-else class="feedback-muted">{{ t('admin.feedback.empty') }}</p>
        </div>
      </div>

      <h4 class="feedback-section-title">
        {{ t('admin.feedback.samples.title') }}
        <small class="feedback-muted">{{ t('admin.feedback.samples.total', { count: sampleTotal }) }}</small>
      </h4>
      <p v-if="!samples.length" class="feedback-muted">{{ t('admin.feedback.samples.empty') }}</p>
      <div v-else class="feedback-list">
        <article v-for="(sample, index) in samples" :key="sample.request_id + index" class="feedback-item">
          <div class="feedback-meta">
            <time>{{ formatTs(sample.ts) }}</time>
            <span v-if="sample.model">{{ sample.model }}</span>
            <span>{{ t('admin.feedback.samples.origin') }}: {{ sample.sampled_by || '-' }}</span>
            <span>{{ t('admin.feedback.samples.length') }}: {{ sample.bucket?.length || '-' }}</span>
            <span>{{ t('admin.feedback.samples.chars') }}: {{ formatNumber(sample.char_count) }}</span>
            <span>{{ t('admin.feedback.samples.latency') }}: {{ formatLatency(sample.latency_ms) }}</span>
            <code :title="sample.request_id">{{ shortId(sample.request_id) }}</code>
          </div>
          <p class="feedback-text">
            <span class="feedback-text-label">{{ t('admin.feedback.samples.source') }}</span>
            <span :title="sample.source_text">{{ preview(sample.source_text) }}</span>
          </p>
          <p class="feedback-text">
            <span class="feedback-text-label">{{ t('admin.feedback.samples.target') }}</span>
            <span :title="sample.target_text">{{ preview(sample.target_text) }}</span>
          </p>
        </article>
      </div>

      <h4 class="feedback-section-title">
        {{ t('admin.feedback.events.title') }}
        <small class="feedback-muted">{{ t('admin.feedback.events.total', { count: eventTotal }) }}</small>
      </h4>
      <p v-if="!events.length" class="feedback-muted">{{ t('admin.feedback.events.empty') }}</p>
      <div v-else class="feedback-list">
        <article v-for="(item, index) in events" :key="item.request_id + item.event + index" class="feedback-item">
          <div class="feedback-meta">
            <time>{{ formatTs(item.ts) }}</time>
            <template v-if="item.event === 'rate'">
              <strong class="feedback-stars" :title="t('admin.feedback.events.rating', { rating: ratingOf(item) })">★{{ ratingOf(item) }}</strong>
              <span v-for="tag in tagsOf(item)" :key="tag" class="feedback-tag">{{ tagLabel(tag) }}</span>
              <span v-if="commentOf(item)" class="feedback-comment" :title="commentOf(item)">{{ preview(commentOf(item), 80) }}</span>
            </template>
            <span v-else class="feedback-event-type">{{ item.event }}</span>
            <code :title="item.request_id">{{ shortId(item.request_id) }}</code>
          </div>
        </article>
      </div>
    </template>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import api from '../api'
import type {
  AdminFeedbackEvent,
  AdminFeedbackEventsResponse,
  AdminFeedbackSample,
  AdminFeedbackSamplesResponse,
  AdminFeedbackSummary
} from '../types'

const { t, te } = useI18n()

/** Feedback window and page sizes; kept in one place so the header text cannot drift from the query. */
const DAYS = 7
const SAMPLE_LIMIT = 50
const EVENT_LIMIT = 50
/** Preview length for sample texts; the full text stays reachable through the title attribute. */
const PREVIEW_CHARS = 120

const summary = ref<AdminFeedbackSummary | null>(null)
const samples = ref<AdminFeedbackSample[]>([])
const events = ref<AdminFeedbackEvent[]>([])
const sampleTotal = ref(0)
const eventTotal = ref(0)
const loading = ref(true)
const error = ref('')

/**
 * Loads the three admin endpoints together. They are independent reads, so one failing
 * (a disabled feedback pipeline, say) must not blank out the other two — hence `allSettled`
 * and a single error line instead of a shared try/catch that drops every result.
 */
async function loadAll() {
  loading.value = true
  error.value = ''
  const [summaryResult, samplesResult, eventsResult] = await Promise.allSettled([
    api.get<AdminFeedbackSummary>('/admin/feedback/summary', { params: { days: DAYS } }),
    api.get<AdminFeedbackSamplesResponse>('/admin/feedback/samples', { params: { limit: SAMPLE_LIMIT } }),
    api.get<AdminFeedbackEventsResponse>('/admin/feedback/events', { params: { limit: EVENT_LIMIT } })
  ])

  if (summaryResult.status === 'fulfilled') summary.value = normalizeSummary(summaryResult.value.data)
  if (samplesResult.status === 'fulfilled') {
    samples.value = samplesResult.value.data?.items || []
    sampleTotal.value = Number(samplesResult.value.data?.total ?? samples.value.length) || 0
  }
  if (eventsResult.status === 'fulfilled') {
    events.value = eventsResult.value.data?.items || []
    eventTotal.value = Number(eventsResult.value.data?.total ?? events.value.length) || 0
  }

  const failure = [summaryResult, samplesResult, eventsResult].find(result => result.status === 'rejected')
  if (failure && failure.status === 'rejected') error.value = apiError(failure.reason, t('admin.feedback.loadFailed'))
  loading.value = false
}

/** Fills in the counters the UI reads directly, so a partial payload cannot break rendering. */
function normalizeSummary(data: AdminFeedbackSummary): AdminFeedbackSummary {
  const rating = data.rating
  return {
    ...data,
    eventsByType: data.eventsByType || {},
    languagePairs: data.languagePairs || {},
    models: data.models || {},
    samplesByOrigin: data.samplesByOrigin || {},
    rating: {
      count: Number(rating?.count ?? 0),
      average: rating?.average ?? null,
      lowRate: rating?.lowRate ?? null,
      distribution: rating?.distribution || {}
    }
  }
}

onMounted(loadAll)

const distributionRows = computed(() => {
  const distribution = summary.value?.rating.distribution || {}
  const counts = [1, 2, 3, 4, 5].map(star => Number(distribution[String(star)] || 0))
  const max = Math.max(...counts, 1)
  return counts.map((count, index) => ({ star: index + 1, count, percent: count ? Math.max(4, Math.round(count / max * 100)) : 0 }))
})

/** The three "name: count" lists; sorted by count so the loudest entry is always first. */
const countGroups = computed(() => [
  { label: t('admin.feedback.summary.origin'), entries: countEntries(summary.value?.samplesByOrigin) },
  { label: t('admin.feedback.summary.languagePairs'), entries: countEntries(summary.value?.languagePairs) },
  { label: t('admin.feedback.summary.models'), entries: countEntries(summary.value?.models) }
])

function countEntries(map?: Record<string, number>) {
  if (!map) return [] as { name: string; value: number }[]
  return Object.entries(map)
    .map(([name, value]) => ({ name, value: Number(value) || 0 }))
    .sort((a, b) => b.value - a.value || a.name.localeCompare(b.name))
}

function ratingOf(item: AdminFeedbackEvent) {
  const rating = item.payload?.rating
  return typeof rating === 'number' ? rating : '-'
}

function tagsOf(item: AdminFeedbackEvent) {
  const tags = item.payload?.tags
  return Array.isArray(tags) ? tags.filter(tag => typeof tag === 'string') : []
}

function commentOf(item: AdminFeedbackEvent) {
  const comment = item.payload?.comment
  return typeof comment === 'string' ? comment : ''
}

/** Reason tags reuse the rating widget's own labels, so both surfaces read the same. */
function tagLabel(tag: string) {
  const key = 'components.feedbackRating.' + tag
  return te(key) ? t(key) : tag
}

function apiError(e: unknown, fallback: string): string {
  const message = (e as { response?: { data?: { error?: string } } })?.response?.data?.error
  return message || fallback
}

function formatNumber(value: unknown) { return Number(value || 0).toLocaleString() }

function formatAverage(value: number | null | undefined) {
  if (value == null || Number.isNaN(Number(value))) return '-'
  return Number(value).toFixed(1)
}

function formatPercent(value: number | null | undefined) {
  if (value == null || Number.isNaN(Number(value))) return '-'
  const rate = Number(value)
  // The contract sends a 0–1 rate; a backend that sends whole percents is tolerated as well.
  return (rate <= 1 ? rate * 100 : rate).toFixed(1) + '%'
}

function formatLatency(value: number | null | undefined) {
  if (value == null || Number.isNaN(Number(value))) return '-'
  return formatNumber(value) + ' ms'
}

function formatTs(value: string) {
  if (!value) return '-'
  const date = new Date(value)
  return Number.isNaN(date.getTime()) ? value : date.toLocaleString()
}

function shortId(value: string) { return value ? value.slice(0, 8) : '-' }

function preview(value: string | null | undefined, max: number = PREVIEW_CHARS) {
  if (!value) return ''
  return value.length > max ? value.slice(0, max) + '…' : value
}
</script>

<style scoped>
.feedback-panel { min-width: 0; }
.feedback-heading { display: flex; justify-content: space-between; align-items: flex-start; gap: 16px; }
.feedback-title { margin: 0; font-size: 16px; font-weight: 600; }
.feedback-subtitle { margin: 3px 0 0; color: #777; font-size: 13px; }
.feedback-refresh { background: #fff; color: #333; border-color: #bbb; }
.feedback-refresh:hover:not(:disabled) { background: #eee; }
.feedback-muted { color: #777; font-size: 13px; }
.feedback-error { color: #e03131; font-size: 13px; }
.feedback-metrics { display: grid; grid-template-columns: repeat(5, 1fr); gap: 10px; margin: 20px 0; }
.feedback-metric { padding: 15px; border: 1px solid #e5e5e5; background: #fafafa; border-radius: 7px; }
.feedback-metric span { display: block; color: #777; font-size: 12px; }
.feedback-metric strong { display: block; margin-top: 3px; font-size: 22px; }
.feedback-boxes { display: grid; grid-template-columns: repeat(auto-fit, minmax(200px, 1fr)); gap: 14px; }
.feedback-box { min-width: 0; padding: 14px; border: 1px solid #e5e5e5; border-radius: 7px; }
.feedback-box h4 { margin: 0 0 10px; font-size: 13px; font-weight: 600; }
.feedback-box p { margin: 0; }
.feedback-dist { display: flex; flex-direction: column; gap: 6px; }
.feedback-dist-row { display: grid; grid-template-columns: auto 1fr auto; gap: 8px; align-items: center; font-size: 12px; }
.feedback-dist-star { color: #b8860b; white-space: nowrap; }
.feedback-dist-track { height: 8px; background: #eee; border-radius: 5px; overflow: hidden; }
.feedback-dist-fill { height: 100%; background: #f0a020; border-radius: 5px; transition: width .25s; }
.feedback-counts { display: flex; flex-direction: column; gap: 4px; margin: 0; padding: 0; list-style: none; font-size: 12px; }
.feedback-counts li { display: flex; justify-content: space-between; gap: 8px; }
.feedback-count-name { min-width: 0; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; color: #555; }
.feedback-section-title { margin: 24px 0 10px; font-size: 15px; font-weight: 600; }
.feedback-section-title small { margin-left: 8px; font-weight: 400; }
.feedback-list { border: 1px solid #eee; border-radius: 7px; max-height: 460px; overflow-y: auto; overscroll-behavior: contain; }
.feedback-item { padding: 10px 12px; border-bottom: 1px solid #f0f0f0; }
.feedback-item:last-child { border-bottom: 0; }
.feedback-meta { display: flex; flex-wrap: wrap; align-items: center; gap: 10px; color: #777; font-size: 12px; }
.feedback-meta time { color: #555; }
.feedback-meta code { padding: 1px 5px; border-radius: 3px; background: #f1f1f1; font-size: 11px; }
.feedback-stars { color: #b8860b; }
.feedback-tag { padding: 1px 7px; border: 1px solid #dee2e6; border-radius: 10px; color: #1864ab; background: #e7f5ff; font-size: 11px; }
.feedback-event-type { padding: 1px 7px; border-radius: 10px; background: #f1f1f1; color: #555; font-size: 11px; }
.feedback-comment { min-width: 0; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; color: #666; font-style: italic; }
.feedback-text { display: flex; gap: 8px; margin: 6px 0 0; font-size: 13px; color: #444; }
.feedback-text-label { flex-shrink: 0; color: #999; font-size: 12px; }
.feedback-text span:last-child { min-width: 0; overflow-wrap: anywhere; }
@media (max-width: 720px) { .feedback-metrics { grid-template-columns: repeat(2, 1fr); } .feedback-heading { flex-direction: column; gap: 8px; } }
</style>
