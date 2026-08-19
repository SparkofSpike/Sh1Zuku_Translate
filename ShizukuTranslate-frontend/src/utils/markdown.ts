const SAFE_LINK_PROTOCOL = /^(https?:|mailto:)/i

function escapeHtml(value: string): string {
  return value
    .replace(/&/g, '&amp;')
    .replace(/</g, '&lt;')
    .replace(/>/g, '&gt;')
    .replace(/"/g, '&quot;')
    .replace(/'/g, '&#39;')
}

function safeHref(value: string): string | null {
  const href = value.trim()
  return SAFE_LINK_PROTOCOL.test(href) ? href : null
}

function inlineMarkdown(value: string): string {
  const replacements: string[] = []
  const placeholder = (html: string) => {
    const index = replacements.push(html) - 1
    return `\u0000${index}\u0000`
  }

  let text = value
    .replace(/`([^`\n]+)`/g, (_, code: string) => placeholder(`<code>${escapeHtml(code)}</code>`))
    .replace(/!\[([^\]]*)\]\((\S+?)(?:\s+["']([^"']*)["'])?\)/g, (_, label: string) =>
      placeholder(`[图片：${escapeHtml(label)}]`),
    )
    .replace(/\[([^\]]+)\]\((\S+?)(?:\s+["']([^"']*)["'])?\)/g, (_, label: string, href: string, title?: string) => {
      const safe = safeHref(href)
      if (!safe) return placeholder(escapeHtml(label))
      const titleAttribute = title ? ` title="${escapeHtml(title)}"` : ''
      return placeholder(`<a href="${escapeHtml(safe)}"${titleAttribute} target="_blank" rel="noopener noreferrer">${escapeHtml(label)}</a>`)
    })

  text = escapeHtml(text)
    .replace(/\*\*([^*\n]+)\*\*/g, '<strong>$1</strong>')
    .replace(/__([^_\n]+)__/g, '<strong>$1</strong>')
    .replace(/~~([^~\n]+)~~/g, '<del>$1</del>')
    .replace(/(^|[^*])\*([^*\n]+)\*(?!\*)/g, '$1<em>$2</em>')
    .replace(/(^|[^_])_([^_\n]+)_(?!_)/g, '$1<em>$2</em>')

  return text.replace(/\u0000(\d+)\u0000/g, (_, index: string) => replacements[Number(index)] || '')
}

/**
 * Render the supported Markdown subset as generated HTML.
 * Raw HTML is escaped and links are restricted to safe protocols.
 */
export function renderMarkdown(markdown: string | null | undefined): string {
  if (!markdown) return ''

  const lines = markdown.replace(/\r\n?/g, '\n').split('\n')
  const output: string[] = []
  let paragraph: string[] = []
  let listType: 'ul' | 'ol' | null = null
  let listItems: string[] = []
  let inCodeBlock = false
  let codeLines: string[] = []

  const flushParagraph = () => {
    if (paragraph.length) {
      output.push(`<p>${inlineMarkdown(paragraph.join('\n')).replace(/\n/g, '<br>')}</p>`)
      paragraph = []
    }
  }

  const flushList = () => {
    if (listType && listItems.length) {
      output.push(`<${listType}>${listItems.map(item => `<li>${inlineMarkdown(item)}</li>`).join('')}</${listType}>`)
    }
    listType = null
    listItems = []
  }

  const flushBlocks = () => {
    flushParagraph()
    flushList()
  }

  for (const line of lines) {
    if (inCodeBlock) {
      if (/^\s*```/.test(line)) {
        output.push(`<pre><code>${escapeHtml(codeLines.join('\n'))}</code></pre>`)
        inCodeBlock = false
        codeLines = []
      } else {
        codeLines.push(line)
      }
      continue
    }

    if (/^\s*```/.test(line)) {
      flushBlocks()
      inCodeBlock = true
      continue
    }

    const heading = line.match(/^\s{0,3}(#{1,6})\s+(.+?)\s*#*\s*$/)
    if (heading) {
      flushBlocks()
      const level = heading[1].length
      output.push(`<h${level}>${inlineMarkdown(heading[2])}</h${level}>`)
      continue
    }

    const quote = line.match(/^\s*>\s?(.*)$/)
    if (quote) {
      flushBlocks()
      output.push(`<blockquote>${inlineMarkdown(quote[1])}</blockquote>`)
      continue
    }

    const unordered = line.match(/^\s*[-+*]\s+(.+)$/)
    const ordered = line.match(/^\s*\d+[.)]\s+(.+)$/)
    if (unordered || ordered) {
      flushParagraph()
      const nextType = unordered ? 'ul' : 'ol'
      if (listType && listType !== nextType) flushList()
      listType = nextType
      listItems.push((unordered || ordered)![1])
      continue
    }

    if (/^\s*(---+|\*\*\*+)\s*$/.test(line)) {
      flushBlocks()
      output.push('<hr>')
      continue
    }

    if (!line.trim()) {
      flushBlocks()
      continue
    }

    if (listType) flushList()
    paragraph.push(line)
  }

  if (inCodeBlock) output.push(`<pre><code>${escapeHtml(codeLines.join('\n'))}</code></pre>`)
  flushBlocks()
  return output.join('')
}
