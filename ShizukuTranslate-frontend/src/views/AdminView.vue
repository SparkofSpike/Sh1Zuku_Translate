<template>
  <div class="admin-page">
    <section class="card admin-card">
      <div class="page-heading">
        <div>
          <h2>管理员面板</h2>
          <p class="muted">模型调用用量概览</p>
        </div>
        <button class="btn-sm btn-refresh" @click="loadUsage">刷新数据</button>
      </div>

      <div v-if="usageLoading" class="muted">加载用量中...</div>
      <template v-else>
        <div class="metric-grid">
          <div class="metric"><span>总 Token</span><strong>{{ formatNumber(usage.totalTokens) }}</strong></div>
          <div class="metric"><span>输入 Token</span><strong>{{ formatNumber(usage.promptTokens) }}</strong></div>
          <div class="metric"><span>输出 Token</span><strong>{{ formatNumber(usage.completionTokens) }}</strong></div>
          <div class="metric"><span>调用次数</span><strong>{{ formatNumber(usage.requestCount) }}</strong></div>
        </div>

        <div class="charts-grid">
          <div class="chart-panel">
            <h3>近 14 日用量</h3>
            <div class="bar-chart">
              <div v-for="day in usage.daily" :key="day.date" class="bar-column" :title="`${day.date}: ${formatNumber(day.totalTokens)} Token`">
                <div class="bar-track"><div class="bar-fill" :style="{ height: barHeight(day.totalTokens) + '%' }"></div></div>
                <span>{{ shortDate(day.date) }}</span>
              </div>
            </div>
          </div>
          <div class="chart-panel">
            <h3>模型用量</h3>
            <div v-if="usage.byModel?.length" class="model-chart">
              <div v-for="item in usage.byModel" :key="item.provider + item.model" class="model-row">
                <div class="model-name"><span>{{ item.model }}</span><small>{{ providerLabel(item.provider) }}</small></div>
                <div class="model-bar-track"><div class="model-bar-fill" :style="{ width: modelWidth(item.totalTokens) + '%' }"></div></div>
                <strong>{{ formatNumber(item.totalTokens) }}</strong>
              </div>
            </div>
            <p v-else class="muted">暂无模型调用记录</p>
          </div>
        </div>

        <h3 class="subheading">账户用量</h3>
        <div class="table-wrap">
          <table>
            <thead><tr>
              <th class="sortable" :class="{ 'sorted': sortKey === 'username' }" @click="cycleSort('username')">账户{{ sortIndicator('username') }}</th>
              <th class="sortable" :class="{ 'sorted': sortKey === 'totalTokens' }" @click="cycleSort('totalTokens')">总 Token{{ sortIndicator('totalTokens') }}</th>
              <th class="sortable" :class="{ 'sorted': sortKey === 'requestCount' }" @click="cycleSort('requestCount')">调用次数{{ sortIndicator('requestCount') }}</th>
              <th class="sortable" :class="{ 'sorted': sortKey === 'latestUsedAt' }" @click="cycleSort('latestUsedAt')">最新使用{{ sortIndicator('latestUsedAt') }}</th>
              <th></th>
            </tr></thead>
            <tbody>
              <tr v-for="user in sortedUsers" :key="user.id">
                <td><strong>{{ user.username }}</strong><small>{{ user.email }}</small></td>
                <td>{{ formatNumber(user.totalTokens) }}</td>
                <td>{{ user.requestCount }}</td>
                <td>{{ formatDate(user.latestUsedAt) }}</td>
                <td><button class="btn-sm btn-detail" @click="showDetails(user)">查看详情</button></td>
              </tr>
            </tbody>
          </table>
        </div>
      </template>

      <p v-if="error" class="error">{{ error }}</p>
    </section>

    <section class="card admin-card announcement-section">
      <h3 class="section-title">发布公告</h3>
      <input v-model.trim="title" type="text" maxlength="100" placeholder="公告标题" />
      <div class="markdown-editor">
        <div class="markdown-tabs" role="tablist" aria-label="公告内容编辑模式">
          <button
            type="button"
            class="markdown-tab"
            :class="{ active: editorMode === 'write' }"
            :aria-selected="editorMode === 'write'"
            role="tab"
            @click="editorMode = 'write'"
          >编辑</button>
          <button
            type="button"
            class="markdown-tab"
            :class="{ active: editorMode === 'preview' }"
            :aria-selected="editorMode === 'preview'"
            role="tab"
            @click="editorMode = 'preview'"
          >预览</button>
        </div>
        <textarea v-if="editorMode === 'write'" v-model="content" rows="5" placeholder="公告内容"></textarea>
        <div v-else-if="content.trim()" class="announcement-markdown markdown-preview" v-html="renderMarkdown(content)"></div>
        <p v-else class="markdown-preview-empty">暂无内容可预览</p>
      </div>
      <label class="ack-checkbox">
        <input type="checkbox" v-model="requireConfirmation" />
        <span>需要用户确认：用户访问网站时将弹出此公告，点击确认后不再自动弹出</span>
      </label>
      <button @click="publish" :disabled="publishing">{{ publishing ? '发布中...' : '发布公告' }}</button>
      <p v-if="success" class="success">{{ success }}</p>

      <hr />
      <h3 class="section-title">已发布公告</h3>
      <div v-if="loading" class="muted">加载中...</div>
      <div v-else-if="announcements.length" class="announcement-list">
        <article v-for="announcement in announcements" :key="announcement.id" class="announcement-item">
          <div class="announcement-content">
            <h4>{{ announcement.title }}<span v-if="announcement.requireConfirmation" class="badge">需确认</span></h4>
            <time>{{ formatDate(announcement.createdAt) }}</time>
            <div class="announcement-markdown" v-html="renderMarkdown(announcement.content)"></div>
          </div>
          <div class="announcement-actions">
            <button v-if="announcement.requireConfirmation" class="btn-sm btn-detail" @click="showAcknowledgements(announcement)">确认情况</button>
            <button class="btn-sm btn-remove" @click="removeAnnouncement(announcement.id)">删除</button>
          </div>
        </article>
      </div>
      <p v-else class="muted">暂无公告</p>
    </section>

    <div v-if="detailUser" class="modal-backdrop" @click.self="detailUser = null">
      <section class="modal card">
        <div class="page-heading">
          <div><h3>{{ detailUser.user.username }} 的 Token 日志</h3><p class="muted">{{ detailUser.user.email }}</p></div>
          <button class="btn-sm btn-remove" @click="detailUser = null">关闭</button>
        </div>
        <div class="detail-summary">
          <span>总计 <strong>{{ formatNumber(detailUser.summary.totalTokens) }}</strong></span>
          <span>输入 {{ formatNumber(detailUser.summary.promptTokens) }}</span>
          <span>输出 {{ formatNumber(detailUser.summary.completionTokens) }}</span>
        </div>
        <div v-if="detailLoading" class="muted">加载日志中...</div>
        <div v-else-if="detailUser.logs.length" class="table-wrap log-table">
          <table>
            <thead><tr><th>时间</th><th>协议</th><th>模型</th><th>来源</th><th>输入</th><th>输出</th><th>合计</th></tr></thead>
            <tbody><tr v-for="log in detailUser.logs" :key="log.id">
              <td>{{ formatDate(log.createdAt) }}</td><td>{{ providerLabel(log.provider) }}</td><td>{{ log.model }}</td>
              <td>{{ log.estimated ? '估算' : (log.sourceType === 'CACHE_BACKFILL' ? '缓存实际' : '实际') }}</td>
              <td>{{ formatNumber(log.promptTokens) }}</td><td>{{ formatNumber(log.completionTokens) }}</td><td><strong>{{ formatNumber(log.totalTokens) }}</strong></td>
            </tr></tbody>
          </table>
        </div>
        <p v-else class="muted">暂无 token 使用日志</p>
      </section>
    </div>

    <div v-if="ackModal" class="modal-backdrop" @click.self="ackModal = null">
      <section class="modal card">
        <div class="page-heading">
          <div>
            <h3>「{{ ackModal.title }}」已确认用户</h3>
            <p class="muted">共 {{ ackModal.total }} 人已确认</p>
          </div>
          <button class="btn-sm btn-remove" @click="ackModal = null">关闭</button>
        </div>
        <div v-if="ackLoading" class="muted">加载中...</div>
        <template v-else>
          <div v-if="ackModal.users.length" class="table-wrap log-table">
            <table>
              <thead><tr><th>用户名</th><th>邮箱</th><th>确认时间</th></tr></thead>
              <tbody><tr v-for="ack in ackModal.users" :key="ack.username + ack.acknowledgedAt">
                <td><strong>{{ ack.username }}</strong></td>
                <td>{{ ack.email }}</td>
                <td>{{ formatDate(ack.acknowledgedAt) }}</td>
              </tr></tbody>
            </table>
          </div>
          <p v-else class="muted">暂无用户确认此公告</p>
        </template>
      </section>
    </div>
  </div>
</template>

<script setup>
import { computed, onMounted, ref } from 'vue'
import api from '../api'
import { renderMarkdown } from '../utils/markdown'

const usage = ref({ totalTokens: 0, promptTokens: 0, completionTokens: 0, requestCount: 0, daily: [], byModel: [], users: [] })

// 账户用量表格排序：不按是默认，第一次点击升序，第二次降序，第三次还原，以此类推
const sortKey = ref(null)        // 'username' | 'totalTokens' | 'requestCount' | 'latestUsedAt' | null
const sortDirection = ref(null)  // 'asc' | 'desc' | null（null 表示还原为默认顺序）

function cycleSort(key) {
  if (sortKey.value !== key) {
    sortKey.value = key
    sortDirection.value = 'asc'
  } else if (sortDirection.value === 'asc') {
    sortDirection.value = 'desc'
  } else {
    sortKey.value = null
    sortDirection.value = null
  }
}

function sortIndicator(key) {
  if (sortKey.value !== key) return ''
  return sortDirection.value === 'asc' ? ' ↑' : ' ↓'
}

const sortedUsers = computed(() => {
  const users = usage.value.users || []
  if (!sortKey.value || !sortDirection.value) return users
  const dir = sortDirection.value === 'asc' ? 1 : -1
  return [...users].sort((a, b) => {
    const av = a[sortKey.value]
    const bv = b[sortKey.value]
    // 空值（如暂无使用时间）始终排在最后
    if (av == null && bv == null) return 0
    if (av == null) return 1
    if (bv == null) return -1
    if (typeof av === 'number' && typeof bv === 'number') return (av - bv) * dir
    return String(av).localeCompare(String(bv), undefined, { numeric: true }) * dir
  })
})
const usageLoading = ref(true)
const detailUser = ref(null)
const detailLoading = ref(false)
const error = ref('')
const title = ref('')
const content = ref('')
const requireConfirmation = ref(false)
const editorMode = ref('write')
const announcements = ref([])
const loading = ref(true)
const publishing = ref(false)
const success = ref('')
const ackModal = ref(null)  // { title, total, users } | null
const ackLoading = ref(false)

async function loadUsage() {
  usageLoading.value = true
  try {
    const res = await api.get('/admin/usage')
    usage.value = res.data
  } catch (e) {
    error.value = e.response?.data?.error || '加载 token 用量失败'
  } finally {
    usageLoading.value = false
  }
}

async function showDetails(user) {
  detailLoading.value = true
  detailUser.value = { user, summary: user, logs: [] }
  try {
    const res = await api.get('/admin/usage/users/' + user.id)
    detailUser.value = res.data
  } catch (e) {
    error.value = e.response?.data?.error || '加载 token 日志失败'
    detailUser.value = null
  } finally {
    detailLoading.value = false
  }
}

async function loadAnnouncements() {
  try {
    const res = await api.get('/announcements')
    announcements.value = res.data || []
  } catch (e) {
    error.value = '加载公告失败：' + (e.response?.data?.error || '')
  } finally { loading.value = false }
}

async function publish() {
  if (!title.value || !content.value.trim()) { error.value = '请填写公告标题和内容'; return }
  publishing.value = true
  error.value = ''
  try {
    await api.post('/admin/announcements', {
      title: title.value,
      content: content.value,
      requireConfirmation: requireConfirmation.value
    })
    title.value = ''; content.value = ''; requireConfirmation.value = false; editorMode.value = 'write'; success.value = '公告发布成功'
    await loadAnnouncements()
  } catch (e) { error.value = e.response?.data?.error || '发布失败' }
  finally { publishing.value = false }
}

async function showAcknowledgements(announcement) {
  ackLoading.value = true
  ackModal.value = { title: announcement.title, total: 0, users: [] }
  try {
    const res = await api.get('/admin/announcements/' + announcement.id + '/acknowledgements')
    ackModal.value = {
      title: announcement.title,
      total: res.data.total || 0,
      users: res.data.users || []
    }
  } catch (e) {
    error.value = e.response?.data?.error || '加载确认列表失败'
    ackModal.value = null
  } finally {
    ackLoading.value = false
  }
}

async function removeAnnouncement(id) {
  if (!window.confirm('确定要删除这条公告吗？')) return
  try { await api.delete('/admin/announcements/' + id); await loadAnnouncements() }
  catch (e) { error.value = e.response?.data?.error || '删除失败' }
}

function formatNumber(value) { return Number(value || 0).toLocaleString() }
function formatDate(value) { return value ? value.replace('T', ' ').slice(0, 16) : '暂无' }
function shortDate(value) { return value ? value.slice(5).replace('-', '/') : '' }
function providerLabel(value) { return value === 'anthropic' ? 'Anthropic' : value === 'openai' ? 'OpenAI 兼容' : 'DeepSeek' }
function barHeight(value) {
  const max = Math.max(...(usage.value.daily || []).map(day => day.totalTokens), 1)
  return value ? Math.max(8, Math.round(value / max * 100)) : 2
}
function modelWidth(value) {
  const max = Math.max(...(usage.value.byModel || []).map(item => item.totalTokens), 1)
  return value ? Math.max(4, Math.round(value / max * 100)) : 0
}

onMounted(() => { loadUsage(); loadAnnouncements() })
</script>

<style scoped>
.admin-page { max-width: 1100px; margin: 0 auto; }
.admin-card { max-width: none; margin: 0 0 16px; }
.page-heading { display: flex; justify-content: space-between; align-items: flex-start; gap: 16px; }
h2 { margin: 0; font-weight: 600; }
h3 { font-size: 16px; font-weight: 600; }
.muted { color: #777; font-size: 13px; }
.page-heading p { margin: 3px 0 0; }
.metric-grid { display: grid; grid-template-columns: repeat(4, 1fr); gap: 10px; margin: 20px 0; }
.metric { padding: 15px; border: 1px solid #e5e5e5; background: #fafafa; border-radius: 7px; }
.metric span { display: block; color: #777; font-size: 12px; }
.metric strong { display: block; margin-top: 3px; font-size: 22px; }
.charts-grid { display: grid; grid-template-columns: 1.2fr .8fr; gap: 14px; }
.chart-panel { min-width: 0; border: 1px solid #e5e5e5; border-radius: 7px; padding: 15px; }
.chart-panel h3 { margin: 0 0 15px; }
.bar-chart { display: flex; align-items: end; height: 145px; gap: 5px; }
.bar-column { display: flex; flex: 1; height: 100%; min-width: 0; flex-direction: column; align-items: center; justify-content: end; gap: 5px; }
.bar-track { display: flex; align-items: end; width: 100%; height: 115px; background: #f1f1f1; border-radius: 3px 3px 0 0; }
.bar-fill { width: 100%; min-height: 2px; background: #292929; border-radius: 3px 3px 0 0; transition: height .25s; }
.bar-column span { color: #888; font-size: 10px; white-space: nowrap; transform: rotate(-35deg); }
.model-chart { display: flex; flex-direction: column; gap: 14px; }
.model-row { display: grid; grid-template-columns: minmax(90px, 1fr) minmax(80px, 1.4fr) auto; gap: 8px; align-items: center; font-size: 12px; }
.model-name { min-width: 0; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }.model-name small { display: block; color: #999; }
.model-bar-track { height: 8px; background: #eee; border-radius: 5px; overflow: hidden; }.model-bar-fill { height: 100%; background: #555; border-radius: 5px; }
.subheading { margin: 24px 0 10px; }
.table-wrap { overflow-x: auto; } table { width: 100%; border-collapse: collapse; font-size: 13px; } th, td { padding: 10px 8px; border-bottom: 1px solid #eee; text-align: left; white-space: nowrap; } th { color: #777; font-size: 12px; font-weight: 500; } td small { display: block; color: #999; font-size: 11px; }
th.sortable { cursor: pointer; user-select: none; transition: color .15s; }th.sortable:hover { color: #222; }
th.sorted { color: #222; font-weight: 600; }
.btn-refresh, .btn-detail { background: #fff; color: #333; border-color: #bbb; }.btn-refresh:hover, .btn-detail:hover { background: #eee; }
.markdown-editor { margin: 12px 0 12px; border: 1px solid #ddd; border-radius: 6px; overflow: hidden; }.markdown-tabs { display: flex; gap: 2px; padding: 0 8px; border-bottom: 1px solid #eee; background: #fafafa; }.markdown-tab { padding: 8px 12px; border: 0; border-bottom: 2px solid transparent; border-radius: 0; background: transparent; color: #666; font-size: 13px; }.markdown-tab:hover { background: #f0f0f0; color: #222; }.markdown-tab.active { border-bottom-color: #222; color: #222; font-weight: 600; }.markdown-editor textarea { display: block; box-sizing: border-box; width: 100%; min-height: 140px; margin: 0; border: 0; border-radius: 0; resize: vertical; }.announcement-markdown.markdown-preview { min-height: 140px; margin: 0; padding: 10px 12px; }.markdown-preview-empty { min-height: 140px; margin: 0; padding: 10px 12px; color: #999; font-size: 13px; }.section-title { margin: 0 0 12px; }.announcement-list { display: flex; flex-direction: column; }.announcement-item { display: flex; align-items: flex-start; gap: 16px; padding: 14px 0; border-bottom: 1px solid #f0f0f0; }.announcement-content { min-width: 0; flex: 1; }.announcement-item h4 { margin: 0; font-size: 15px; }.announcement-item time { color: #999; font-size: 12px; }.announcement-markdown { margin: 7px 0 0; color: #555; overflow-wrap: anywhere; }.announcement-markdown :deep(p), .announcement-markdown :deep(ul), .announcement-markdown :deep(ol), .announcement-markdown :deep(blockquote), .announcement-markdown :deep(pre) { margin: 0 0 7px; }.announcement-markdown :deep(p:last-child), .announcement-markdown :deep(ul:last-child), .announcement-markdown :deep(ol:last-child), .announcement-markdown :deep(blockquote:last-child), .announcement-markdown :deep(pre:last-child) { margin-bottom: 0; }.announcement-markdown :deep(ul), .announcement-markdown :deep(ol) { padding-left: 20px; }.announcement-markdown :deep(blockquote) { padding-left: 10px; border-left: 3px solid #ddd; color: #777; }.announcement-markdown :deep(code) { padding: 1px 4px; border-radius: 3px; background: #f1f1f1; font-size: 12px; }.announcement-markdown :deep(pre) { padding: 8px 10px; overflow-x: auto; border-radius: 4px; background: #f5f5f5; }.announcement-markdown :deep(pre code) { padding: 0; background: transparent; }.announcement-markdown :deep(a) { color: #444; text-decoration: underline; }
.ack-checkbox { display: flex; align-items: center; gap: 8px; margin: 0 0 12px; color: #555; font-size: 13px; cursor: pointer; }.ack-checkbox input { width: auto; margin: 0; }.badge { display: inline-block; margin-left: 8px; padding: 1px 7px; border-radius: 10px; background: #fff3bf; color: #9a6700; font-size: 11px; font-weight: 600; vertical-align: middle; }.announcement-actions { display: flex; flex-direction: column; gap: 8px; flex-shrink: 0; }
.success { color: #2b8a3e; }.error { color: #e03131; }.detail-summary { display: flex; gap: 18px; margin: 14px 0; color: #777; font-size: 13px; }.detail-summary strong { color: #222; font-size: 20px; }.log-table { max-height: 390px; overflow-y: auto; }
.modal-backdrop { position: fixed; z-index: 10; inset: 0; display: flex; align-items: center; justify-content: center; padding: 20px; background: rgba(0,0,0,.42); }.modal { width: min(900px, 100%); max-height: calc(100vh - 40px); overflow: auto; margin: 0; box-shadow: 0 16px 50px rgba(0,0,0,.25); }
@media (max-width: 720px) { .metric-grid { grid-template-columns: repeat(2, 1fr); }.charts-grid { grid-template-columns: 1fr; }.model-row { grid-template-columns: 1fr 1.2fr auto; }.announcement-item { flex-direction: column; gap: 8px; }.announcement-actions { flex-direction: row; justify-content: flex-end; } }
</style>
