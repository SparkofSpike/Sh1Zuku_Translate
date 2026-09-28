import { describe, expect, it } from 'vitest'
import { selectRecentAnnouncements } from '../src/utils/announcements'
import type { Announcement } from '../src/types'

function makeAnnouncement(id: number, createdAt: string): Announcement {
  return { id, title: `t${id}`, content: `c${id}`, createdAt }
}

const FIVE = [
  makeAnnouncement(1, '2026-09-01T10:00:00'),
  makeAnnouncement(2, '2026-09-03T10:00:00'),
  makeAnnouncement(3, '2026-09-02T10:00:00'),
  makeAnnouncement(4, '2026-09-05T10:00:00'),
  makeAnnouncement(5, '2026-09-04T10:00:00')
]

describe('selectRecentAnnouncements', () => {
  it('keeps at most the three newest announcements, newest first', () => {
    expect(selectRecentAnnouncements(FIVE).map(a => a.id)).toEqual([4, 5, 2])
  })

  it('does not depend on the order the API returned them in', () => {
    const shuffled = [FIVE[2], FIVE[4], FIVE[0], FIVE[3], FIVE[1]]
    expect(selectRecentAnnouncements(shuffled).map(a => a.id)).toEqual([4, 5, 2])
  })

  it('returns every announcement when there are fewer than three', () => {
    const two = [makeAnnouncement(1, '2026-09-01T10:00:00'), makeAnnouncement(2, '2026-09-02T10:00:00')]
    expect(selectRecentAnnouncements(two).map(a => a.id)).toEqual([2, 1])
  })

  it('returns an empty array when there are none', () => {
    expect(selectRecentAnnouncements([])).toEqual([])
  })

  it('does not mutate the input array', () => {
    const input = [...FIVE]
    selectRecentAnnouncements(input)
    expect(input.map(a => a.id)).toEqual([1, 2, 3, 4, 5])
  })
})
