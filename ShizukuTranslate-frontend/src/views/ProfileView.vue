<template>
  <div class="card">
    <h2 style="margin-top:0; font-weight:600;">账号资料</h2>
    <p style="margin:0 0 4px 0;"><strong>用户名：</strong>{{ profile.username }}</p>
    <p style="margin:0 0 4px 0;"><strong>邮箱：</strong>{{ profile.email }}</p>
    <p style="margin:0;"><strong>注册时间：</strong>{{ profile.createdAt || '未知' }}</p>

    <hr />

    <h3 style="margin-top:0;">AI API Key</h3>
    <p style="color:#777; font-size:14px;">配置自己的 DeepSeek API Key 后，翻译将使用你的 Key（不填则用站方 Key）。</p>
    <input v-model.trim="aiKey" type="password" placeholder="sk-..." style="margin-bottom:12px;" />
    <div style="display:flex; gap:12px;">
      <button @click="saveAiKey">保存</button>
      <button v-if="profile.hasAiApiKey" class="btn-sm btn-remove" @click="clearAiKey">清除</button>
    </div>
    <p v-if="profile.hasAiApiKey" style="color:#2b8a3e; margin-top:12px;">已配置个人 API Key</p>
    <p v-if="aiKeyMsg" style="color:#2b8a3e; margin-top:12px;">{{ aiKeyMsg }}</p>
    <p v-if="error" style="color:#e03131; margin-top:12px;">{{ error }}</p>

    <hr />

    <h3 style="margin-top:0;">插件 API Key</h3>
    <p style="color:#777; font-size:14px;">用于浏览器插件调用翻译接口，生成后请妥善保存（只显示一次）。</p>
    <button @click="createPluginKey">生成插件 Key</button>
    <div v-if="newKey" style="margin-top:12px; display:flex; gap:8px; align-items:center;">
      <code style="flex:1; background:#f5f5f5; padding:8px 12px; border-radius:6px; word-break:break-all;">{{ newKey }}</code>
      <button class="btn-sm btn-primary" @click="copyKey">复制</button>
    </div>
    <div v-if="keys.length" style="margin-top:16px;">
      <div v-for="k in keys" :key="k.id" style="border-bottom:1px solid #eee; padding:8px 0; display:flex; align-items:center; gap:12px;">
        <span style="flex:1;">{{ k.name }}</span>
        <span style="color:#777; font-size:13px;">{{ k.keyPrefix }}</span>
        <button class="btn-sm btn-remove" @click="deleteKey(k.id)">删除</button>
      </div>
    </div>
  </div>
</template>

<script setup>
import { ref, onMounted } from 'vue'
import api from '../api'

const profile = ref({ username: '', email: '', hasAiApiKey: false, createdAt: '' })
const aiKey = ref('')
const aiKeyMsg = ref('')
const error = ref('')
const newKey = ref('')
const keys = ref([])

async function loadProfile() {
  try {
    const res = await api.get('/auth/profile')
    profile.value = res.data
  } catch (e) {
    error.value = e.response?.data?.error || '加载失败'
  }
}

async function saveAiKey() {
  try {
    await api.put('/auth/profile/ai-key', { aiApiKey: aiKey.value })
    aiKey.value = ''
    aiKeyMsg.value = '已保存'
    await loadProfile()
  } catch (e) {
    error.value = e.response?.data?.error || '保存失败'
  }
}

async function clearAiKey() {
  try {
    await api.put('/auth/profile/ai-key', { aiApiKey: '' })
    aiKeyMsg.value = '已清除'
    await loadProfile()
  } catch (e) {
    error.value = e.response?.data?.error || '清除失败'
  }
}

async function createPluginKey() {
  try {
    const res = await api.post('/auth/api-key', { name: 'pixiv-plugin' })
    newKey.value = res.data.keyValue
    await loadKeys()
  } catch (e) {
    error.value = e.response?.data?.error || '生成失败'
  }
}

async function copyKey() {
  try {
    await navigator.clipboard.writeText(newKey.value)
    aiKeyMsg.value = '已复制'
  } catch (e) {
    error.value = '复制失败，请手动选择复制'
  }
}

async function loadKeys() {
  try {
    const res = await api.get('/auth/api-keys')
    keys.value = res.data.apiKeys || []
  } catch (e) {
    error.value = e.response?.data?.error || '加载失败'
  }
}

async function deleteKey(id) {
  try {
    await api.delete('/auth/api-key/' + id)
    await loadKeys()
  } catch (e) {
    error.value = e.response?.data?.error || '删除失败'
  }
}

onMounted(() => {
  loadProfile()
  loadKeys()
})
</script>
