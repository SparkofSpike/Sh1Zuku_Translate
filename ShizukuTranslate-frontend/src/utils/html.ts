/**
 * Escape a string for safe use inside double-quoted HTML attribute values.
 * Escaping for text content is handled by `escapeHtml`.
 */
export function escapeAttr(value: string): string {
  return value
    .replace(/&/g, '&amp;')
    .replace(/"/g, '&quot;')
    .replace(/</g, '&lt;')
    .replace(/>/g, '&gt;')
}

/** Escape a string for safe interpolation into element text content. */
export function escapeHtml(value: string): string {
  return value
    .replace(/&/g, '&amp;')
    .replace(/</g, '&lt;')
    .replace(/>/g, '&gt;')
    .replace(/"/g, '&quot;')
    .replace(/'/g, '&#39;')
}

/** A preset row as returned by GET /api/v1/admin/presets (id + name + prompt). */
export interface AdminPreset {
  id: number
  name: string
  prompt: string
  createdAt?: string
}
