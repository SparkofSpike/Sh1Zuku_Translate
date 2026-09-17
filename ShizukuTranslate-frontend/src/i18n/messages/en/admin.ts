export default {
  heading: 'Admin panel',
  subtitle: 'Model usage overview',
  refreshData: 'Refresh data',
  loading: 'Loading...',
  loadingUsage: 'Loading usage...',
  loadingLogs: 'Loading logs...',
  none: 'None',
  metrics: {
    totalTokens: 'Total tokens',
    promptTokens: 'Input tokens',
    completionTokens: 'Output tokens',
    requestCount: 'Requests'
  },
  chart: {
    daily: 'Last 14 days',
    barTitle: '{date}: {tokens} tokens',
    model: 'Usage by model',
    noModelUsage: 'No model calls recorded'
  },
  users: {
    title: 'Usage by account',
    username: 'Account',
    totalTokens: 'Total tokens',
    requestCount: 'Requests',
    latestUsedAt: 'Last used',
    detail: 'Details'
  },
  announce: {
    publish: 'Publish announcement',
    titlePlaceholder: 'Announcement title',
    contentModeLabel: 'Announcement content editing mode',
    preview: 'Preview',
    contentPlaceholder: 'Announcement content',
    emptyPreview: 'Nothing to preview yet',
    requireConfirmation: 'Require user confirmation: this announcement pops up when users visit the site and stops popping up once they confirm it',
    publishing: 'Publishing...',
    publishedTitle: 'Published announcements',
    needConfirm: 'Confirmation required',
    acknowledgements: 'Confirmations',
    noConfirmNeeded: 'No confirmation needed',
    restoreConfirm: 'Require confirmation again',
    updating: 'Updating...',
    empty: 'No announcements'
  },
  detail: {
    title: 'Token log for {username}',
    total: 'Total',
    prompt: 'Input',
    completion: 'Output',
    time: 'Time',
    provider: 'Protocol',
    model: 'Model',
    source: 'Source',
    sum: 'Sum',
    estimated: 'Estimated',
    cacheBackfill: 'Actual (cache)',
    actual: 'Actual',
    empty: 'No token usage logs'
  },
  acks: {
    title: 'Users who confirmed “{title}”',
    total: '{count} users have confirmed',
    username: 'Username',
    email: 'Email',
    time: 'Confirmed at',
    empty: 'No users have confirmed this announcement'
  },
  presets: {
    title: 'Translation presets',
    subtitle: 'Presets are injected into the system prompt by name; saving takes effect immediately without a redeployment',
    namePlaceholder: 'Preset name (e.g. Kaguya!)',
    promptPlaceholder: 'Preset content (translation rules appended to the system prompt)',
    create: 'Create preset',
    update: 'Save changes',
    saving: 'Saving...',
    empty: 'No presets',
    confirmDelete: 'Delete this preset? Users will no longer be able to select it.',
    errors: {
      load: 'Failed to load presets',
      fillNamePrompt: 'Please provide the preset name and content',
      save: 'Failed to save the preset',
      delete: 'Failed to delete the preset'
    },
    messages: {
      created: 'Preset created',
      updated: 'Preset updated'
    }
  },
  logs: {
    title: 'Extension logs',
    subtitle: 'Error reports submitted by the browser extension',
    submitter: 'Submitted by',
    version: 'Version',
    submittedAt: 'Submitted at',
    errorMessage: 'Error message',
    empty: 'No logs',
    prev: 'Previous',
    page: 'Page {page}',
    next: 'Next'
  },
  provider: {
    openai: 'OpenAI-compatible'
  },
  errors: {
    loadUsage: 'Failed to load token usage',
    loadUserLogs: 'Failed to load token logs',
    loadAnnouncements: 'Failed to load announcements: ',
    fillTitleContent: 'Please provide the announcement title and content',
    publishFailed: 'Publishing failed',
    loadAcks: 'Failed to load the confirmation list',
    updateFailed: 'Failed to update the announcement',
    deleteFailed: 'Deletion failed',
    loadLogs: 'Failed to load logs'
  },
  messages: {
    published: 'Announcement published'
  },
  confirmDelete: 'Delete this announcement?'
}
