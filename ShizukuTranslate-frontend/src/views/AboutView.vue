<template>
  <div class="card">
    <h2 style="margin-top:0; font-weight:600;">{{ t('about.title') }}</h2>
    <p>{{ t('about.intro') }}</p>
    <h3>{{ t('about.basic.title') }}</h3>
    <ol>
      <li>{{ t('about.basic.buildDate', { date: buildDate }) }}</li>
      <li>{{ t('about.basic.version', { version: commit }) }}</li>
    </ol>
    <h3>{{ t('about.usage.title') }}</h3>
    <ol>
      <li>{{ t('about.usage.item1') }}</li>
      <li>{{ t('about.usage.item2') }}</li>
      <li>{{ t('about.usage.item3') }}</li>
      <li>{{ t('about.usage.item4') }}</li>
      <li>{{ t('about.usage.item5') }}</li>
    </ol>
    <h3>{{ t('about.terms.title') }}</h3>
    <ol>
      <li>{{ t('about.terms.item1') }}</li>
      <li>{{ t('about.terms.item2') }}</li>
      <li>{{ t('about.terms.item3') }}</li>
      <li>{{ t('about.terms.item4') }}</li>
      <li>{{ t('about.terms.item5') }}</li>
      <li>{{ t('about.terms.item6') }}</li>
      <li>{{ t('about.terms.item7') }}</li>
      <li>{{ t('about.terms.item8') }}</li>
      <li>{{ t('about.terms.item9') }}</li>
      <li>{{ t('about.terms.item10') }}</li>
      <li>{{ t('about.terms.item11') }}</li>
    </ol>
    <h3>{{ t('about.notes.title') }}</h3>
    <ol>
      <li>{{ t('about.notes.item1') }}</li>
      <li>{{ t('about.notes.item2') }}</li>
      <li>{{ t('about.notes.item3') }}</li>
      <li>{{ t('about.notes.item4') }}</li>
      <li>{{ t('about.notes.item5') }}</li>
      <li>{{ t('about.notes.item6') }}</li>
      <li>{{ t('about.notes.item7') }}</li>
    </ol>
    <p style="margin-top:20px;">Sh1Zuku 2026.6.13</p>
  </div>
</template>

<script setup>
import { computed } from 'vue'
import { useI18n } from 'vue-i18n'

const { t } = useI18n()

// Injected at build time by vite.config.js (define). Computed rather than resolved once at
// setup, so the "unknown" fallback follows a language switch.
const buildDate = computed(() => {
  const ts = Number(import.meta.env.VITE_BUILD_TIME)
  if (!ts) return t('common.unknown')
  const d = new Date(ts)
  return `${d.getFullYear()}.${d.getMonth() + 1}.${d.getDate()}`
})

const commit = computed(() => import.meta.env.VITE_COMMIT || t('common.unknown'))
</script>
