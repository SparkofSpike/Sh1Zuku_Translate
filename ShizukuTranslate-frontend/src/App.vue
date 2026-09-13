<template>
  <div>
    <header style="border-bottom: 1px solid #eee; padding: 0 24px;">
      <div style="max-width: 800px; margin: 0 auto; display: flex; align-items: center; height: 56px;">
        <router-link to="/" class="nav-item">{{ t('nav.translate') }}</router-link>
        <router-link to="/history" v-if="authStore.token" class="nav-item" style="margin-left: 24px;">{{ t('nav.history') }}</router-link>
        <router-link to="/admin" v-if="authStore.isAdmin" class="nav-item" style="margin-left: 24px;">{{ t('nav.admin') }}</router-link>
        <router-link to="/logs" v-if="authStore.isAdmin" class="nav-item" style="margin-left: 24px;">{{ t('nav.logs') }}</router-link>
        <router-link to="/about" class="nav-item" style="margin-left: 24px;">{{ t('nav.about') }}</router-link>
        <div style="flex:1;"></div>
        <select v-model="locale" class="locale-select" :title="t('nav.language')">
          <option v-for="item in SUPPORTED_LOCALES" :key="item.code" :value="item.code">{{ item.label }}</option>
        </select>
        <template v-if="!authStore.token">
          <router-link to="/login" class="nav-item" style="margin-left: 16px;">{{ t('nav.login') }}</router-link>
          <router-link to="/register" class="nav-item" style="margin-left: 16px;">{{ t('nav.register') }}</router-link>
        </template>
        <template v-else>
          <router-link to="/profile" class="nav-item" style="margin-left: 16px;">{{ t('nav.profile') }}</router-link>
          <button @click="logout" style="margin-left: 16px; padding: 6px 16px;">{{ t('nav.logout') }}</button>
        </template>
      </div>
    </header>
    <main class="app-main">
      <router-view />
    </main>
    <AnnouncementConfirmDialog
      v-if="pendingAnnouncements.length"
      :announcements="pendingAnnouncements"
      @confirmed="onAnnouncementConfirmed"
    />
  </div>
</template>

<script setup>
import { onMounted, ref, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import { SUPPORTED_LOCALES, setLocale } from './i18n'
import { useAuthStore } from './stores/auth'
import { useRouter } from 'vue-router'
import api from './api'
import AnnouncementConfirmDialog from './components/AnnouncementConfirmDialog.vue'

const { t, locale } = useI18n()

// Selecting a language updates the i18n instance, <html lang> and localStorage together.
watch(locale, (value) => setLocale(value))

const authStore = useAuthStore()
const router = useRouter()
const pendingAnnouncements = ref([])

// 登录用户每次进入站点时拉取需要确认的公告；确认后（或登出）即清除，不再自动弹出。
watch(
  () => authStore.token,
  async (token) => {
    if (!token) {
      pendingAnnouncements.value = []
      return
    }
    try {
      const res = await api.get('/announcements/pending')
      pendingAnnouncements.value = res.data || []
    } catch (e) {
      // Token 失效/过期或网络错误：本次会话不弹确认框，交由 /auth/me 流程处理登出。
      pendingAnnouncements.value = []
    }
  },
  { immediate: true }
)

function onAnnouncementConfirmed(id) {
  pendingAnnouncements.value = pendingAnnouncements.value.filter((a) => a.id !== id)
}

onMounted(async () => {
  if (authStore.token) {
    try {
      const res = await api.get('/auth/me')
      authStore.setAdmin(res.data.isAdmin)
      authStore.setEmailVerified(!!res.data.emailVerified)
    } catch (e) {
      // Token expired or revoked: clear it and leave protected pages.
      authStore.logout()
      if (router.currentRoute.value.meta.requiresAuth) {
        router.replace('/login')
      }
    }
  }
})

function logout() {
  authStore.logout()
  router.push('/login')
}
</script>

<style scoped>
.nav-item {
  font-size: 16px;
  color: #555;
  padding: 4px 0;
  border-bottom: 2px solid transparent;
  transition: color 0.2s, border-color 0.2s;
}
.nav-item:hover { color: #000; border-bottom-color: #000; text-decoration: none; }
.router-link-exact-active { color: #000 !important; border-bottom-color: #000 !important; }

.locale-select {
  width: auto;
  padding: 4px 8px;
  font-size: 14px;
  border: 1px solid #ddd;
  border-radius: 4px;
  background: #fff;
  cursor: pointer;
}

.app-main {
  max-width: 1240px;
  margin: 0 auto;
  padding: 24px 20px;
}
</style>
