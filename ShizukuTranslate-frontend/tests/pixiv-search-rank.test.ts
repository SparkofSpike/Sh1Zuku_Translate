import { describe, expect, it } from 'vitest'
import { AUTO_IMPORT_SCORE, concreteTags, isUsableTitle, normalizeTagForSearch, scoreCandidate, tagSearchKeywords, titleMatches, titleSearchChunk } from '../src/utils/pixivSearch'
import type { PixivExtractResponse, PixivSearchItem } from '../src/types'

/**
 * Ranking behaviour behind "paste a screenshot → the original work is found". The fixture
 * data is taken from a real production run: the screenshot extractor returned this title and
 * tag list for the user's test image, and Pixiv returned these candidates.
 */

const recognised: PixivExtractResponse = {
  title: '私の幸せな日々',
  author: '',
  tags: ['BLACKSOULS', 'BLACKSOULS2', '紅ずきん', '小红帽'],
  summary: ''
}

function item(partial: Partial<PixivSearchItem>): PixivSearchItem {
  return { id: 'x', title: '', author: '', tags: [], xRestrict: 0, description: '', textCount: 0, ...partial }
}

describe('screenshot search ranking', () => {
  it('ranks the exact work above every other candidate (the regression case)', () => {
    const target = item({ id: '28032455', title: '私の幸せな日々', tags: ['BLACKSOULS', 'BLACKSOULS2', '紅ずきん'] })
    const lookalike = item({ id: '28521517', title: '私の幸せな日常', tags: ['ようこそ実力至上主義の教室へ'] })
    const tagOnly = item({ id: '29211377', title: '旅の劇団と黒の不死者', tags: ['BLACKSOULS', 'Fate/GrandOrder'] })

    const ranked = [lookalike, tagOnly, target]
      .map(candidate => ({ item: candidate, score: scoreCandidate(recognised, candidate) }))
      .sort((a, b) => b.score - a.score)

    expect(ranked[0].item.id).toBe('28032455')
    expect(ranked[0].score).toBeGreaterThanOrEqual(AUTO_IMPORT_SCORE)
    // The near-miss title must never reach auto-import level.
    expect(ranked.find(entry => entry.item.id === '28521517')!.score).toBeLessThan(AUTO_IMPORT_SCORE)
  })

  it('a title match outranks any amount of tag overlap', () => {
    const titleMatch = item({ id: 'a', title: '私の幸せな日々', tags: [] })
    const tagHeavy = item({ id: 'b', title: '全然違う話', tags: ['BLACKSOULS', 'BLACKSOULS2', '紅ずきん', '小红帽'] })
    expect(scoreCandidate(recognised, titleMatch)).toBeGreaterThan(scoreCandidate(recognised, tagHeavy))
  })

  it('below the title level, more tag overlap wins', () => {
    const twoTags = item({ id: 'a', title: '別の小説', tags: ['BLACKSOULS', 'BLACKSOULS2'] })
    const oneTag = item({ id: 'b', title: '別の小説', tags: ['BLACKSOULS'] })
    expect(scoreCandidate(recognised, twoTags)).toBeGreaterThan(scoreCandidate(recognised, oneTag))
  })

  it('matches titles tolerantly (decoration, punctuation, case)', () => {
    expect(titleMatches('私の幸せな日々', '【改稿】私の幸せな日々（完結）')).toBe(true)
    expect(titleMatches('DEATH NOTE', 'Death Note')).toBe(true)
    // Too short to be meaningful: a 1-character title would match half the site.
    expect(titleMatches('短', '短編集')).toBe(false)
  })

  it('extracts the longest clean title run for title-mode search', () => {
    expect(titleSearchChunk('第四篇（中） 未知的命运，未尽的余韵，未卜的前路--X.命运之轮（逆位）')).toBe('未知的命运')
    expect(titleSearchChunk('私の幸せな日々')).toBe('私の幸せな日々')
  })
})

describe('tag normalisation and generic filtering (the R-18 / BLACKSOULSⅡ regression)', () => {
  it('normalises roman digits so BLACKSOULSⅡ still finds BLACKSOULS2', () => {
    expect(normalizeTagForSearch('BLACKSOULSⅡ')).toBe('BLACKSOULS2')
    expect(normalizeTagForSearch('VOL.Ⅲ')).toBe('VOL.3')
    expect(normalizeTagForSearch('１２３')).toBe('123')
  })

  it('builds both keyword variants when digits differ', () => {
    expect(tagSearchKeywords(['BLACKSOULSⅡ'])).toEqual(['BLACKSOULSⅡ', 'BLACKSOULS2'])
    expect(tagSearchKeywords(['私の幸せな日々'])).toEqual(['私の幸せな日々'])
    expect(tagSearchKeywords([])).toEqual([])
  })

  it('filters generic tags from search planning', () => {
    // 'R-18 AND BLACKSOULS2' zeroes out on Pixiv (the work carries no R-18 tag); the
    // concrete tag alone is the query that actually finds it.
    const concrete = concreteTags(['R-18', 'BLACKSOULSⅡ'])
    expect(concrete).toEqual(['BLACKSOULSⅡ'])
    expect(tagSearchKeywords(concrete)).toEqual(['BLACKSOULSⅡ', 'BLACKSOULS2'])
    // All-generic input falls back to the original list rather than searching nothing.
    expect(concreteTags(['R-18', 'オリジナル'])).toEqual(['R-18', 'オリジナル'])
  })

  it('rejects a tag promoted to the title field', () => {
    expect(isUsableTitle('R-18', ['R-18', 'BLACKSOULSⅡ'])).toBe(false)
    expect(isUsableTitle('BLACKSOULSⅡ', ['BLACKSOULSⅡ'])).toBe(false)
    expect(isUsableTitle('私の幸せな日々', ['BLACKSOULS2'])).toBe(true)
    expect(isUsableTitle('', [])).toBe(false)
  })

  it('scores digit-normalised tag overlap and ignores generic tags', () => {
    const info: PixivExtractResponse = { title: '', author: '', tags: ['R-18', 'BLACKSOULSⅡ'], summary: '' }
    const target = item({ id: 't', title: '元作品', tags: ['BLACKSOULS', 'BLACKSOULS2'] })
    const unrelated = item({ id: 'u', title: '别的作品', tags: ['R-18', 'Gore'] })
    expect(scoreCandidate(info, target)).toBeGreaterThan(scoreCandidate(info, unrelated))
    // The unrelated work carries R-18 too, which is generic and must not score.
    expect(scoreCandidate(info, unrelated)).toBe(0)
  })
})
