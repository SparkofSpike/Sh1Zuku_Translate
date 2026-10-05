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
 * number of recognised tags the candidate also carries breaks ties below that.
 */
export function scoreCandidate(info: PixivExtractResponse, item: PixivSearchItem): number {
  const titleScore = titleMatches(info.title, item.title) ? 1000 : 0
  const wanted = new Set((info.tags || []).map(normalizeTag).filter(Boolean))
  const present = new Set((item.tags || []).map(normalizeTag))
  let overlap = 0
  for (const tag of wanted) {
    if (present.has(tag)) overlap++
  }
  return titleScore + Math.min(overlap, 9) * 10
}

/** A candidate scoring at least this much carries a title match: import it directly. */
export const AUTO_IMPORT_SCORE = 1000
