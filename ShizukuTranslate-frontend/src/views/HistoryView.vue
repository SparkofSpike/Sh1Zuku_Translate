<template>
  <div style="max-width: 800px; margin: 20px auto;">
    <h2>翻译历史</h2>
    <div v-if="records.length">
      <div v-for="item in records" :key="item.id" style="border-bottom:1px solid #eee; padding:10px;">
        <div><strong>时间：</strong>{{ item.createdAt }}</div>
        <div><strong>模型：</strong>{{ item.model }}</div>
        <router-link :to="'/history/' + item.id">查看详情</router-link>
      </div>
      <div style="margin-top:10px;">
        <button :disabled="page === 0" @click="prevPage">上一页</button>
        <span>第 {{ page + 1 }} 页</span>
        <button :disabled="!hasMore" @click="nextPage">下一页</button>
      </div>
    </div>
    <p v-else>暂无记录</p>
  </div>
</template>

<script setup>
import { ref, onMounted } from 'vue'
import api from '../api'

const records = ref([])
const page = ref(0)
const hasMore = ref(false)

async function fetchHistory() {
  try {
    const res = await api.get('/translations', { params: { page: page.value, size: 10, sort: 'createdAt,desc' } })
    records.value = res.data.content || []
    hasMore.value = !res.data.last
  } catch (e) {
    console.error(e)
  }
}

function nextPage() {
  page.value++
  fetchHistory()
}

function prevPage() {
  page.value--
  fetchHistory()
}

onMounted(fetchHistory)
</script>
