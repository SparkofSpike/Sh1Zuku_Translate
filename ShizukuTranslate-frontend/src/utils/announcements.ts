import type { Announcement } from '../types'

/**
 * Pick the announcements the panel should show: the newest ones, newest first.
 *
 * The endpoint currently returns announcements in reverse chronological order, but the
 * sort is applied here anyway so that "the latest few" stays a property of this view
 * rather than a silent dependency on the backend's ordering. The input array is not
 * modified.
 */
export function selectRecentAnnouncements(announcements: Announcement[], limit = 3): Announcement[] {
  return [...announcements]
    .sort((a, b) => b.createdAt.localeCompare(a.createdAt))
    .slice(0, limit)
}
