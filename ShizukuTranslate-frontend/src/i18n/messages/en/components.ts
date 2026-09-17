export default {
  presetSelector: {
    label: 'Extra presets (multiple choices allowed)'
  },
  ocrPreview: {
    imageAlt: 'Uploaded image {index}',
    removeImage: 'Remove this one',
    removeAll: 'Remove all',
    remove: 'Remove',
    recognizing: 'Recognising...',
    paddleOcr: 'PaddleOCR',
    paddleOcrWithCount: 'PaddleOCR ({count} images)',
    repairSegments: 'Repair segmentation',
    thresholdLabel: 'Confidence:'
  },
  // Shared by TranslateResult.vue and SseTranslateResult.vue: both render the same result block.
  translateResult: {
    heading: 'Translation result',
    // `|` must be escaped as {'|'} or vue-i18n parses it as the plural separator.
    tokenUsage: "Token usage: input {prompt} {'|'} output {completion} {'|'} total {total}"
  },
  export: {
    label: 'Export',
    exporting: 'Exporting…',
    failed: 'Export failed, please try again',
    // First line of every exported file. {commit} = site build commit, {model} = model used.
    creditLine: 'This article was translated by Sh1Zuku_Translate (build {commit}) using the model {model}, for study and exchange only. '
      + 'All source code is open-sourced under the MIT license at https://github.com/SparkofSpike/Sh1Zuku_Translate. '
      + 'Found a problem? Please open an issue/PR; if you like it, please give us a star! '
      + 'Disclaimer: AI-generated results may be inaccurate — please double-check. Sh1Zuku_Translate and its maintainer Sh1Zuku accept no legal liability for anything arising from this translation. '
      + 'When republishing, you may trim this notice but must keep labels such as "AI translation" or "machine translation". '
      + 'Long live fandom! Long live love! The text follows —',
    format: {
      docx: 'Word document (.docx)',
      doc: 'Word document (.doc)',
      pdf: 'PDF document (.pdf)',
      txt: 'Plain text (.txt)'
    }
  },
  announcementPanel: {
    heading: 'Announcements',
    collapse: 'Collapse',
    expand: 'Expand',
    empty: 'No announcements'
  },
  announcementConfirmDialog: {
    dialogLabel: 'Announcements to confirm',
    heading: 'Announcements',
    intro: '{count} announcements need your read and confirmation; once confirmed they will not pop up again',
    confirming: 'Confirming...',
    acknowledge: 'I have read and confirm',
    confirmFailed: 'Confirmation failed, please try again'
  }
}
