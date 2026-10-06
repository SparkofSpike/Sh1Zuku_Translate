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
    plugin: 'The browser extension is now available to try!'
  },
  placeholder: 'Paste the source text, or drop a TXT / MD / image file here — you can also use the button in the bottom-right corner to upload…',
  upload: {
    label: '📎 Upload',
    title: 'Upload images / TXT / MD'
  },
  pixiv: {
    placeholder: 'Paste a Pixiv novel link to import its text…',
    action: 'Import',
    loading: 'Importing…',
    untitled: '(untitled)',
    imported: 'Imported “{title}”',
    importedWithAuthor: 'Imported “{title}” (by {author})',
    failed: 'Import failed — check that the link is correct',
    titleLabel: 'Title',
    authorLabel: 'Author',
    tagsLabel: 'Tags',
    descriptionLabel: 'Summary',
    includeMetadata: 'Include title / author / tags / summary in the translation',
    translateMetadata: 'Translate title & summary',
    metadataLoading: 'Translating…',
    metadataFailed: 'Title/summary translation failed',
    metadataResultTitle: 'Title / summary translation',
    panelTitle: 'Import from Pixiv',
    pasteHint: 'Paste a screenshot, drop it here, or click to choose an image',
    maxImages: 'Up to 3 images at a time',
    recognizing: 'Reading the screenshot…',
    recognizeFailed: 'Could not read the screenshot — try a clearer one',
    loginRequired: 'Sign in to use screenshot recognition',
    extractedInfo: 'What we read from the screenshot',
    searchKeywordLabel: 'Search keyword',
    searchAction: 'Search again',
    modeTag: 'By tag',
    modeTitle: 'By title',
    searchMatched: 'Searched {keyword}: {count} result(s)',
    searchTriedAll: 'No match found for {keyword}',
    autoImported: 'Imported the matched original work: {title}',
    autoCandidates: 'Found {count} candidate(s) from the recognised info — confirm with “Import this work”',
    searching: 'Searching…',
    searchFailed: 'Search failed — please try again',
    searchNoResult: 'No matching work found — try a different keyword',
    candidateImport: 'Import this work',
    r18Badge: 'R-18',
    charCount: '{count} characters',
    clear: 'Clear',
    shotClear: 'Cancel recognition'
  },
  targetLanguage: 'Target language',
  streaming: 'Stream output',
  termFix: {
    label: 'Coined-term fix for long novels',
    hint: 'Slower: proper nouns are extracted before translating and the result is audited for consistency afterwards.'
  },
  pipeline: {
    extractRunning: 'Extracting proper nouns…',
    extractDone: 'Extracted {count} proper nouns',
    translating: 'Translating chunk {current}/{total}',
    auditRunning: 'Auditing terminology consistency…',
    auditDone: 'Audit complete — {count} fix(es)',
    auditClean: 'Audit complete — no inconsistencies found',
    auditSkipped: 'Audit skipped (term extraction unavailable)'
  },
  customPrompt: 'Custom extra prompt (optional)',
  start: 'Start translation',
  cancel: 'Cancel translation',
  retranslate: 'Re-translate',
  retranslateHint: 'Force re-translation: ignores the cache and existing translations, calls the AI again',
  siteModelPrefix: 'Site',
  status: {
    preparing: 'Connecting to the server…',
    uploadingImages: 'Uploading images…',
    translating: 'AI is translating…',
    translatingWithCount: 'AI is translating… ({count} characters received)'
  },
  errors: {
    tooManyImages: 'You can upload at most {max} images at a time',
    tooManyImagesKept: 'You can upload at most {max} images at a time; only the first {max} were kept',
    imageModelFailed: 'Image model processing failed',
    translateFailed: 'Translation failed'
  }
}
