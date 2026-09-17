import { describe, expect, it } from 'vitest'
import { renderMarkdown } from '../src/utils/markdown'

describe('renderMarkdown', () => {
  it('renders headings, emphasis, and lists', () => {
    const html = renderMarkdown('# Title\n\n**bold** and *italic*\n\n- one\n- two')
    expect(html).toContain('<h1>Title</h1>')
    expect(html).toContain('<strong>bold</strong>')
    expect(html).toContain('<em>italic</em>')
    expect(html).toContain('<ul>')
    expect(html).toContain('<li>one</li>')
    expect(html).toContain('<li>two</li>')
  })

  it('escapes raw HTML instead of rendering it', () => {
    const html = renderMarkdown('<script>alert(1)</script>')
    expect(html).not.toContain('<script>')
    expect(html).toContain('&lt;script&gt;')
  })

  it('escapes HTML inside link labels and rejects javascript: links', () => {
    const html = renderMarkdown('[<img onerror=alert(1)>](javascript:alert(1))')
    expect(html).not.toContain('href="javascript:')
    expect(html).not.toContain('<img')
    expect(html).toContain('&lt;img')
  })

  it('keeps only safe link protocols', () => {
    const html = renderMarkdown('[site](https://example.com)')
    expect(html).toContain('href="https://example.com"')
    expect(html).toContain('rel="noopener noreferrer"')
  })

  it('renders fenced code blocks with escaped content', () => {
    const html = renderMarkdown('```\n<b>x</b>\n```')
    expect(html).toContain('<pre><code>')
    expect(html).toContain('&lt;b&gt;x&lt;/b&gt;')
  })

  it('returns empty output for empty input', () => {
    expect(renderMarkdown('')).toBe('')
    expect(renderMarkdown(null)).toBe('')
    expect(renderMarkdown(undefined)).toBe('')
  })

  it('normalises CRLF line endings', () => {
    const html = renderMarkdown('# A\r\n\r\nB\r\n')
    expect(html).toContain('<h1>A</h1>')
    expect(html).toContain('<p>B</p>')
  })
})
