<template>
  <div class="card">
    <div v-if="detail">
      <h2>翻译详情</h2>
      <div><strong>原文：</strong><pre>{{ detail.sourceText }}</pre></div>
      <div><strong>译文：</strong><pre>{{ detail.translatedText }}</pre></div>
      <div><strong>模型：</strong>{{ detail.model }}</div>
      <div><strong>自定义 Prompt：</strong>{{ detail.customPrompt || '默认' }}</div>
      <div><strong>时间：</strong>{{ detail.createdAt }}</div>
      <router-link to="/history" style="display:inline-block; margin-top:16px;">← 返回列表</router-link>
    </div>
    <p v-else>加载中...</p>
  </div>
</template>

<script setup>
import { ref, onMounted } from 'vue'
import { useRoute } from 'vue-router'
import api from '../api'

const route = useRoute()
const detail = ref(null)

onMounted(async () => {
  try {
    const res = await api.get(`/translations/${route.params.id}`)
    detail.value = res.data
  } catch (e) {
    console.error(e)
  }
})
</script>
