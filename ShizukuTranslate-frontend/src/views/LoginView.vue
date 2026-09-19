<template>
  <div class="card small-card">
    <h2 style="margin-top:0; font-weight:600;">{{ t('nav.login') }}</h2>
    <form @submit.prevent="login">
      <input v-model.trim="username" type="text" :placeholder="t('auth.login.usernameOrEmail')" style="margin-bottom:16px;" />
      <input v-model="password" type="password" :placeholder="t('auth.password')" style="margin-bottom:16px;" />
      <button type="submit" style="width:100%;">{{ t('nav.login') }}</button>
    </form>
    <p v-if="error" style="color:#e03131; margin-top:12px;">{{ error }}</p>
  </div>
</template>

<script setup>
import { ref } from 'vue'
import { useI18n } from 'vue-i18n'
import api from '../api'
import { useAuthStore } from '../stores/auth'
import { useRoute, useRouter } from 'vue-router'

const { t } = useI18n()

const username = ref('')
const password = ref('')
const error = ref('')
const authStore = useAuthStore()
const router = useRouter()
const route = useRoute()

// 登录成功后的回跳目标：只接受站内相对路径。`//evil.com` 是协议相对地址，
// `\` 会被浏览器当成 `/`（`/\evil.com` 同样会跳去外部站点），一律忽略。
function safeRedirect(value) {
  if (typeof value !== 'string' || !value.startsWith('/')) return ''
  if (value.startsWith('//') || value.includes('\\')) return ''
  return value
}

async function login() {
  try {
    const res = await api.post('/auth/login', { username: username.value, password: password.value })
    authStore.setToken(res.data.token)
    const me = await api.get('/auth/me')
    authStore.setAdmin(!!me.data.isAdmin)
    authStore.setEmailVerified(!!me.data.emailVerified)
    router.push(safeRedirect(route.query.redirect) || '/')
  } catch (e) {
    error.value = e.response?.data?.error || t('auth.errors.loginFailed')
  }
}
</script>
