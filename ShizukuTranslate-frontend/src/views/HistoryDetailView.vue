<template>
  <div class="card">
    <div v-if="detail">
      <h2>{{ t('history.detail.title') }}</h2>
      <div><strong>{{ t('history.detail.source') }}</strong><pre>{{ detail.sourceText }}</pre></div>
      <div><strong>{{ t('history.detail.translated') }}</strong><pre>{{ detail.translatedText }}</pre></div>
      <div><strong>{{ t('history.detail.model') }}</strong>{{ detail.model }}</div>
      <div><strong>{{ t('history.detail.customPrompt') }}</strong>{{ detail.customPrompt || t('history.detail.default') }}</div>
      <div><strong>{{ t('history.detail.time') }}</strong>{{ detail.createdAt }}</div>
      <router-link to="/history" style="display:inline-block; margin-top:16px;">{{ t('history.detail.back') }}</router-link>
    </div>
    <p v-else>{{ t('history.detail.loading') }}</p>
  </div>
</template>

<script setup>
import { ref, onMounted } from 'vue'
import { useI18n } from 'vue-i18n'
import { useRoute } from 'vue-router'
import api from '../api'

const { t } = useI18n()

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
