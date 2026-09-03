<template>
  <aside class="announcement-panel">
    <h3 class="announcement-heading">公告</h3>
    <div v-if="announcements.length" class="announcement-list">
      <article v-for="announcement in announcements" :key="announcement.id" class="announcement-item">
        <h4>{{ announcement.title }}</h4>
        <time>{{ formatDate(announcement.createdAt) }}</time>
        <div
          class="announcement-markdown"
          :class="{
            collapsed: !isExpanded(announcement.id),
            truncated: overlongIds.has(announcement.id) && !isExpanded(announcement.id),
          }"
          :ref="(el) => setContentEl(announcement.id, el)"
          v-html="renderMarkdown(announcement.content)"
        ></div>
        <button
          v-if="overlongIds.has(announcement.id)"
          class="announcement-toggle"
          @click="toggleExpanded(announcement.id)"
        >
          {{ isExpanded(announcement.id) ? '收起' : '展开' }}
        </button>
      </article>
    </div>
    <p v-else class="empty-text">暂无公告</p>
  </aside>
</template>

<script setup lang="ts">
import { ref, watch, nextTick } from 'vue'
import type { Announcement } from '../types'
import { renderMarkdown } from '../utils/markdown'

const props = defineProps<{
  announcements: Announcement[]
}>()

const expandedIds = ref<Set<number>>(new Set())
const overlongIds = ref<Set<number>>(new Set())
const contentEls = new Map<number, HTMLElement>()

function setContentEl(id: number, el: unknown) {
  if (el instanceof HTMLElement) contentEls.set(id, el)
  else contentEls.delete(id)
}

function isExpanded(id: number) {
  return expandedIds.value.has(id)
}

function toggleExpanded(id: number) {
  const next = new Set(expandedIds.value)
  if (next.has(id)) next.delete(id)
  else next.add(id)
  expandedIds.value = next
}

// 折叠态下内容高度超出 max-height（即被裁掉）的公告记作过长：默认折叠并显示「展开」按钮
function measureOverlong() {
  const next = new Set<number>()
  contentEls.forEach((el, id) => {
    // 留 1px 余量避免 max-height 像素取整造成误判；真正溢出时至少会差一整行
    if (el.scrollHeight - el.clientHeight > 1) next.add(id)
  })
  overlongIds.value = next
}

watch(
  () => props.announcements,
  () => {
    // 公告数据变化时回到全部折叠，重新测量哪些过长
    expandedIds.value = new Set()
    nextTick(measureOverlong)
  },
  { immediate: true }
)

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

.announcement-markdown {
  margin: 7px 0 0;
  color: #555;
  font-size: 13px;
  line-height: 1.6;
  overflow-wrap: anywhere;
}

.announcement-markdown :deep(p),
.announcement-markdown :deep(ul),
.announcement-markdown :deep(ol),
.announcement-markdown :deep(blockquote),
.announcement-markdown :deep(pre) {
  margin: 0 0 7px;
}

.announcement-markdown :deep(p:last-child),
.announcement-markdown :deep(ul:last-child),
.announcement-markdown :deep(ol:last-child),
.announcement-markdown :deep(blockquote:last-child),
.announcement-markdown :deep(pre:last-child) {
  margin-bottom: 0;
}

.announcement-markdown :deep(ul),
.announcement-markdown :deep(ol) {
  padding-left: 20px;
}

.announcement-markdown :deep(blockquote) {
  padding-left: 10px;
  border-left: 3px solid #ddd;
  color: #777;
}

.announcement-markdown :deep(code) {
  padding: 1px 4px;
  border-radius: 3px;
  background: #f1f1f1;
  font-size: 12px;
}

.announcement-markdown :deep(pre) {
  padding: 8px 10px;
  overflow-x: auto;
  border-radius: 4px;
  background: #f5f5f5;
}

.announcement-markdown :deep(pre code) {
  padding: 0;
  background: transparent;
}

.announcement-markdown :deep(a) {
  color: #444;
  text-decoration: underline;
}

.announcement-markdown.collapsed {
  /* 默认折叠为 5 行（line-height 1.6），避免过长公告撑高页面 */
  max-height: 8em;
  overflow: hidden;
}

.announcement-markdown.truncated {
  position: relative;
}

/* 折叠截断处右下角追加省略号，提示内容未完 */
.announcement-markdown.truncated::after {
  content: '……';
  position: absolute;
  right: 0;
  bottom: 0;
  padding: 0 2px;
  background: #fff;
  color: #777;
  font-size: 13px;
  line-height: 1.6;
}

.announcement-toggle {
  display: inline-block;
  margin-top: 5px;
  padding: 0;
  border: 0;
  background: transparent;
  color: #666;
  font-size: 12px;
  cursor: pointer;
}

.announcement-toggle:hover {
  /* 覆盖全局 button:hover 的深色背景，保持文本按钮样式 */
  background: transparent;
  color: #333;
}

.empty-text {
  margin: 0;
  color: #999;
  font-size: 13px;
}
</style>
