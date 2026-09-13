<template>
  <div class="card small-card">
    <h2 style="margin-top:0; font-weight:600;">{{ t('nav.register') }}</h2>
    <form @submit.prevent="submit">
      <input v-model.trim="username" type="text" :placeholder="t('auth.username')" style="margin-bottom:12px;" />
      <input v-model.trim="email" type="email" :placeholder="t('auth.register.emailPlaceholder')" style="margin-bottom:12px;" />
      <div style="display:flex; gap:8px; margin-bottom:12px;">
        <input v-model.trim="code" type="text" inputmode="numeric" maxlength="6" :placeholder="t('auth.register.codePlaceholder')" style="flex:1; min-width:0;" />
        <button type="button" style="white-space:nowrap;" @click="sendCode" :disabled="sending || countdown > 0">
          {{ countdown > 0 ? t('auth.register.resendIn', { seconds: countdown }) : (sending ? t('auth.register.sending') : t('auth.register.sendCode')) }}
        </button>
      </div>
      <input v-model="password" type="password" :placeholder="t('auth.register.passwordPlaceholder')" style="margin-bottom:16px;" />
      <button type="submit" style="width:100%;">{{ t('nav.register') }}</button>
    </form>
    <p v-if="message" style="color:#2b8a3e; margin-top:12px;">{{ message }}</p>
    <p v-if="error" style="color:#e03131; margin-top:12px;">{{ error }}</p>
  </div>

  <!-- 使用条约模态框 -->
  <div v-if="showTerms" class="modal-mask" @click.self="closeTerms">
    <div class="modal">
      <h2 style="margin-top:0; font-weight:600;">{{ t('auth.terms.title') }}</h2>
      <div class="terms-body" ref="termsBody" @scroll="onScroll">
        <h3>{{ t('auth.terms.heading') }}</h3>
        <ol>
          <li>{{ t('auth.terms.item1') }}</li>
          <li>{{ t('auth.terms.item2') }}</li>
          <li>{{ t('auth.terms.item3') }}</li>
          <li>{{ t('auth.terms.item4') }}</li>
          <li>{{ t('auth.terms.item5') }}</li>
          <li>{{ t('auth.terms.item6') }}</li>
          <li>{{ t('auth.terms.item7') }}</li>
          <li>{{ t('auth.terms.item8') }}</li>
          <li>{{ t('auth.terms.item9') }}</li>
          <li>{{ t('auth.terms.item10') }}</li>
          <li>{{ t('auth.terms.item11') }}</li>
        </ol>
      </div>
      <div style="margin-top:12px;">
        <label style="display:flex; align-items:center; gap:6px; font-size:14px;">
          <input type="checkbox" v-model="agreed" :disabled="!reachedBottom" style="width:auto;" />
          {{ t('auth.terms.agree') }}
        </label>
      </div>
      <div style="margin-top:16px; display:flex; gap:12px; justify-content:flex-end;">
        <button class="btn-sm btn-remove" @click="closeTerms">{{ t('common.cancel') }}</button>
        <button class="btn-sm btn-primary" :disabled="!agreed" @click="confirmRegister">{{ t('auth.terms.agreeAndRegister') }}</button>
      </div>
    </div>
  </div>
</template>

<script setup>
import { onUnmounted, ref, nextTick } from 'vue'
import { useI18n } from 'vue-i18n'
import { useRouter } from 'vue-router'
import api, { sendEmailCode } from '../api'

const { t } = useI18n()

const username = ref('')
const email = ref('')
const code = ref('')
const password = ref('')
const error = ref('')
const message = ref('')
const router = useRouter()

const showTerms = ref(false)
const reachedBottom = ref(false)
const agreed = ref(false)
const termsBody = ref(null)

const sending = ref(false)
const countdown = ref(0)
let countdownTimer = null

onUnmounted(() => {
  if (countdownTimer) clearInterval(countdownTimer)
})

function isValidEmail(value) {
  return /^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(value)
}

async function sendCode() {
  if (!isValidEmail(email.value)) {
    error.value = t('auth.errors.enterValidEmail')
    return
  }
  error.value = ''
  message.value = ''
  sending.value = true
  try {
    await sendEmailCode(email.value)
    message.value = t('auth.register.codeSent')
    startCountdown()
  } catch (e) {
    error.value = e.response?.data?.error || t('auth.errors.codeSendFailed')
  } finally {
    sending.value = false
  }
}

function startCountdown() {
  if (countdownTimer) clearInterval(countdownTimer)
  countdown.value = 60
  countdownTimer = setInterval(() => {
    countdown.value -= 1
    if (countdown.value <= 0) {
      countdown.value = 0
      if (countdownTimer) clearInterval(countdownTimer)
    }
  }, 1000)
}

function submit() {
  if (!username.value || !email.value || !code.value || !password.value) {
    error.value = t('auth.errors.incomplete')
    return
  }
  if (!isValidEmail(email.value)) {
    error.value = t('auth.errors.emailFormat')
    return
  }
  if (password.value.length < 6) {
    error.value = t('auth.errors.passwordTooShort')
    return
  }
  error.value = ''
  message.value = ''
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
      password: password.value,
      code: code.value
    })
    router.push('/login')
  } catch (e) {
    error.value = e.response?.data?.error || t('auth.errors.registerFailed')
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
