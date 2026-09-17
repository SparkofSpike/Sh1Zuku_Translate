/**
 * Format-specific export builders: TXT, DOC (HTML), DOCX (OOXML) and PDF.
 *
 * Every export is a plain document whose first line credits the tool, e.g.
 * "本文由 ShizukuTranslate 翻译生成 · 2026-09-18" (the line text comes from
 * the i18n messages and follows the interface language). No graphics or
 * rotated shapes — just one readable line of text at the very top.
 */
import {
  AlignmentType,
  Document,
  Paragraph,
  Packer,
  TextRun
} from 'docx'
import { jsPDF } from 'jspdf'
import {
  buildExportFilename,
  downloadBlob,
  wrapTextByWidth,
  type ExportFormat
} from './export'

export type { ExportFormat }

/** Content of a `.doc` export: the credit line is a plain paragraph at the top. */
const DOC_HTML_TEMPLATE = `<!DOCTYPE html>
<html xmlns:o="urn:schemas-microsoft-com:office:office" xmlns:w="urn:schemas-microsoft-com:office:word" xmlns="http://www.w3.org/TR/REC-html40">
<head>
<meta charset="utf-8">
<title>{TITLE}</title>
<style>
  body { font-family: 'MS Mincho', 'SimSun', serif; font-size: 12pt; }
  .credit { color: #808080; }
  pre { white-space: pre-wrap; word-wrap: break-word; font-family: inherit; }
</style>
</head>
<body>
<p class="credit">{WATERMARK}</p>
<pre>{BODY}</pre>
</body>
</html>`

function escapeHtml(value: string): string {
  return value
    .replace(/&/g, '&amp;')
    .replace(/</g, '&lt;')
    .replace(/>/g, '&gt;')
    .replace(/"/g, '&quot;')
}

async function exportTxt(text: string, creditLine: string): Promise<void> {
  const content = `${creditLine}\n\n${text}\n`
  downloadBlob(new Blob([content], { type: 'text/plain;charset=utf-8' }), buildExportFilename('txt'))
}

function exportDoc(text: string, creditLine: string): void {
  const html = DOC_HTML_TEMPLATE
    .replace('{TITLE}', 'ShizukuTranslate')
    .replace('{WATERMARK}', escapeHtml(creditLine))
    .replace('{BODY}', escapeHtml(text))
  // Word opens this MHTML-style HTML in editing mode (the xmlns:w declaration is the switch).
  downloadBlob(
    new Blob(['\ufeff', html], { type: 'application/msword;charset=utf-8' }),
    buildExportFilename('doc')
  )
}

async function exportDocx(text: string, creditLine: string): Promise<void> {
  // First paragraph: the gray credit line, then the translation body.
  const paragraphs = [
    new Paragraph({
      alignment: AlignmentType.LEFT,
      children: [new TextRun({ text: creditLine, color: '808080', italics: true })],
    }),
    ...text
      .replace(/\r\n?/g, '\n')
      .split('\n')
      .map(line =>
        new Paragraph({
          alignment: AlignmentType.LEFT,
          children: [new TextRun({ text: line, font: 'MS Mincho' })]
        })
      )
  ]

  const doc = new Document({
    sections: [
      {
        properties: {},
        children: paragraphs
      }
    ]
  })

  const blob = await Packer.toBlob(doc)
  downloadBlob(blob, buildExportFilename('docx'))
}

function exportPdf(text: string, creditLine: string): void {
  const doc = new jsPDF({ unit: 'pt', format: 'a4' })
  const pageWidth = doc.internal.pageSize.getWidth()
  const pageHeight = doc.internal.pageSize.getHeight()
  const margin = 48
  const contentWidth = pageWidth - margin * 2
  const lineHeight = 17
  const fontSize = 12

  doc.setFont('helvetica', 'normal')
  doc.setFontSize(fontSize)

  // The credit line renders first (slightly gray), the body starts below it.
  const lines: { text: string; gray: boolean }[] = [{ text: creditLine, gray: true }]
  for (const line of wrapTextByWidth(text, contentWidth, chunk => doc.getTextWidth(chunk))) {
    lines.push({ text: line, gray: false })
  }

  const linesPerPage = Math.floor((pageHeight - margin * 2) / lineHeight)
  for (let i = 0; i < lines.length; i++) {
    const pageIndex = Math.floor(i / linesPerPage)
    const lineIndex = i % linesPerPage
    if (i > 0 && lineIndex === 0) doc.addPage()
    const line = lines[i]
    doc.setTextColor(line.gray ? 128 : 20)
    doc.text(line.text, margin, margin + (lineIndex + 1) * lineHeight)
  }

  downloadBlob(doc.output('blob'), buildExportFilename('pdf'))
}

/**
 * Export `text` as `format`. `creditLine` is the first line of the output
 * (localized, with the export date already filled in by the caller).
 * The promise resolves once the file has been handed to the browser.
 */
export async function exportTranslation(text: string, format: ExportFormat, creditLine: string): Promise<void> {
  if (!text.trim()) return
  switch (format) {
    case 'txt':
      return exportTxt(text, creditLine)
    case 'doc':
      return exportDoc(text, creditLine)
    case 'docx':
      return exportDocx(text, creditLine)
    case 'pdf':
      return exportPdf(text, creditLine)
  }
}
