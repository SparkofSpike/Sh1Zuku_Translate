<template>
  <div class="card small-card">
    <h2 style="margin-top:0; font-weight:600;">登录</h2>
    <form @submit.prevent="login">
      <input v-model.trim="username" type="text" placeholder="用户名或邮箱" style="margin-bottom:16px;" />
      <input v-model="password" type="password" placeholder="密码" style="margin-bottom:16px;" />
      <button type="submit" style="width:100%;">登录</button>
    </form>
    <p v-if="error" style="color:#e03131; margin-top:12px;">{{ error }}</p>
  </div>
</template>

<script setup>
import { ref } from 'vue'
import api from '../api'
import { useAuthStore } from '../stores/auth'
import { useRouter } from 'vue-router'

const username = ref('')
const password = ref('')
const error = ref('')
const authStore = useAuthStore()
const router = useRouter()

async function login() {
  try {
    const res = await api.post('/auth/login', { username: username.value, password: password.value })
    authStore.setToken(res.data.token)
    router.push('/')
  } catch (e) {
    error.value = e.response?.data?.error || '登录失败'
  }
}
</script>
