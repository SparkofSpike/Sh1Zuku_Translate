<template>
  <div style="max-width: 800px; margin: 20px auto;">
    <h2>{{ t('history.title') }}</h2>
    <div v-if="records.length">
      <div v-for="item in records" :key="item.id" style="border-bottom:1px solid #eee; padding:10px;">
        <div><strong>{{ t('history.time') }}</strong>{{ item.createdAt }}</div>
        <div><strong>{{ t('history.model') }}</strong>{{ item.model }}</div>
        <router-link :to="'/history/' + item.id">{{ t('history.viewDetail') }}</router-link>
      </div>
      <div style="margin-top:10px;">
        <button :disabled="page === 0" @click="prevPage">{{ t('history.prev') }}</button>
        <span>{{ t('history.page', { page: page + 1 }) }}</span>
        <button :disabled="!hasMore" @click="nextPage">{{ t('history.next') }}</button>
      </div>
    </div>
    <p v-else>{{ t('history.empty') }}</p>
  </div>
</template>

<script setup>
import { ref, onMounted } from 'vue'
import { useI18n } from 'vue-i18n'
import api from '../api'

const { t } = useI18n()

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
