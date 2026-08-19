export interface TranslateRequest {
  sourceText: string
  model?: string
  modelProfileId?: number | null
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

export interface Announcement {
  id: number
  title: string
  content: string
  createdAt: string
}

export interface UsageSummary {
  promptTokens: number
  completionTokens: number
  totalTokens: number
  requestCount: number
  latestUsedAt?: string | null
}

export interface UsageDay {
  date: string
  totalTokens: number
}

export interface UsageModel {
  provider: string
  model: string
  totalTokens: number
}

export interface UsageUser extends UsageSummary {
  id: number
  username: string
  email: string
}

export interface UsageLog extends UsageSummary {
  id: number
  provider: string
  model: string
  sourceType?: string
  estimated?: boolean
  createdAt: string
}
