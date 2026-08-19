<template>
  <aside class="announcement-panel">
    <h3 class="announcement-heading">公告</h3>
    <div v-if="announcements.length" class="announcement-list">
      <article v-for="announcement in announcements" :key="announcement.id" class="announcement-item">
        <h4>{{ announcement.title }}</h4>
        <time>{{ formatDate(announcement.createdAt) }}</time>
        <p :class="{ collapsed: !isExpanded(announcement.id) }">{{ announcement.content }}</p>
        <button class="announcement-toggle" @click="toggleExpanded(announcement.id)">
          {{ isExpanded(announcement.id) ? '收起' : '展开' }}
        </button>
      </article>
    </div>
    <p v-else class="empty-text">暂无公告</p>
  </aside>
</template>

<script setup lang="ts">
import { ref } from 'vue'
import type { Announcement } from '../types'

defineProps<{
  announcements: Announcement[]
}>()

const expandedIds = ref<Set<number>>(new Set())

function isExpanded(id: number) {
  return expandedIds.value.has(id)
}

function toggleExpanded(id: number) {
  const next = new Set(expandedIds.value)
  if (next.has(id)) next.delete(id)
  else next.add(id)
  expandedIds.value = next
}

function formatDate(value: string) {
  return value.replace('T', ' ').slice(0, 16)
}
</script>

<style scoped>
.announcement-panel {
  width: 100%;
  padding: 16px;
  border: 1px solid #eee;
  border-radius: 8px;
  background: #fff;
}

.announcement-heading {
  margin: 0 0 12px;
  font-size: 16px;
  font-weight: 600;
}

.announcement-list {
  display: flex;
  flex-direction: column;
  gap: 14px;
}

.announcement-item {
  padding-bottom: 14px;
  border-bottom: 1px solid #f0f0f0;
}

.announcement-item:last-child {
  padding-bottom: 0;
  border-bottom: 0;
}

.announcement-item h4 {
  margin: 0;
  font-size: 14px;
  font-weight: 600;
  line-height: 1.5;
  overflow-wrap: anywhere;
}

.announcement-item time {
  display: block;
  margin-top: 3px;
  color: #999;
  font-size: 12px;
}

.announcement-item p {
  margin: 7px 0 0;
  color: #555;
  font-size: 13px;
  line-height: 1.6;
  white-space: pre-wrap;
  overflow-wrap: anywhere;
}

.announcement-toggle {
  display: none;
  margin-top: 5px;
  padding: 0;
  background: transparent;
  color: #666;
  font-size: 12px;
}

.empty-text {
  margin: 0;
  color: #999;
  font-size: 13px;
}

@media (max-width: 720px) {
  .announcement-item p.collapsed {
    display: -webkit-box;
    overflow: hidden;
    -webkit-box-orient: vertical;
    -webkit-line-clamp: 3;
  }

  .announcement-toggle {
    display: inline-block;
  }
}
</style>
