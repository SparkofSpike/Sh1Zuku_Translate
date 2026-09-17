import { describe, expect, it } from 'vitest'
import {
  buildExportFilename,
  sanitizeFilename,
  wrapTextByWidth
} from '../src/utils/export'

describe('sanitizeFilename', () => {
  it('strips characters that are invalid in filenames', () => {
    expect(sanitizeFilename('a<b>c:d"e/f\\g|h?i*j')).toBe('a_b_c_d_e_f_g_h_i_j')
    expect(sanitizeFilename('正常名称.txt')).toBe('正常名称.txt')
  })

  it('falls back to a default name when nothing valid remains', () => {
    expect(sanitizeFilename('///')).toBe('translation')
    expect(sanitizeFilename('')).toBe('translation')
  })
})

describe('buildExportFilename', () => {
  it('uses the ShizukuTranslate prefix, a timestamp, and the format extension', () => {
    const name = buildExportFilename('pdf', new Date(2026, 8, 18, 7, 5, 3))
    expect(name).toBe('ShizukuTranslate_20260918_070503.pdf')
    expect(buildExportFilename('docx').endsWith('.docx')).toBe(true)
    expect(buildExportFilename('txt').endsWith('.txt')).toBe(true)
    expect(buildExportFilename('doc').endsWith('.doc')).toBe(true)
  })
})

describe('wrapTextByWidth', () => {
  // Monospace stand-in for real text measurement: each char is 10 units wide.
  const measure = (chunk: string) => chunk.length * 10

  it('breaks CJK text that has no spaces at the width limit', () => {
    const lines = wrapTextByWidth('你好世界甲乙丙丁', 40, measure)
    expect(lines).toEqual(['你好世界', '甲乙丙丁'])
  })

  it('keeps explicit line breaks and empty lines', () => {
    const lines = wrapTextByWidth('ab\n\ncd', 1000, measure)
    expect(lines).toEqual(['ab', '', 'cd'])
  })

  it('never breaks inside an ellipsis-like width overflow for a single huge char', () => {
    const lines = wrapTextByWidth('A', 5, measure)
    expect(lines).toEqual(['A'])
  })

  it('normalises CRLF input', () => {
    const lines = wrapTextByWidth('ab\r\ncd', 1000, measure)
    expect(lines).toEqual(['ab', 'cd'])
  })
})
