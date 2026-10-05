import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import type { AxiosRequestConfig, AxiosResponse } from 'axios'
import api, { extractPixivNovelInfo, importPixivNovel, searchPixivNovels } from '../src/api'
import type { PixivSearchItem } from '../src/types'

/**
 * The Pixiv import endpoints are exercised through a stubbed axios adapter, so the request shape
 * the backend contract depends on — the `keyword` / `url` query parameters and the repeated
 * `images` multipart field (one entry per screenshot, in order) — stays pinned down without a
 * running backend.
 */

interface CapturedRequest {
  url?: string
  method?: string
  params?: unknown
  data?: unknown
}

let captured: CapturedRequest | null = null

function stubAdapter(payload: unknown) {
  captured = null
  api.defaults.adapter = async (config: AxiosRequestConfig): Promise<AxiosResponse> => {
    captured = { url: config.url, method: config.method, params: config.params, data: config.data }
    return {
      data: payload,
      status: 200,
      statusText: 'OK',
      headers: {},
      config: config as AxiosResponse['config']
    }
  }
}

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

const CANDIDATE: PixivSearchItem = {
  id: '1234567',
  title: 'タイトル',
  author: '作者',
  tags: ['タグ1', 'タグ2'],
  xRestrict: 1,
  description: 'あらすじ',
  textCount: 4200
}

describe('searchPixivNovels', () => {
  it('sends the keyword as a query parameter and hands the candidates back as-is', async () => {
    stubAdapter([CANDIDATE])

    const res = await searchPixivNovels('タイトル タグ1')

    expect(captured?.url).toBe('/pixiv/search')
    expect(String(captured?.method).toLowerCase()).toBe('get')
    expect(captured?.params).toEqual({ keyword: 'タイトル タグ1' })
    expect(res.data).toEqual([CANDIDATE])
  })

  it('accepts an empty result set', async () => {
    stubAdapter([])

    const res = await searchPixivNovels('nothing matches')

    expect(res.data).toEqual([])
  })
})

describe('extractPixivNovelInfo', () => {
  it('posts each screenshot as a repeated `images` field, in order', async () => {
    stubAdapter({ title: 't', author: 'a', tags: ['x'], summary: 's' })
    const first = new File(['one'], 'one.png', { type: 'image/png' })
    const second = new File(['two'], 'two.png', { type: 'image/png' })

    const res = await extractPixivNovelInfo([first, second])

    const form = captured?.data
    expect(captured?.url).toBe('/pixiv/extract')
    expect(String(captured?.method).toLowerCase()).toBe('post')
    expect(form).toBeInstanceOf(FormData)
    expect((form as FormData).getAll('images').map(entry => (entry as File).name))
      .toEqual(['one.png', 'two.png'])
    expect(res.data.summary).toBe('s')
  })
})

describe('importPixivNovel', () => {
  it('passes a work id through as the `url` parameter', async () => {
    stubAdapter({ novelId: '1234567', title: 't', author: 'a', description: 'd', tags: [], text: 'body', metadataText: 'meta' })

    const res = await importPixivNovel('1234567')

    expect(captured?.url).toBe('/pixiv/novel')
    expect(String(captured?.method).toLowerCase()).toBe('get')
    expect(captured?.params).toEqual({ url: '1234567' })
    expect(res.data.text).toBe('body')
  })
})
