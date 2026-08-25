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

export function translateImage(file: File, request: TranslateRequest) {
  const formData = new FormData()
  formData.append('image', file)
  formData.append('request', new Blob([JSON.stringify(request)], { type: 'application/json' }))
  return api.post<TranslateResponse>('/translate/image', formData, { headers: { 'Content-Type': 'multipart/form-data' } })
}

export function checkOcrHealth() {
  return api.get('/ocr/health')
}

export function translateStream(
  sourceText: string,
  model: string | undefined,
  modelProfileId: number | null | undefined,
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

  let doneReceived = false

  fetch(api.defaults.baseURL + '/translate/stream', {
    method: 'POST',
    headers,
    body: JSON.stringify({ sourceText, model, modelProfileId, customPrompt, presets } as TranslateRequest),
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

    const processLine = (line: string) => {
      if (!line.startsWith('data:')) return
      const data = line.slice(line.indexOf(':') + 1).trim()
      if (!data) return
      try {
        const parsed = JSON.parse(data)
        if (typeof parsed.token === 'string') onToken(parsed.token)
        if (parsed.done) { doneReceived = true; onDone(parsed as unknown as TranslateResponse) }
        if (parsed.error) { doneReceived = true; onError(parsed.error) }
      } catch (e) {
        // Ignore malformed SSE records and continue consuming the stream.
      }
    }

    while (true) {
      const { done, value } = await reader.read()
      if (done) break
      buffer += decoder.decode(value, { stream: true })
      const lines = buffer.split('\n')
      buffer = lines.pop() || ''
      for (const line of lines) processLine(line)
    }

    // Process a final event even if the server closes without a trailing
    // newline; otherwise a final `done` record can be lost.
    buffer += decoder.decode()
    if (buffer) processLine(buffer)
    if (!doneReceived) onError('翻译流意外中断，请重试')
  }).catch(err => {
    // Abort is the expected cancellation path from the web UI, not an
    // error that should overwrite the user's cleared state.
    if (!doneReceived && !controller.signal.aborted) onError(err.message)
  })

  return controller
}

export default api
