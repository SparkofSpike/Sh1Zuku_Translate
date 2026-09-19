export default {
  title: 'Plugin authorization',
  permission: 'The plugin will gain access to your account and can translate with the models and presets configured on this site.',
  code: 'Authorization code',
  allow: 'Allow',
  cancel: 'Cancel',
  submitting: 'Authorizing…',
  success: 'Authorization succeeded. Go back to the plugin window to finish the setup; you can close this page.',
  successKeyName: 'Key name for this authorization: {name}',
  cancelled: 'Authorization cancelled. You can close this page; the plugin will keep waiting until it times out.',
  errors: {
    incompleteTitle: 'Incomplete link',
    incomplete: 'This link is missing its authorization code. Please start the authorization again from the plugin window.',
    invalidOrExpired: 'That authorization code is invalid or has expired. Please get a new code from the plugin window.',
    emailNotVerified: 'Your account email is not verified yet. Please verify it first, then come back to this page to authorize again.',
    emailNotVerifiedAction: 'Verify email',
    alreadyApproved: 'That authorization code has already been used. Please get a new code from the plugin window.',
    unknown: 'Authorization failed, please try again later.'
  }
}
