<template>
  <div class="card small-card">
    <h2 style="margin-top:0; font-weight:600;">注册</h2>
    <form @submit.prevent="register">
      <input v-model.trim="username" type="text" placeholder="用户名" style="margin-bottom:12px;" />
      <input v-model.trim="email" type="email" placeholder="邮箱" style="margin-bottom:12px;" />
      <input v-model="password" type="password" placeholder="密码" style="margin-bottom:16px;" />
      <button type="submit" style="width:100%;">注册</button>
    </form>
    <p v-if="error" style="color:#e03131; margin-top:12px;">{{ error }}</p>
  </div>
</template>

<script setup>
import { ref } from 'vue'
import { useRouter } from 'vue-router'
import api from '../api'

const username = ref('')
const email = ref('')
const password = ref('')
const error = ref('')
const router = useRouter()

async function register() {
  try {
    await api.post('/auth/register', {
      username: username.value,
      email: email.value,
      password: password.value
    })
    router.push('/login')
  } catch (e) {
    error.value = e.response?.data?.error || '注册失败'
  }
}
</script>
