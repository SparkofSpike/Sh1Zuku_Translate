/**
 * Shared helpers for exporting translation results as files.
 * Format-specific builders live in `exportFormats.ts`; the UI component is `ExportBar.vue`.
 */

/** Supported export formats, keyed by file extension. */
export type ExportFormat = 'txt' | 'doc' | 'docx' | 'pdf'

/** Supported characters of `name` are kept; the rest become underscores. */
export function sanitizeFilename(name: string): string {
  const cleaned = name.replace(/[\\/:*?"<>|\r\n]+/g, '_').replace(/^_+|_+$/g, '').trim()
  return cleaned || 'translation'
}

/** Build the download filename, e.g. `ShizukuTranslate_20260918_142530.txt`. */
export function buildExportFilename(format: ExportFormat, now: Date = new Date()): string {
  const pad = (n: number) => String(n).padStart(2, '0')
  const stamp = `${now.getFullYear()}${pad(now.getMonth() + 1)}${pad(now.getDate())}_${pad(now.getHours())}${pad(now.getMinutes())}${pad(now.getSeconds())}`
  return `ShizukuTranslate_${stamp}.${format}`
}

/**
 * Trigger a browser download for the given blob without leaving the page.
 * Uses the object-URL path (secure contexts), with a data-URL fallback.
 */
export function downloadBlob(blob: Blob, filename: string): void {
  if (typeof URL !== 'undefined' && typeof URL.createObjectURL === 'function') {
    const url = URL.createObjectURL(blob)
    triggerAnchorDownload(url, filename)
    // Give the browser a tick to start the download before revoking.
    setTimeout(() => URL.revokeObjectURL(url), 10_000)
    return
  }
  // Fallback for non-secure contexts where createObjectURL may be unavailable.
  const reader = new FileReader()
  reader.onload = () => triggerAnchorDownload(String(reader.result), filename)
  reader.readAsDataURL(blob)
}

function triggerAnchorDownload(href: string, filename: string): void {
  const a = document.createElement('a')
  a.href = href
  a.download = filename
  a.style.display = 'none'
  document.body.appendChild(a)
  a.click()
  document.body.removeChild(a)
}

/** Full-width CJK punctuation may not be broken from its neighbours. */
const NO_BREAK_BEFORE = new Set('、。，．：；？！ー」』）》〉】〕％℃‰“”')
const NO_BREAK_AFTER = new Set('「『（《〈【〔“')

/**
 * Wrap plain text to fit a given width, in jsPDF units.
 *
 * jsPDF's `splitTextToSize` only breaks at spaces, which never occur in
 * Chinese/Japanese text, so lines are built by measuring every character
 * instead. `measure` must return the rendered width of a string.
 */
export function wrapTextByWidth(
  text: string,
  maxWidth: number,
  measure: (chunk: string) => number
): string[] {
  const lines: string[] = []
  for (const rawLine of text.replace(/\r\n?/g, '\n').split('\n')) {
    if (!rawLine) {
      lines.push('')
      continue
    }
    let current = ''
    let currentWidth = 0
    for (const char of rawLine) {
      const charWidth = measure(char)
      if (current && currentWidth + charWidth > maxWidth && !NO_BREAK_AFTER.has(current[current.length - 1]) && !NO_BREAK_BEFORE.has(char)) {
        lines.push(current)
        current = char
        currentWidth = charWidth
      } else {
        current += char
        currentWidth += charWidth
      }
    }
    if (current) lines.push(current)
  }
  return lines
}
