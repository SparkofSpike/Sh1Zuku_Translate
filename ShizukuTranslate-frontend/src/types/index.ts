export interface TranslateRequest {
  sourceText: string
  model?: string
  customPrompt?: string
  presets?: string[]
}

export interface TokenUsage {
  promptTokens: number
  completionTokens: number
  totalTokens: number
}

export interface TranslateResponse {
  id: number
  translatedText: string
  model: string
  createdAt: string
  tokenUsage?: TokenUsage
}

export interface OcrResponse {
  text: string
  lines: number
  success: boolean
}

export interface LoginRequest {
  username: string
  password: string
}

export interface RegisterRequest {
  username: string
  password: string
  email?: string
}

export interface UserInfo {
  id: number
  username: string
  email: string
  isAdmin: boolean
}

export interface HistoryRecord {
  id: number
  sourceText: string
  translatedText: string
  model: string
  customPrompt?: string
  createdAt: string
}

export interface SurveyRequest {
  translationQuality: number
  experienceQuality: number
  favoriteFeature: string
  suggestion?: string
}

export interface SurveyStats {
  total: number
  avgTranslationQuality: number
  avgExperienceQuality: number
  records: any[]
}
