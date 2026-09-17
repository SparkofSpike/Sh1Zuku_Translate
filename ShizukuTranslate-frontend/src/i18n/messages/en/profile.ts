export default {
  account: {
    username: 'Username:',
    email: 'Email:',
    createdAt: 'Registered:'
  },
  badge: {
    verified: '✓ Verified',
    unverified: 'Unverified'
  },
  emailVerify: {
    title: 'Email verification',
    hint: 'Send a verification code to the email address above and enter it to complete verification. Unverified accounts cannot use translation (web or extension); to change your email, simply edit the address above and verify again.',
    emailPlaceholder: 'Email address',
    resendIn: 'Resend in {seconds}s',
    sending: 'Sending...',
    sendCode: 'Send verification code',
    codePlaceholder: 'Verification code',
    submitting: 'Submitting...',
    submit: 'Verify and save',
    validityHint: 'The code is valid for 10 minutes. If the original inbox can no longer receive mail, change the address above before requesting a new code.',
    verified: 'Email verified.',
    changeEmail: 'Change email',
    codeSent: 'Verification code sent, please check your inbox',
    success: 'Email verified — translation is now available'
  },
  usage: {
    label: 'Total token usage',
    meta: 'Input {input} · Output {output} · {count} calls'
  },
  model: {
    title: 'Model profiles',
    hint: 'You can save several profiles and pick one separately for web translation and the browser extension.',
    add: 'Add profile',
    site: 'Site',
    siteMeta: 'Uses the DeepSeek API key provided by the site',
    selected: 'Selected',
    apiKeyPrefix: 'API Key: ',
    usingSiteKey: 'Using the site key',
    empty: 'No personal model profiles yet; the site key is in use.',
    editTitle: 'Edit model profile',
    createTitle: 'New model profile',
    selectedSite: 'Site profile selected',
    selectedPersonal: 'Personal model profile selected',
    noModels: 'The provider returned no model list; please enter the model name manually',
    detected: 'Detected {count} models — tick the ones you want to save',
    saved: 'Model profile saved',
    keyCleared: 'Key cleared; the site key will be used',
    deleted: 'Model profile deleted',
    confirmDelete: 'Delete this model profile?'
  },
  form: {
    name: 'Profile name',
    provider: 'Protocol',
    modelName: 'Model name',
    modelPlaceholder: 'Enter a model name',
    addModel: 'Add',
    detecting: 'Detecting...',
    detect: 'Auto-detect',
    removeModel: 'Remove {model}',
    detectedTitle: 'Detected models (multiple choices allowed):',
    multiHint: 'You can tick several models at once; each one is saved as its own profile.',
    baseUrl: 'Base URL',
    apiKey: 'API Key',
    apiKeyKeep: 'Leave blank to keep the current key',
    apiKeyOptional: 'For DeepSeek, leave blank to use the site key',
    currentKeyPrefix: 'Current key: ',
    saving: 'Saving...',
    save: 'Save profile',
    clearKey: 'Clear key'
  },
  provider: {
    deepseek: 'DeepSeek',
    openai: 'OpenAI-compatible',
    anthropic: 'Anthropic-compatible'
  },
  plugin: {
    title: 'Extension API key',
    hint: 'Used by the browser extension to call the translation API. Save it carefully after generating — it is shown only once.',
    generate: 'Generate extension key'
  },
  errors: {
    loadProfile: 'Failed to load your profile',
    enterValidEmail: 'Please enter a valid email address first',
    codeSendFailed: 'Failed to send the verification code',
    enterCode: 'Please enter the code from the email',
    verifyFailed: 'Email verification failed',
    loadProfiles: 'Failed to load model profiles',
    apiKeyRequired: 'Enter an API key before detecting models',
    detectFailed: 'Model detection failed; you can enter the model name manually',
    modelRequired: 'Add at least one model name',
    saveFailed: 'Failed to save the model profile',
    clearKeyFailed: 'Failed to clear the key',
    deleteFailed: 'Failed to delete the model profile',
    loadUsage: 'Failed to load usage data',
    generateFailed: 'Generation failed',
    copyFailed: 'Copy failed; please select and copy manually',
    loadKeys: 'Failed to load extension keys',
    deleteKeyFailed: 'Deletion failed'
  }
}
