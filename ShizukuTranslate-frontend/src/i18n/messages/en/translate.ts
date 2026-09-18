export default {
  heading: 'Novel Translation',
  emailGate: {
    title: 'Email not verified',
    desc1: 'To keep the service stable and prevent abuse, you need to verify your email before using the translation feature.',
    desc2: 'Verification takes only a minute and does not affect your history or model configuration; the browser extension also requires an email-verified account.',
    action: 'Verify your email on the “Profile” page'
  },
  openSource: {
    lead: 'This project is open source: ',
    tail: ' — your stars and follows keep me motivated to keep updating!',
    plugin: 'A browser extension is in active development, expected to be ready in early September…'
  },
  placeholder: 'Paste the source text, or drop a TXT / MD / image file here — you can also use the button in the bottom-right corner to upload…',
  upload: {
    label: '📎 Upload',
    title: 'Upload images / TXT / MD'
  },
  targetLanguage: 'Target language',
  streaming: 'Stream output',
  imageMode: {
    label: 'Image processing:',
    model: 'Model',
    ocr: 'OCR'
  },
  customPrompt: 'Custom extra prompt (optional)',
  start: 'Start translation',
  cancel: 'Cancel translation',
  retranslate: 'Re-translate',
  retranslateHint: 'Force re-translation: ignores the cache and existing translations, calls the AI again',
  siteModelPrefix: 'Site',
  status: {
    preparing: 'Connecting to the server…',
    translating: 'AI is translating…',
    translatingWithCount: 'AI is translating… ({count} characters received)'
  },
  errors: {
    tooManyImages: 'You can upload at most {max} images at a time',
    tooManyImagesKept: 'You can upload at most {max} images at a time; only the first {max} were kept',
    noText: 'No text recognised',
    ocrFailed: 'OCR request failed',
    imageModelFailed: 'Image model processing failed',
    translateFailed: 'Translation failed'
  }
}
