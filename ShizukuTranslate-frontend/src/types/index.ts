export interface TranslateRequest {
  sourceText: string
  model?: string
  modelProfileId?: number | null
  customPrompt?: string
  presets?: string[]
  /** Target language tag; the backend falls back to its configured default when omitted. */
  targetLanguage?: string
  /**
   * Re-translate switch: ignore the caller's own cache AND other users' translations of the
   * same text, and force a fresh model call. The new result is still cached afterwards.
   */
  skipCache?: boolean
  /**
   * requestId of the result this request re-translates. Set when the user presses
   * "re-translate", so the backend can link the new translation to the one it replaces.
   */
  retranslatedFrom?: string
}

/** A Pixiv novel imported by URL (`GET /pixiv/novel`). */
export interface PixivNovelResponse {
  novelId: string
  title: string
  author: string
  /** Work summary as plain text (Pixiv HTML stripped), possibly empty. */
  description: string
  tags: string[]
  text: string
  /** Title / author / tags / description as a labelled block, ready to send as a translate request. */
  metadataText: string
}

/** A target language offered by the backend (`GET /translation/languages`). */
export interface LanguageOption {
  code: string
  label: string
}

export interface TokenUsage {
  promptTokens: number
  completionTokens: number
  totalTokens: number
}

export interface TranslateResponse {
  id: number
  translatedText: string
  model: string
  createdAt: string
  tokenUsage?: TokenUsage
  /** Served from another user's earlier translation of the same text (no model call). */
  fromSharedTranslation?: boolean
  /** Replayed from this user's own translation cache. */
  fromCache?: boolean
  /**
   * Server-side id of this translation, echoed on both the REST response and the SSE `done`
   * event. It is the key the anonymous feedback endpoints (rating / events) correlate on.
   */
  requestId?: string
}

export interface LoginRequest {
  username: string
  password: string
}

export interface RegisterRequest {
  username: string
  password: string
  email?: string
  code?: string
}

export interface UserInfo {
  id: number
  username: string
  email: string
  isAdmin: boolean
  emailVerified?: boolean
}

export interface HistoryRecord {
  id: number
  sourceText: string
  translatedText: string
  model: string
  customPrompt?: string
  createdAt: string
}

export interface Announcement {
  id: number
  title: string
  content: string
  requireConfirmation?: boolean
  createdAt: string
}

export interface AnnouncementAcknowledgement {
  username: string
  email: string
  acknowledgedAt: string
}

export interface AnnouncementAcknowledgementSummary {
  announcementId: number
  requireConfirmation: boolean
  total: number
  users: AnnouncementAcknowledgement[]
}

export interface UsageSummary {
  promptTokens: number
  completionTokens: number
  totalTokens: number
  requestCount: number
  latestUsedAt?: string | null
}

export interface UsageDay {
  date: string
  totalTokens: number
}

export interface UsageModel {
  provider: string
  model: string
  totalTokens: number
}

export interface UsageUser extends UsageSummary {
  id: number
  username: string
  email: string
}

export interface UsageLog extends UsageSummary {
  id: number
  provider: string
  model: string
  sourceType?: string
  estimated?: boolean
  createdAt: string
}

// ─── Translation quality feedback (admin read-only API) ─────────
// Field names stay snake_case because they mirror the feedback pipeline's JSONL schema.

/** Aggregate counters for the feedback window (`GET /admin/feedback/summary`). */
export interface AdminFeedbackSummary {
  days: number
  /** Window start / end, ISO8601 as reported by the backend. */
  since: string
  until: string
  sampleCount: number
  eventCount: number
  eventsByType: Record<string, number>
  rating: {
    count: number
    /** `null` when nothing has been rated in the window. */
    average: number | null
    /** Share of ratings at or below the low-score threshold, as a 0–1 rate. */
    lowRate: number | null
    /** Star value ("1"–"5") to number of votes. */
    distribution: Record<string, number>
  }
  /** Counts keyed by "<source>-<target>" language pair. */
  languagePairs: Record<string, number>
  models: Record<string, number>
  /** Counts keyed by the sampler that captured the sample (e.g. a rate-triggered policy). */
  samplesByOrigin: Record<string, number>
}

/** One sampled translation (`GET /admin/feedback/samples`). Texts are redacted server-side. */
export interface AdminFeedbackSample {
  request_id: string
  ts: string
  source_lang: string
  target_lang: string
  engine: string
  model: string
  params: Record<string, unknown>
  char_count: number
  bucket: { length: string | null; scene: string | null }
  latency_ms: number | null
  source_text: string
  target_text: string
  source_sha256: string
  sampled_by: string
  /** Present only when the stored text was cut down to the sample size limit. */
  truncated?: boolean
  thinking?: string
}

/** One behaviour event (`GET /admin/feedback/events`); rate events carry the vote payload. */
export interface AdminFeedbackEvent {
  request_id: string
  ts: string
  event: string
  payload: {
    rating?: number
    tags?: string[]
    comment?: string
    device_fp?: string
    permalink?: string
    [key: string]: unknown
  }
}

export interface AdminFeedbackSamplesResponse {
  items: AdminFeedbackSample[]
  total: number
}

export interface AdminFeedbackEventsResponse {
  items: AdminFeedbackEvent[]
  total: number
}
