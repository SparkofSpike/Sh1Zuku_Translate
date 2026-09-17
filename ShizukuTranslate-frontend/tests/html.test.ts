import { describe, expect, it } from 'vitest'
import { escapeAttr, escapeHtml } from '../src/utils/html'

describe('html escaping helpers', () => {
  it('escapeAttr neutralises double quotes and angle brackets', () => {
    expect(escapeAttr('a"b<c>')).toBe('a&quot;b&lt;c&gt;')
    expect(escapeAttr('a&b')).toBe('a&amp;b')
  })

  it('escapeHtml neutralises markup characters', () => {
    expect(escapeHtml('<img src=x onerror=alert(1)>')).toBe(
      '&lt;img src=x onerror=alert(1)&gt;')
    expect(escapeHtml("single 'quote'")).toContain('&#39;')
  })

  it('leaves plain text unchanged', () => {
    expect(escapeHtml('plain text 123')).toBe('plain text 123')
  })
})
