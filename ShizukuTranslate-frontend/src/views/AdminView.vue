<template>
  <div class="card">
    <h2 style="margin-top:0; font-weight:600;">管理员面板</h2>

    <section>
      <h3 style="margin:0 0 12px;">发布公告</h3>
      <input
        v-model.trim="title"
        type="text"
        maxlength="100"
        placeholder="公告标题"
        style="margin-bottom:12px;"
      />
      <textarea
        v-model="content"
        rows="5"
        placeholder="公告内容"
      ></textarea>
      <button @click="publish" :disabled="publishing" style="margin-top:12px;">
        {{ publishing ? '发布中...' : '发布公告' }}
      </button>
    </section>

    <p v-if="success" style="color:#2b8a3e; margin-top:12px;">{{ success }}</p>
    <p v-if="error" style="color:#e03131; margin-top:12px;">{{ error }}</p>

    <hr />

    <section>
      <h3 style="margin-top:0;">已发布公告</h3>
      <div v-if="loading" style="color:#777;">加载中...</div>
      <div v-else-if="announcements.length" class="announcement-list">
        <article v-for="announcement in announcements" :key="announcement.id" class="announcement-item">
          <div class="announcement-content">
            <h4>{{ announcement.title }}</h4>
            <time>{{ formatDate(announcement.createdAt) }}</time>
            <p>{{ announcement.content }}</p>
          </div>
          <button class="btn-sm btn-remove" @click="removeAnnouncement(announcement.id)">删除</button>
        </article>
      </div>
      <p v-else style="color:#999;">暂无公告</p>
    </section>
  </div>
</template>

<script setup>
import { ref, onMounted } from 'vue'
import api from '../api'

const title = ref('')
const content = ref('')
const announcements = ref([])
const loading = ref(true)
const publishing = ref(false)
const success = ref('')
const error = ref('')

async function loadAnnouncements() {
  try {
    const res = await api.get('/announcements')
    announcements.value = res.data || []
  } catch (e) {
    error.value = '加载失败：' + (e.response?.data?.error || '无法获取公告')
  } finally {
    loading.value = false
  }
}

async function publish() {
  if (!title.value || !content.value.trim()) {
    error.value = '请填写公告标题和内容'
    success.value = ''
    return
  }

  publishing.value = true
  error.value = ''
  success.value = ''
  try {
    await api.post('/admin/announcements', {
      title: title.value,
      content: content.value
    })
    title.value = ''
    content.value = ''
    success.value = '公告发布成功'
    await loadAnnouncements()
  } catch (e) {
    error.value = e.response?.data?.error || '发布失败'
  } finally {
    publishing.value = false
  }
}

async function removeAnnouncement(id) {
  if (!window.confirm('确定要删除这条公告吗？')) return

  try {
    await api.delete('/admin/announcements/' + id)
    await loadAnnouncements()
  } catch (e) {
    error.value = e.response?.data?.error || '删除失败'
  }
}

function formatDate(value) {
  return value.replace('T', ' ').slice(0, 16)
}

onMounted(loadAnnouncements)
</script>

<style scoped>
textarea {
  resize: vertical;
  width: 100%;
}

.announcement-list {
  display: flex;
  flex-direction: column;
}

.announcement-item {
  display: flex;
  align-items: flex-start;
  gap: 16px;
  padding: 14px 0;
  border-bottom: 1px solid #f0f0f0;
}

.announcement-item:first-child {
  padding-top: 0;
}

.announcement-content {
  min-width: 0;
  flex: 1;
}

.announcement-item h4 {
  margin: 0;
  font-size: 15px;
  font-weight: 600;
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
  white-space: pre-wrap;
  overflow-wrap: anywhere;
}

@media (max-width: 480px) {
  .announcement-item {
    flex-direction: column;
    gap: 8px;
  }
}
</style>
