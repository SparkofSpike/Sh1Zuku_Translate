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
