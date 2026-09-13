import { createI18n } from 'vue-i18n'
import zhCN from './messages/zh-CN'
import vi from './messages/vi'

/**
 * Interface languages offered by the web app. `code` doubles as the value sent as the
 * translate request's `targetLanguage`, so the UI language and the translation target stay in
 * step by default. The backend's own list (`GET /translation/languages`) is what actually
 * decides which targets are valid; this list only covers what the UI has translations for.
 */
export const SUPPORTED_LOCALES = [
  { code: 'zh-CN', label: '简体中文' },
  { code: 'vi', label: 'Tiếng Việt' }
] as const

export type LocaleCode = (typeof SUPPORTED_LOCALES)[number]['code']

const LOCALE_STORAGE_KEY = 'locale'

export function isSupportedLocale(value: string | null | undefined): value is LocaleCode {
  return !!value && SUPPORTED_LOCALES.some(item => item.code === value)
}

/** Stored choice wins; otherwise follow the browser, falling back to Chinese. */
export function detectLocale(): LocaleCode {
  const stored = localStorage.getItem(LOCALE_STORAGE_KEY)
  if (isSupportedLocale(stored)) return stored
  const browser = (navigator.language || '').toLowerCase()
  if (browser.startsWith('vi')) return 'vi'
  return 'zh-CN'
}

export const i18n = createI18n({
  legacy: false,
  locale: detectLocale(),
  fallbackLocale: 'zh-CN',
  messages: { 'zh-CN': zhCN, vi }
})

export function currentLocale(): LocaleCode {
  return i18n.global.locale.value as LocaleCode
}

export function setLocale(code: LocaleCode) {
  i18n.global.locale.value = code
  localStorage.setItem(LOCALE_STORAGE_KEY, code)
  document.documentElement.lang = code
}
