<template>
  <div>
    <header style="border-bottom: 1px solid #eee; padding: 0 24px;">
      <div style="max-width: 800px; margin: 0 auto; display: flex; align-items: center; height: 56px;">
        <router-link to="/" class="nav-item">翻译</router-link>
        <router-link to="/history" v-if="authStore.token" class="nav-item" style="margin-left: 24px;">历史</router-link>
        <router-link to="/survey" v-if="authStore.token" class="nav-item" style="margin-left: 24px;">问卷</router-link>
        <router-link to="/admin" v-if="authStore.isAdmin" class="nav-item" style="margin-left: 24px;">管理</router-link>
        <router-link to="/about" class="nav-item" style="margin-left: 24px;">关于</router-link>
        <div style="flex:1;"></div>
        <template v-if="!authStore.token">
          <router-link to="/login" class="nav-item">登录</router-link>
          <router-link to="/register" class="nav-item" style="margin-left: 16px;">注册</router-link>
        </template>
        <button v-else @click="logout" style="margin-left: 16px; padding: 6px 16px;">登出</button>
      </div>
    </header>
    <main style="max-width: 800px; margin: 0 auto; padding: 24px 20px;">
      <router-view />
    </main>
  </div>
</template>

<script setup>
import { onMounted } from 'vue'
import { useAuthStore } from './stores/auth'
import { useRouter } from 'vue-router'
import api from './api'

const authStore = useAuthStore()
const router = useRouter()

onMounted(async () => {
  if (authStore.token) {
    try {
      const res = await api.get('/auth/me')
      authStore.setAdmin(res.data.isAdmin)
    } catch (e) {
      // token 失效，清除
      authStore.logout()
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
</style>
