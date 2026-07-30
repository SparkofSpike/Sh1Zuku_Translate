import axios from 'axios'
import type { AxiosResponse } from 'axios'
import type { TranslateRequest, TranslateResponse, OcrResponse } from '../types'

const api = axios.create({
  baseURL: (import.meta.env.VITE_API_BASE_URL as string) || 'http://localhost:5566/api/v1'
})

api.interceptors.request.use(config => {
  const token = localStorage.getItem('token')
  if (token) {
    config.headers.Authorization = 'Bearer ' + token
  }
  return config
})

export function ocrImage(file: File, polish: boolean = true, threshold: number = 0.5) {
  const formData = new FormData()
  formData.append('image', file)
  formData.append('polish', String(polish))
  formData.append('threshold', String(threshold))
  return api.post<OcrResponse>('/ocr', formData, {
    headers: { 'Content-Type': 'multipart/form-data' }
  })
}

export function checkOcrHealth() {
  return api.get('/ocr/health')
}

export function translateStream(
  sourceText: string,
  model: string,
  customPrompt: string | undefined,
  presets: string[] | undefined,
  onToken: (token: string) => void,
  onDone: (response: TranslateResponse) => void,
  onError: (error: string) => void
): AbortController {
  const controller = new AbortController()

  const token = localStorage.getItem('token')
  const headers: Record<string, string> = { 'Content-Type': 'application/json' }
  if (token) {
    headers['Authorization'] = 'Bearer ' + token
  }

  fetch(api.defaults.baseURL + '/translate/stream', {
    method: 'POST',
    headers,
    body: JSON.stringify({ sourceText, model, customPrompt, presets } as TranslateRequest),
    signal: controller.signal
  }).then(async response => {
    if (!response.ok) {
      const text = await response.text().catch(() => '')
      onError('SSE error ' + response.status + ': ' + text)
      return
    }
    const reader = response.body?.getReader()
    if (!reader) { onError('Stream not supported'); return }
    const decoder = new TextDecoder()
    let buffer = ''
    while (true) {
      const { done, value } = await reader.read()
      if (done) break
      buffer += decoder.decode(value, { stream: true })
      const lines = buffer.split('\n')
      buffer = lines.pop() || ''
      for (const line of lines) {
        if (line.startsWith('data: ')) {
          const data = line.slice(6)
          try {
            const parsed = JSON.parse(data)
            if (parsed.token) onToken(parsed.token)
            if (parsed.done) onDone(parsed)
            if (parsed.error) onError(parsed.error)
          } catch (e) {}
        }
      }
    }
  }).catch(err => onError(err.message))

  return controller
}

export default api
