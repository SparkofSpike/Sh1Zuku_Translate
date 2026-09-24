import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { translateImagesStream, translateStream } from '../src/api'
import type { TranslateResponse } from '../src/types'

/**
 * The streaming client is exercised against a stubbed fetch so the SSE framing (split records,
 * a trailing record without a final newline) and the multipart upload shape stay pinned down.
 */

function sseResponse(chunks: string[]) {
  const encoder = new TextEncoder()
  const body = new ReadableStream<Uint8Array>({
    start(controller) {
      for (const chunk of chunks) controller.enqueue(encoder.encode(chunk))
      controller.close()
    }
  })
  return new Response(body, { status: 200, headers: { 'Content-Type': 'text/event-stream' } })
}

const DONE_EVENT = 'data: {"done":true,"id":7,"translatedText":"你好","model":"m","createdAt":"x"}\n\n'

beforeEach(() => {
  vi.stubGlobal('localStorage', {
    getItem: () => 'test-token',
    setItem: () => {},
    removeItem: () => {}
  })
})

afterEach(() => {
  vi.unstubAllGlobals()
})

describe('translateStream', () => {
  it('forwards tokens and completes with the final response', async () => {
    vi.stubGlobal('fetch', vi.fn(async () => sseResponse([
      'data: {"token":"你"}\n\n',
      'data: {"token":"好"}\n\n',
      DONE_EVENT
    ])))

    const tokens: string[] = []
    let done: TranslateResponse | null = null
    translateStream('source', 'm', null, undefined, undefined, 'zh-CN',
      token => tokens.push(token), response => { done = response }, () => {})

    await vi.waitFor(() => expect(done).not.toBeNull())
    expect(tokens).toEqual(['你', '好'])
    expect((done as unknown as TranslateResponse).translatedText).toBe('你好')
  })

  it('keeps a final record that arrives without a trailing newline', async () => {
    vi.stubGlobal('fetch', vi.fn(async () => sseResponse([
      'data: {"token":"半"}\n\n',
      'data: {"token":"句"}\n\ndata: {"done":true,"id":8,"translatedText":"半句","model":"m","createdAt":"x"}'
    ])))

    const tokens: string[] = []
    let done: TranslateResponse | null = null
    translateStream('source', 'm', null, undefined, undefined, 'zh-CN',
      token => tokens.push(token), response => { done = response }, () => {})

    await vi.waitFor(() => expect(done).not.toBeNull())
    expect(tokens).toEqual(['半', '句'])
  })

  it('shows the backend error message instead of the raw status', async () => {
    vi.stubGlobal('fetch', vi.fn(async () => new Response(
      JSON.stringify({ error: '邮箱尚未验证' }), { status: 403 })))

    let error = ''
    translateStream('source', 'm', null, undefined, undefined, 'zh-CN',
      () => {}, () => {}, message => { error = message })

    await vi.waitFor(() => expect(error).not.toBe(''))
    expect(error).toBe('邮箱尚未验证')
  })
})

describe('translateImagesStream', () => {
  it('uploads multipart pages and consumes the streamed answer', async () => {
    const calls: Array<{ url: string; init: RequestInit }> = []
    vi.stubGlobal('fetch', vi.fn(async (url: string, init: RequestInit) => {
      calls.push({ url, init })
      return sseResponse([
        'data: {"token":"译"}\n\n',
        'data: {"token":"文"}\n\n',
        'data: {"done":true,"id":9,"translatedText":"译文","model":"m","createdAt":"x"}\n\n'
      ])
    }))

    const file = new File([new Uint8Array([1, 2, 3])], 'page.png', { type: 'image/png' })
    const tokens: string[] = []
    let done = false
    translateImagesStream([file], { sourceText: '' },
      token => tokens.push(token), () => { done = true }, () => {})

    await vi.waitFor(() => expect(done).toBe(true))
    expect(tokens).toEqual(['译', '文'])
    expect(calls[0].url).toContain('/translate/image/stream')
    // The browser must add the multipart boundary itself, so no Content-Type is set by hand.
    expect((calls[0].init.headers as Record<string, string>)['Content-Type']).toBeUndefined()
    expect((calls[0].init.headers as Record<string, string>)['Authorization']).toBe('Bearer test-token')
    const body = calls[0].init.body as FormData
    expect(body).toBeInstanceOf(FormData)
    expect(body.getAll('images')).toHaveLength(1)
    expect(await (body.get('request') as Blob).text()).toContain('"sourceText":""')
  })

  it('surfaces a rejected upload as the error message', async () => {
    vi.stubGlobal('fetch', vi.fn(async () => new Response(
      JSON.stringify({ error: '一次最多处理 10 张图片' }), { status: 400 })))

    let error = ''
    translateImagesStream([new File([new Uint8Array([1])], 'p.png', { type: 'image/png' })], { sourceText: '' },
      () => {}, () => {}, message => { error = message })

    await vi.waitFor(() => expect(error).not.toBe(''))
    expect(error).toBe('一次最多处理 10 张图片')
  })
})
