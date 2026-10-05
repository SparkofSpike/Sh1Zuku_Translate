import { describe, expect, it } from 'vitest'
import { AUTO_IMPORT_SCORE, concreteTags, isTrustedKeyword, isUsableTitle, normalizeTagForSearch, PRECISE_VARIANT_BONUS, scoreCandidate, tagSearchKeywords, TITLE_EXACT_SCORE, titleMatches, titleSearchChunk, titleSearchKeywords } from '../src/utils/pixivSearch'
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

  it('treats two CJK characters as a distinctive title (熱平衡 case)', () => {
    // The model read a three-character title; the old four-character floor dropped the
    // whole title search. CJK is information-dense and must clear a lower bar.
    expect(titleMatches('熱平衡', '熱平衡')).toBe(true)
    expect(titleMatches('熱平衡', 'ある夜の熱平衡')).toBe(true)
    expect(isUsableTitle('熱平衡', ['超かぐや姫!', '酒寄彩葉'])).toBe(true)
    // Latin stays strict.
    expect(titleMatches('R-18', 'R-18')).toBe(false)
  })

  it('prefers an exact title over a containment match (same-title decoy)', () => {
    const info: PixivExtractResponse = {
      title: '熱平衡',
      author: '海鷂魚',
      tags: ['超かぐや姫!', '酒寄彩葉', '月見ヤチヨ', '現パロ', '曲パロ'],
      summary: ''
    }
    const exact = scoreCandidate(info, item({ id: 'exact', title: '熱平衡', tags: ['超かぐや姫!', '酒寄彩葉', '月見ヤチヨ', '現パロ'] }))
    const sameTitleOtherWork = scoreCandidate(info, item({ id: 'decoy', title: '熱平衡 ', tags: ['つりライフ', 'つりぷら'] }))
    const contained = scoreCandidate(info, item({ id: 'contained', title: 'ある夜の熱平衡', tags: ['腐向け'] }))
    expect(exact).toBeGreaterThanOrEqual(AUTO_IMPORT_SCORE)
    // The user's screenshot showed the work with the matching tag set; it must win over a
    // same-titled work with different tags, which in turn beats the containment match.
    expect(exact).toBeGreaterThan(sameTitleOtherWork)
    expect(sameTitleOtherWork).toBeGreaterThan(contained)
    expect(sameTitleOtherWork).toBe(TITLE_EXACT_SCORE)
  })

  it('extracts the longest clean title run for title-mode search', () => {
    expect(titleSearchChunk('第四篇（中） 未知的命运，未尽的余韵，未卜的前路--X.命运之轮（逆位）')).toBe('未知的命运')
    expect(titleSearchChunk('私の幸せな日々')).toBe('私の幸せな日々')
  })

  it('builds title search keywords: the full title first, the longest run as fallback', () => {
    // The regression case: the model read only the tail of the title. The full string as
    // read is searched first (Pixiv matches it as-is), and the longest clean run backs it up.
    expect(titleSearchKeywords('紅ずきんとグリムの秘話　ラドヴィッジ市街上層にて'))
      .toEqual(['紅ずきんとグリムの秘話　ラドヴィッジ市街上層にて', 'ラドヴィッジ市街上層にて'])
    expect(titleSearchKeywords('私の幸せな日々')).toEqual(['私の幸せな日々'])
    expect(titleSearchKeywords('')).toEqual([])
  })
})

describe('tag normalisation and generic filtering (the R-18 / BLACKSOULSⅡ regression)', () => {
  it('normalises roman digits so BLACKSOULSⅡ still finds BLACKSOULS2', () => {
    expect(normalizeTagForSearch('BLACKSOULSⅡ')).toBe('BLACKSOULS2')
    expect(normalizeTagForSearch('VOL.Ⅲ')).toBe('VOL.3')
    expect(normalizeTagForSearch('１２３')).toBe('123')
  })

  it('builds both keyword variants when digits differ, trusted form first', () => {
    expect(tagSearchKeywords(['BLACKSOULSⅡ'])).toEqual(['BLACKSOULS2', 'BLACKSOULSⅡ'])
    expect(tagSearchKeywords(['私の幸せな日々'])).toEqual(['私の幸せな日々'])
    expect(tagSearchKeywords([])).toEqual([])
  })

  it('flags which keywords are trustworthy', () => {
    expect(isTrustedKeyword('BLACKSOULS2')).toBe(true)
    expect(isTrustedKeyword('私の幸せな日々')).toBe(true)
    expect(isTrustedKeyword('BLACKSOULSⅡ')).toBe(false)
    expect(isTrustedKeyword('VOL.Ⅲ')).toBe(false)
  })

  it('ranks a trusted-query hit above a work merely tagged with the roman form', () => {
    // The regression pair from production: 28610144 literally carries the roman tag, while
    // 28032455 is the work the screenshot actually showed (tagged BLACKSOULS2). Both overlap
    // one recognised tag; the trusted hit must come first.
    const info: PixivExtractResponse = { title: '', author: '', tags: ['R-18', 'BLACKSOULSⅡ'], summary: '' }
    const romanTagged = item({ id: '28610144', title: '第 二 乃幕 【確認】', tags: ['BLACKSOULSⅡ'] })
    const target = item({ id: '28032455', title: '私の幸せな日々', tags: ['BLACKSOULS2'] })
    const scoreRoman = scoreCandidate(info, romanTagged, false)
    const scoreTarget = scoreCandidate(info, target, true)
    expect(scoreTarget).toBeGreaterThan(scoreRoman)
    expect(scoreTarget - scoreRoman).toBe(PRECISE_VARIANT_BONUS)
  })

  it('filters generic tags from search planning', () => {
    // 'R-18 AND BLACKSOULS2' zeroes out on Pixiv (the work carries no R-18 tag); the
    // concrete tag alone is the query that actually finds it.
    const concrete = concreteTags(['R-18', 'BLACKSOULSⅡ'])
    expect(concrete).toEqual(['BLACKSOULSⅡ'])
    expect(tagSearchKeywords(concrete)).toEqual(['BLACKSOULS2', 'BLACKSOULSⅡ'])
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
