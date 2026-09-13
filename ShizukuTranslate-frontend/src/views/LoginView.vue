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
import { useRouter } from 'vue-router'

const { t } = useI18n()

const username = ref('')
const password = ref('')
const error = ref('')
const authStore = useAuthStore()
const router = useRouter()

async function login() {
  try {
    const res = await api.post('/auth/login', { username: username.value, password: password.value })
    authStore.setToken(res.data.token)
    const me = await api.get('/auth/me')
    authStore.setAdmin(!!me.data.isAdmin)
    authStore.setEmailVerified(!!me.data.emailVerified)
    router.push('/')
  } catch (e) {
    error.value = e.response?.data?.error || t('auth.errors.loginFailed')
  }
}
</script>
