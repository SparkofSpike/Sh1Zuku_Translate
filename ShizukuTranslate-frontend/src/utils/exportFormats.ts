/**
 * Format-specific export builders: TXT, DOC (HTML), DOCX (OOXML) and PDF.
 *
 * Every export is a plain document that starts with a credit/disclaimer block
 * (the "watermark"), passed in by the caller as an array of output lines —
 * an empty string renders as a blank line. No graphics or rotated shapes,
 * just readable gray text at the very top, then the translation body.
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

/** Content of a `.doc` export: the credit block is a sequence of paragraphs at the top. */
const DOC_HTML_TEMPLATE = `<!DOCTYPE html>
<html xmlns:o="urn:schemas-microsoft-com:office:office" xmlns:w="urn:schemas-microsoft-com:office:word" xmlns="http://www.w3.org/TR/REC-html40">
<head>
<meta charset="utf-8">
<title>{TITLE}</title>
<style>
  body { font-family: 'MS Mincho', 'SimSun', serif; font-size: 12pt; }
  .credit p { color: #808080; margin: 0; }
  pre { white-space: pre-wrap; word-wrap: break-word; font-family: inherit; }
</style>
</head>
<body>
<div class="credit">{WATERMARK}</div>
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

/** Credit block as one plain-text chunk (lines joined by \n), for TXT. */
function creditText(creditLines: string[]): string {
  return creditLines.join('\n')
}

/** Credit block as HTML paragraphs (empty line → spacer), for DOC. */
function creditHtml(creditLines: string[]): string {
  return creditLines
    .map(line => (line ? `<p>${escapeHtml(line)}</p>` : '<p>&nbsp;</p>'))
    .join('\n')
}

async function exportTxt(text: string, creditLines: string[]): Promise<void> {
  const content = `${creditText(creditLines)}\n\n${text}\n`
  downloadBlob(new Blob([content], { type: 'text/plain;charset=utf-8' }), buildExportFilename('txt'))
}

function exportDoc(text: string, creditLines: string[]): void {
  const html = DOC_HTML_TEMPLATE
    .replace('{TITLE}', 'Sh1Zuku_Translate')
    .replace('{WATERMARK}', creditHtml(creditLines))
    .replace('{BODY}', escapeHtml(text))
  // Word opens this MHTML-style HTML in editing mode (the xmlns:w declaration is the switch).
  downloadBlob(
    new Blob(['\ufeff', html], { type: 'application/msword;charset=utf-8' }),
    buildExportFilename('doc')
  )
}

async function exportDocx(text: string, creditLines: string[]): Promise<void> {
  // Gray credit paragraphs first (an empty entry renders as a blank line), then the body.
  const creditParagraphs = creditLines.map(line =>
    new Paragraph({
      alignment: AlignmentType.LEFT,
      children: [new TextRun({ text: line, color: '808080', italics: true })],
    })
  )
  const bodyParagraphs = text
    .replace(/\r\n?/g, '\n')
    .split('\n')
    .map(line =>
      new Paragraph({
        alignment: AlignmentType.LEFT,
        children: [new TextRun({ text: line, font: 'MS Mincho' })]
      })
    )

  const doc = new Document({
    sections: [
      {
        properties: {},
        children: [...creditParagraphs, ...bodyParagraphs]
      }
    ]
  })

  const blob = await Packer.toBlob(doc)
  downloadBlob(blob, buildExportFilename('docx'))
}

function exportPdf(text: string, creditLines: string[]): void {
  const doc = new jsPDF({ unit: 'pt', format: 'a4' })
  const pageWidth = doc.internal.pageSize.getWidth()
  const pageHeight = doc.internal.pageSize.getHeight()
  const margin = 48
  const contentWidth = pageWidth - margin * 2
  const lineHeight = 17
  const fontSize = 12

  doc.setFont('helvetica', 'normal')
  doc.setFontSize(fontSize)

  // Every credit line wraps independently (blank line stays blank), then the body follows.
  const lines: { text: string; gray: boolean }[] = []
  for (const credit of creditLines) {
    if (!credit) {
      lines.push({ text: '', gray: true })
      continue
    }
    for (const wrapped of wrapTextByWidth(credit, contentWidth, chunk => doc.getTextWidth(chunk))) {
      lines.push({ text: wrapped, gray: true })
    }
  }
  for (const line of wrapTextByWidth(text, contentWidth, chunk => doc.getTextWidth(chunk))) {
    lines.push({ text: line, gray: false })
  }

  const linesPerPage = Math.floor((pageHeight - margin * 2) / lineHeight)
  for (let i = 0; i < lines.length; i++) {
    const pageIndex = Math.floor(i / linesPerPage)
    const lineIndex = i % linesPerPage
    if (i > 0 && lineIndex === 0) doc.addPage()
    const line = lines[i]
    if (!line.text) continue
    doc.setTextColor(line.gray ? 128 : 20)
    doc.text(line.text, margin, margin + (lineIndex + 1) * lineHeight)
  }

  downloadBlob(doc.output('blob'), buildExportFilename('pdf'))
}

/**
 * Export `text` as `format`. `creditLines` is the credit/disclaimer block at the
 * top of the output (localized, with commit/model filled in by the caller);
 * an empty entry renders as a blank line.
 * The promise resolves once the file has been handed to the browser.
 */
export async function exportTranslation(text: string, format: ExportFormat, creditLines: string[]): Promise<void> {
  if (!text.trim()) return
  switch (format) {
    case 'txt':
      return exportTxt(text, creditLines)
    case 'doc':
      return exportDoc(text, creditLines)
    case 'docx':
      return exportDocx(text, creditLines)
    case 'pdf':
      return exportPdf(text, creditLines)
  }
}
