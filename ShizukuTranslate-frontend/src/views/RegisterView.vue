<template>
  <div class="card small-card">
    <h2 style="margin-top:0; font-weight:600;">注册</h2>
    <form @submit.prevent="submit">
      <input v-model.trim="username" type="text" placeholder="用户名" style="margin-bottom:12px;" />
      <input v-model.trim="email" type="email" placeholder="邮箱" style="margin-bottom:12px;" />
      <input v-model="password" type="password" placeholder="密码" style="margin-bottom:16px;" />
      <button type="submit" style="width:100%;">注册</button>
    </form>
    <p v-if="error" style="color:#e03131; margin-top:12px;">{{ error }}</p>
  </div>

  <!-- 使用条约模态框 -->
  <div v-if="showTerms" class="modal-mask" @click.self="closeTerms">
    <div class="modal">
      <h2 style="margin-top:0; font-weight:600;">使用条约</h2>
      <div class="terms-body" ref="termsBody" @scroll="onScroll">
        <h3>基本信息</h3>
        <ol>
          <li>网站更新日期 2026.7.5</li>
        </ol>
        <h3>使用说明</h3>
        <ol>
          <li>注册并登录账号。</li>
          <li>在翻译页面粘贴原文。</li>
          <li>选择模型，勾选需要的预设（不勾也没毛病）。</li>
          <li>点击“开始翻译”，等待结果。</li>
          <li>可复制译文，或前往历史记录查看详情。</li>
        </ol>
        <h3>使用协议</h3>
        <ol>
          <li>在公众平台发布本服务的翻译结果时，应无疑义地、显著地标明“AI翻译”、“机翻”等标签。</li>
          <li>在公众平台发布本服务的翻译结果时，应无疑义地、显著地标明“AI翻译”、“机翻”等标签。</li>
          <li>在公众平台发布本服务的翻译结果时，应无疑义地、显著地标明“AI翻译”、“机翻”等标签。</li>
          <li>禁止对本服务进行反向工程、破解、攻击或其他干扰服务正常运行的行为。</li>
          <li>禁止滥用本服务进行高频、超大容量请求，影响他人正常使用。</li>
          <li>本服务基于大语言模型自动生成翻译结果，不保证译文的准确性、完整性、适用性或合法性。因信赖或使用翻译结果所产生的任何直接或间接损失，本服务不承担任何责任。</li>
          <li>本服务通过第三方 API 提供翻译能力，因第三方模型行为（包括但不限于拒绝翻译、内容截断、模型输出不当等）引发的问题，本服务不承担连带责任。</li>
          <li>本服务有权根据运营需要，暂停或终止部分或全部服务，并在合理范围内提前通知用户。</li>
          <li>违反本协议的，服务器管理员有权暂停或终止其账号，且对其IP地址区段进行封禁。</li>
          <li>服务器管理员（Sh1Zuku）保留本条款的所有解释权与修订权。</li>
          <li>使用本网站进行翻译活动被视为您完全阅读、充分理解并同意本协议的行为。</li>
        </ol>
        <h3>注意事项</h3>
        <ol>
          <li>因为接的是我个人的API-KEY，所以尽量不要把网址推给群外的陌生人。</li>
          <li>如果要推，跟我说一声，群里@我，或者加我QQ2163018547都可以。</li>
          <li>如果此网站被恶意传播，导致预算被烧没，我会考虑关停。</li>
          <li>各位大哥大姐手下留情，我的钱也不是大风吹来的orz。</li>
          <li>当然，正常使用是完全没问题的，别担心翻译的文本太长，可劲造！</li>
          <li>另外，历史记录是存储在本地的，丢了咱可不负责……</li>
          <li>AI生成的内容可能会有误差，可能给你翻一半变成日文，或者繁体，再生成一次就行。</li>
        </ol>
      </div>
      <div style="margin-top:12px;">
        <label style="display:flex; align-items:center; gap:6px; font-size:14px;">
          <input type="checkbox" v-model="agreed" :disabled="!reachedBottom" style="width:auto;" />
          我已了解并同意以上所有内容
        </label>
      </div>
      <div style="margin-top:16px; display:flex; gap:12px; justify-content:flex-end;">
        <button class="btn-sm btn-remove" @click="closeTerms">取消</button>
        <button class="btn-sm btn-primary" :disabled="!agreed" @click="confirmRegister">同意并注册</button>
      </div>
    </div>
  </div>
</template>

<script setup>
import { ref, nextTick } from 'vue'
import { useRouter } from 'vue-router'
import api from '../api'

const username = ref('')
const email = ref('')
const password = ref('')
const error = ref('')
const router = useRouter()

const showTerms = ref(false)
const reachedBottom = ref(false)
const agreed = ref(false)
const termsBody = ref(null)

function submit() {
  if (!username.value || !email.value || !password.value) {
    error.value = '请填写完整信息'
    return
  }
  error.value = ''
  showTerms.value = true
  reachedBottom.value = false
  agreed.value = false
  nextTick(() => {
    if (termsBody.value) termsBody.value.scrollTop = 0
  })
}

function onScroll() {
  const el = termsBody.value
  if (!el) return
  if (el.scrollTop + el.clientHeight >= el.scrollHeight - 2) {
    reachedBottom.value = true
  }
}

function closeTerms() {
  showTerms.value = false
}

async function confirmRegister() {
  showTerms.value = false
  await register()
}

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

<style scoped>
.modal-mask {
  position: fixed;
  inset: 0;
  background: rgba(0, 0, 0, 0.5);
  display: flex;
  align-items: center;
  justify-content: center;
  z-index: 100;
  padding: 20px;
}
.modal {
  background: #fff;
  border-radius: 8px;
  padding: 24px;
  max-width: 640px;
  width: 100%;
  max-height: 85vh;
  display: flex;
  flex-direction: column;
}
.terms-body {
  overflow-y: auto;
  flex: 1;
  min-height: 0;
  border: 1px solid #eee;
  border-radius: 6px;
  padding: 4px 16px;
}
.terms-body h3 {
  margin-bottom: 4px;
}
.terms-body ol {
  margin-top: 0;
}
</style>
