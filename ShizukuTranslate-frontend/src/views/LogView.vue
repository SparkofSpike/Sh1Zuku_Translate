<template>
  <div class="log-page">
    <section class="card log-card">
      <div class="page-heading">
        <div>
          <h2>插件日志</h2>
          <p class="muted">浏览器插件提交的错误报告</p>
        </div>
        <button class="btn-sm btn-remove" @click="fetchLogs">刷新</button>
      </div>

      <div v-if="loading" class="muted">加载中...</div>
      <template v-else>
        <div v-if="logs.length" class="table-wrap">
          <table>
            <thead>
              <tr>
                <th>提交者</th>
                <th>版本</th>
                <th>提交时间</th>
                <th class="message-heading">错误信息</th>
              </tr>
            </thead>
            <tbody>
              <tr v-for="log in logs" :key="log.id">
                <td>{{ log.username }}</td>
                <td>{{ log.version || '-' }}</td>
                <td>{{ formatDate(log.createdAt) }}</td>
                <td class="error-cell">{{ log.errorMessage }}</td>
              </tr>
            </tbody>
          </table>
        </div>
        <p v-else class="muted">暂无日志</p>

        <div v-if="logs.length" class="log-pagination">
          <button class="btn-sm btn-remove" :disabled="page === 0" @click="prevPage">上一页</button>
          <span>第 {{ page + 1 }} 页</span>
          <button class="btn-sm btn-remove" :disabled="!hasMore" @click="nextPage">下一页</button>
        </div>
      </template>

      <p v-if="error" class="error">{{ error }}</p>
    </section>
  </div>
</template>

<script setup>
import { ref, onMounted } from 'vue'
import api from '../api'

const logs = ref([])
const page = ref(0)
const hasMore = ref(false)
const loading = ref(true)
const error = ref('')

async function fetchLogs() {
  loading.value = true
  error.value = ''
  try {
    const res = await api.get('/plugin/logs', {
      params: { page: page.value, size: 20, sort: 'createdAt,desc' }
    })
    logs.value = res.data.content || []
    hasMore.value = !res.data.last
  } catch (e) {
    error.value = e.response?.data?.error || '加载日志失败'
  } finally {
    loading.value = false
  }
}

function nextPage() {
  page.value++
  fetchLogs()
}

function prevPage() {
  page.value--
  fetchLogs()
}

function formatDate(value) {
  return value ? value.replace('T', ' ').slice(0, 19) : ''
}

onMounted(fetchLogs)
</script>

<style scoped>
.log-page { max-width: 1100px; margin: 0 auto; }
.log-card { max-width: none; margin: 0; }
.page-heading { display: flex; justify-content: space-between; align-items: flex-start; gap: 16px; }
h2 { margin: 0; font-weight: 600; }
.page-heading p { margin: 3px 0 0; }
.muted { color: #777; font-size: 13px; }
.table-wrap { overflow-x: auto; }
table { width: 100%; border-collapse: collapse; font-size: 13px; }
th, td { padding: 10px 8px; border-bottom: 1px solid #eee; text-align: left; vertical-align: top; }
th { color: #777; font-size: 12px; font-weight: 500; white-space: nowrap; }
.message-heading { width: 40%; }
.error-cell { color: #c5221f; white-space: pre-wrap; overflow-wrap: anywhere; font-family: monospace; font-size: 12px; }
.log-pagination { display: flex; align-items: center; gap: 12px; margin-top: 12px; }
.error { color: #e03131; }
</style>
