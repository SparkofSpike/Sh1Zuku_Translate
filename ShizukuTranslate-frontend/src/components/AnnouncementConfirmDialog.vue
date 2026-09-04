<template>
  <div class="modal-backdrop">
    <section class="modal card ack-dialog" role="dialog" aria-modal="true" aria-label="待确认公告">
      <header class="ack-header">
        <h3>公告</h3>
        <p class="muted">有 {{ announcements.length }} 条公告需要您阅读并确认，确认后不再自动弹出</p>
      </header>

      <div class="ack-body">
        <article v-for="announcement in announcements" :key="announcement.id" class="ack-item">
          <h4>{{ announcement.title }}</h4>
          <time>{{ formatDate(announcement.createdAt) }}</time>
          <div class="ack-markdown" v-html="renderMarkdown(announcement.content)"></div>
          <p v-if="errorId === announcement.id" class="ack-error">{{ errorText }}</p>
          <button
            class="btn-confirm"
            :disabled="confirmingId === announcement.id"
            @click="confirm(announcement)"
          >
            {{ confirmingId === announcement.id ? '确认中...' : '我已阅读并确认' }}
          </button>
        </article>
      </div>
    </section>
  </div>
</template>

<script setup lang="ts">
import { ref } from 'vue'
import type { Announcement } from '../types'
import { renderMarkdown } from '../utils/markdown'
import api from '../api'

defineProps<{
  announcements: Announcement[]
}>()

const emit = defineEmits<{
  (e: 'confirmed', announcementId: number): void
}>()

const confirmingId = ref<number | null>(null)
const errorId = ref<number | null>(null)
const errorText = ref('')

async function confirm(announcement: Announcement) {
  confirmingId.value = announcement.id
  errorId.value = null
  errorText.value = ''
  try {
    await api.post('/announcements/' + announcement.id + '/acknowledge')
    emit('confirmed', announcement.id)
  } catch (err) {
    const axiosError = err as { response?: { data?: { error?: string } } }
    errorId.value = announcement.id
    errorText.value = axiosError.response?.data?.error || '确认失败，请重试'
  } finally {
    confirmingId.value = null
  }
}

function formatDate(value: string) {
  return value.replace('T', ' ').slice(0, 16)
}
</script>

<style scoped>
.modal-backdrop {
  position: fixed;
  z-index: 1000;
  inset: 0;
  display: flex;
  align-items: center;
  justify-content: center;
  padding: 20px;
  background: rgba(0, 0, 0, 0.45);
}

.ack-dialog {
  width: min(680px, 100%);
  max-height: min(80vh, 640px);
  margin: 0;
  display: flex;
  flex-direction: column;
  padding: 20px 22px;
  box-shadow: 0 16px 50px rgba(0, 0, 0, 0.25);
}

.ack-header {
  flex-shrink: 0;
  padding-bottom: 12px;
  border-bottom: 1px solid #eee;
}

.ack-header h3 {
  margin: 0;
  font-size: 17px;
  font-weight: 600;
}

.ack-header .muted {
  margin: 3px 0 0;
  color: #777;
  font-size: 13px;
}

.ack-body {
  overflow-y: auto;
  padding: 4px 2px 2px;
}

.ack-item {
  padding: 14px 0;
  border-bottom: 1px solid #f0f0f0;
}

.ack-item:last-child {
  border-bottom: 0;
}

.ack-item h4 {
  margin: 0;
  font-size: 15px;
  font-weight: 600;
  line-height: 1.5;
  overflow-wrap: anywhere;
}

.ack-item time {
  display: block;
  margin-top: 3px;
  color: #999;
  font-size: 12px;
}

.ack-markdown {
  margin: 7px 0 0;
  color: #555;
  font-size: 13px;
  line-height: 1.6;
  overflow-wrap: anywhere;
}

.ack-markdown :deep(p),
.ack-markdown :deep(ul),
.ack-markdown :deep(ol),
.ack-markdown :deep(blockquote),
.ack-markdown :deep(pre) {
  margin: 0 0 7px;
}

.ack-markdown :deep(p:last-child),
.ack-markdown :deep(ul:last-child),
.ack-markdown :deep(ol:last-child),
.ack-markdown :deep(blockquote:last-child),
.ack-markdown :deep(pre:last-child) {
  margin-bottom: 0;
}

.ack-markdown :deep(ul),
.ack-markdown :deep(ol) {
  padding-left: 20px;
}

.ack-markdown :deep(blockquote) {
  padding-left: 10px;
  border-left: 3px solid #ddd;
  color: #777;
}

.ack-markdown :deep(code) {
  padding: 1px 4px;
  border-radius: 3px;
  background: #f1f1f1;
  font-size: 12px;
}

.ack-markdown :deep(pre) {
  padding: 8px 10px;
  overflow-x: auto;
  border-radius: 4px;
  background: #f5f5f5;
}

.ack-markdown :deep(pre code) {
  padding: 0;
  background: transparent;
}

.ack-markdown :deep(a) {
  color: #444;
  text-decoration: underline;
}

.ack-error {
  margin: 8px 0 0;
  color: #e03131;
  font-size: 13px;
}

.btn-confirm {
  display: block;
  margin: 10px 0 0;
  padding: 7px 18px;
  border-radius: 6px;
  background: #1a1a1a;
  color: #fff;
  font-size: 13px;
}

.btn-confirm:hover:not(:disabled) {
  background: #444;
}
</style>
