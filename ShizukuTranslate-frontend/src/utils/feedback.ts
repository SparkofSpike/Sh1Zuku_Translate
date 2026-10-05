import api from '../api'

/**
 * Anonymous-feedback helpers: one persisted device fingerprint plus a fire-and-forget event
 * reporter. Both endpoints accept anonymous callers, so nothing here waits on auth.
 */
const DEVICE_FP_KEY = 'feedbackDeviceFp'

/**
 * 32 hex chars (16 random bytes), the shape the backend expects.
 *
 * The site can be served over plain http, where the crypto API is not a secure context:
 * `crypto.randomUUID()` and `crypto.subtle` simply do not exist there, so only
 * `crypto.getRandomValues()` is used, with a Math.random fallback for anything older or
 * policy-blocked. A weaker fingerprint beats a thrown error on the translate path.
 */
function randomHex32(): string {
  const bytes = new Uint8Array(16)
  try {
    crypto.getRandomValues(bytes)
    return Array.from(bytes, b => b.toString(16).padStart(2, '0')).join('')
  } catch (e) {
    let fallback = ''
    while (fallback.length < 32) {
      fallback += Math.floor(Math.random() * 0x100000000).toString(16).padStart(8, '0')
    }
    return fallback.slice(0, 32)
  }
}

/** Stable per-browser id: generated once and then reused, so ratings group by device. */
export function getDeviceFp(): string {
  try {
    const stored = localStorage.getItem(DEVICE_FP_KEY)
    if (stored) return stored
  } catch (e) {
    // Private mode can throw on every storage access; report with a throwaway id instead.
    return randomHex32()
  }
  const fingerprint = randomHex32()
  try {
    localStorage.setItem(DEVICE_FP_KEY, fingerprint)
  } catch (e) {
    // Not being able to persist it only costs us cross-visit grouping.
  }
  return fingerprint
}

/**
 * Reports a usage event (copy / retranslate / edit). Deliberately fire-and-forget: callers are
 * UI handlers that must not await it, and every failure stays a debug log — telemetry must never
 * surface as a user-visible error.
 */
export function reportFeedbackEvent(requestId: string, event: string, payload?: object): void {
  if (!requestId) return
  api
    .post('/feedback/event', {
      requestId,
      event,
      deviceFp: getDeviceFp(),
      permalink: window.location.pathname,
      payload: payload ?? {}
    })
    .catch(err => {
      console.debug('反馈埋点上报失败', err)
    })
}
