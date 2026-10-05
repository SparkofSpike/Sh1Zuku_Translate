import type { PixivExtractResponse, PixivSearchItem } from '../types'

/**
 * Pure ranking helpers behind the screenshot-import search.
 *
 * The recognised work is located without any user-selected search mode: several searches run
 * in parallel and the merged candidates are ranked by how well they match the recognised
 * metadata — a title match dominates, tag overlap breaks ties. Keeping these helpers free of
 * Vue lets the ranking be unit-tested against real recognition data.
 */

/** Longest punctuation-free run of the title — the best keyword for title-mode search. */
export function titleSearchChunk(title: string): string {
  const parts = (title || '')
    .split(/[\s、。，,.!！?？…·:：;；\-—ー～~「」『』【】()（）\[\]]+/)
    .map(part => part.trim())
    .filter(part => part.length >= 2)
  if (!parts.length) return (title || '').trim().slice(0, 20)
  return parts.reduce((longest, part) => (part.length > longest.length ? part : longest))
}

/**
 * Keywords used to search for a recognised title. The full title goes first — Pixiv's
 * default novel search matches it as-is, spaces and all — and the longest clean run is
 * kept as a second attempt for titles wrapped in heavy decoration. (Searching one long
 * fragment alone was the old behaviour; it silently found nothing for titles the model
 * read with a prefix, because Pixiv never received the full string.)
 */
export function titleSearchKeywords(title: string): string[] {
  const full = (title || '').trim()
  if (!full) return []
  const chunk = titleSearchChunk(full)
  return chunk && chunk !== full ? [full, chunk] : [full]
}

/** Lower-cased title with spaces and punctuation removed, for tolerant comparison. */
export function normalizeTitle(value: string): string {
  return (value || '')
    .toLowerCase()
    .replace(/[\s、。，,.!！?？…·:：;；\-—ー～~「」『』【】()（）\[\]・／\/｜|#]/g, '')
}

/**
 * True when one title contains the other after normalisation, and the shorter side is long
 * enough to be meaningful (a 2-character title would match half the site).
 */
export function titleMatches(a: string, b: string): boolean {
  const na = normalizeTitle(a)
  const nb = normalizeTitle(b)
  if (na.length < 4 || nb.length < 4) return false
  return na.includes(nb) || nb.includes(na)
}

/** Tag comparison is case-insensitive. */
export function normalizeTag(tag: string): string {
  return (tag || '').trim().toLowerCase()
}

/**
 * Relevance of one candidate against the recognised metadata. The title dominates — a
 * normalised title match scores 1000 and always outranks any amount of tag overlap — and the
 * count of recognised *concrete* tags the candidate also carries breaks ties below that.
 * Generic catch-alls (R-18 etc.) are excluded from scoring: every second work carries them,
 * so they would flatten the ranking instead of ordering it. Both sides are digit-normalised
 * so a tag read as BLACKSOULSⅡ still matches a work tagged BLACKSOULS2.
 *
 * @param trusted true when the candidate was reached through a digit-normalised (trusted)
 *                keyword; such hits get a small bonus over fuzzy-search neighbours.
 */
export function scoreCandidate(info: PixivExtractResponse, item: PixivSearchItem, trusted = false): number {
  const titleScore = titleMatches(info.title, item.title) ? 1000 : 0
  const scoreTags = concreteTags(info.tags || [])
  const wanted = new Set(scoreTags.map(tag => normalizeTagForSearch(normalizeTag(tag))).filter(Boolean))
  const present = new Set((item.tags || []).map(tag => normalizeTagForSearch(normalizeTag(tag))))
  let overlap = 0
  for (const tag of wanted) {
    if (present.has(tag)) overlap++
  }
  return titleScore + Math.min(overlap, 9) * 10 + (trusted ? PRECISE_VARIANT_BONUS : 0)
}

/** A candidate scoring at least this much carries a title match: import it directly. */
export const AUTO_IMPORT_SCORE = 1000

/**
 * Catch-all tags that only dilute a tag search: R-18 alone matches tens of thousands of
 * works, so ANDing it either zeroes the result (the target may not carry it) or floods the
 * candidate list. Concrete tags are what actually locate a work.
 */
const GENERIC_TAGS = new Set([
  'r-18', 'r18', 'r-18g', 'r18g', '成人向け', '全年齢', 'r指定',
  'オリジナル', '二次創作', '短編', '長編', '連載', '完結', 'シリーズ',
  '小説', 'novel', '漫画', 'イラスト', 'fanart', 'その他'
].map(tag => tag.toLowerCase()))

/** Roman numerals and full-width digits map to plain ASCII digits. */
const ROMAN_DIGITS: Record<string, string> = {
  'Ⅰ': '1', 'Ⅱ': '2', 'Ⅲ': '3', 'Ⅳ': '4', 'Ⅴ': '5', 'Ⅵ': '6', 'Ⅶ': '7', 'Ⅷ': '8', 'Ⅸ': '9', 'Ⅹ': '10',
  'ⅰ': '1', 'ⅱ': '2', 'ⅲ': '3', 'ⅳ': '4', 'ⅴ': '5', 'ⅵ': '6', 'ⅶ': '7', 'ⅷ': '8', 'ⅸ': '9', 'ⅹ': '10'
}

/**
 * Digit normalisation for search keywords: the vision model unpredictably transcribes a tag
 * like {@code BLACKSOULS2} as {@code BLACKSOULSⅡ}, and Pixiv treats the roman form as a
 * fuzzy query returning unrelated works — the transcription alone can make the original
 * work unfindable. Converting to ASCII digits before searching fixes that.
 */
export function normalizeTagForSearch(tag: string): string {
  let text = tag || ''
  for (const [roman, digit] of Object.entries(ROMAN_DIGITS)) {
    if (text.includes(roman)) text = text.split(roman).join(digit)
  }
  // Full-width digits ０-９ → ASCII digits.
  text = text.replace(/[０-９]/g, ch => String.fromCharCode(ch.charCodeAt(0) - 0xFEE0))
  return text
}

/** Tags worth searching with: concrete tags when any exist, otherwise the full list. */
export function concreteTags(tags: string[]): string[] {
  const cleaned = (tags || []).map(tag => (tag || '').trim()).filter(Boolean)
  const concrete = cleaned.filter(tag => !GENERIC_TAGS.has(tag.toLowerCase()))
  return concrete.length ? concrete : cleaned
}

/**
 * Keywords to try for a set of tags. When the digits differ, the digit-normalised form comes
 * first: the vision model's roman/full-width digits are the suspect transcription, so the
 * ASCII form is the more trustworthy query (and its hits rank higher in the merge).
 */
export function tagSearchKeywords(tags: string[]): string[] {
  const joined = (tags || []).join(' ').trim()
  if (!joined) return []
  const normalized = normalizeTagForSearch(joined)
  return normalized !== joined ? [normalized, joined] : [joined]
}

/**
 * Whether a search keyword is already in its trusted (digit-normalised) form. Hits from such
 * queries rank slightly higher: a query that needed digit correction may have matched
 * Pixiv's fuzzy search, and works merely *tagged* with the roman form are not what the
 * user's screenshot actually showed.
 */
export function isTrustedKeyword(keyword: string): boolean {
  return !!keyword && normalizeTagForSearch(keyword) === keyword
}

/** Candidates reached through a trusted keyword get this small ranking bonus. */
export const PRECISE_VARIANT_BONUS = 5

/**
 * Whether the recognised title is worth a title-mode search. A "title" that just repeats one
 * of the tags (the model sometimes promotes a tag to the title) or is too short would only
 * pull in unrelated works.
 */
export function isUsableTitle(title: string, tags: string[]): boolean {
  const normalized = normalizeTitle(title)
  if (normalized.length < 4) return false
  for (const tag of tags || []) {
    if (normalizeTitle(tag) === normalized) return false
  }
  return true
}
