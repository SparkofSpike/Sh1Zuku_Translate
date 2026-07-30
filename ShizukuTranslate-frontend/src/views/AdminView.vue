<template>
  <div class="card">
    <h2 style="margin-top:0; font-weight:600;">管理员面板</h2>

    <div v-if="loading">加载中...</div>
    <div v-else-if="error" style="color:#e03131;">{{ error }}</div>
    <div v-else>
      <div style="display:flex; gap:24px; flex-wrap:wrap; margin-bottom:20px;">
        <div style="background:#f9f9f9; border-radius:8px; padding:16px; min-width:140px;">
          <div style="font-size:12px; color:#777;">问卷总数</div>
          <div style="font-size:28px; font-weight:600;">{{ stats.total }}</div>
        </div>
        <div style="background:#f9f9f9; border-radius:8px; padding:16px; min-width:140px;">
          <div style="font-size:12px; color:#777;">翻译质量均分</div>
          <div style="font-size:28px; font-weight:600;">{{ stats.avgTranslationQuality }}</div>
        </div>
        <div style="background:#f9f9f9; border-radius:8px; padding:16px; min-width:140px;">
          <div style="font-size:12px; color:#777;">体验均分</div>
          <div style="font-size:28px; font-weight:600;">{{ stats.avgExperienceQuality }}</div>
        </div>
      </div>

      <h3>详细记录</h3>
      <div v-if="stats.records && stats.records.length">
        <div v-for="r in stats.records" :key="r.id" style="border-bottom:1px solid #f0f0f0; padding:12px 0;">
          <div style="font-size:13px; color:#777;">{{ r.createdAt }} | 用户：{{ r.username }}</div>
          <div>翻译质量：{{ r.translationQuality }} / 5 | 体验：{{ r.experienceQuality }} / 5 | 常用功能：{{ r.favoriteFeature }}</div>
          <div v-if="r.suggestion" style="color:#555; margin-top:4px;">建议：{{ r.suggestion }}</div>
        </div>
      </div>
      <p v-else style="color:#999;">暂无问卷记录</p>
    </div>
  </div>
</template>

<script setup>
import { ref, onMounted } from 'vue'
import api from '../api'

const stats = ref({ total: 0, avgTranslationQuality: 0, avgExperienceQuality: 0, records: [] })
const loading = ref(true)
const error = ref('')

onMounted(async () => {
  try {
    const res = await api.get('/admin/surveys')
    stats.value = res.data
  } catch (e) {
    error.value = '加载失败：' + (e.response?.data?.error || '无权限')
  } finally {
    loading.value = false
  }
})
</script>
