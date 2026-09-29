import type { Announcement } from '../types'

/**
 * Sort the announcements the panel should show, newest first.
 *
 * The panel used to keep only the three newest entries; it now lists every
 * announcement in a scrollable region, so the only job left here is a defensive
 * sort. The endpoint currently returns announcements in reverse chronological
 * order, but the sort is applied here anyway so that "newest first" stays a
 * property of this view rather than a silent dependency on the backend's
 * ordering. The input array is not modified.
 */
export function sortAnnouncementsNewestFirst(announcements: Announcement[]): Announcement[] {
  return [...announcements].sort((a, b) => b.createdAt.localeCompare(a.createdAt))
}
