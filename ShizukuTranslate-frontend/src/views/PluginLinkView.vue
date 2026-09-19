<template>
  <!-- `pending` 期间不渲染内容：未登录时这里会立刻跳转登录页，避免闪一下确认卡片。 -->
  <div v-if="status !== 'pending'" class="card small-card">
    <h2 style="margin-top:0; font-weight:600;">{{ t('pluginLink.title') }}</h2>

    <template v-if="status === 'confirm' || status === 'submitting'">
      <p class="permission">{{ t('pluginLink.permission') }}</p>
      <p class="code-row">
        <span class="muted">{{ t('pluginLink.code') }}</span>
        <code>{{ code }}</code>
      </p>
      <div class="actions">
        <button class="btn-sm btn-remove" type="button" :disabled="submitting" @click="cancel">
          {{ t('pluginLink.cancel') }}
        </button>
        <button class="btn-sm btn-primary" type="button" :disabled="submitting" @click="approve">
          {{ submitting ? t('pluginLink.submitting') : t('pluginLink.allow') }}
        </button>
      </div>
    </template>

    <template v-else-if="status === 'success'">
      <p class="success">{{ t('pluginLink.success') }}</p>
      <p v-if="keyName" class="muted">{{ t('pluginLink.successKeyName', { name: keyName }) }}</p>
    </template>

    <template v-else-if="status === 'cancelled'">
      <p class="muted">{{ t('pluginLink.cancelled') }}</p>
    </template>

    <template v-else-if="status === 'invalid'">
      <p class="error-title">{{ t('pluginLink.errors.incompleteTitle') }}</p>
      <p class="error">{{ t('pluginLink.errors.incomplete') }}</p>
    </template>

    <template v-else-if="status === 'error'">
      <p class="error">{{ errorMessage }}</p>
      <router-link v-if="errorKind === 'emailNotVerified'" class="verify-link" to="/profile">
        {{ t('pluginLink.errors.emailNotVerifiedAction') }}
      </router-link>
    </template>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import { useRoute, useRouter } from 'vue-router'
import api from '../api'
import { useAuthStore } from '../stores/auth'

const { t } = useI18n()
const route = useRoute()
const router = useRouter()
const authStore = useAuthStore()

/**
 * 后端下发的设备授权码是短码（字母 + 数字）。这里只做格式预检，
 * 真正是否有效（已过期 / 已用过）由 approve 接口判定。
 */
const CODE_PATTERN = /^[A-Za-z0-9]{4,64}$/

type Status = 'pending' | 'confirm' | 'submitting' | 'success' | 'cancelled' | 'invalid' | 'error'
type ErrorKind = 'invalidOrExpired' | 'emailNotVerified' | 'alreadyApproved' | 'unknown'

const status = ref<Status>('pending')
const errorKind = ref<ErrorKind>('unknown')
const keyName = ref('')

/** 从 query 里取授权码；`?code=a&code=b` 这种数组形式一律视为无效。 */
const code = computed(() => (typeof route.query.code === 'string' ? route.query.code.trim() : ''))

const submitting = computed(() => status.value === 'submitting')

/** 把错误种类映射成文案，避免在模板里拼动态 key。 */
const errorMessage = computed(() => {
  switch (errorKind.value) {
    case 'invalidOrExpired':
      return t('pluginLink.errors.invalidOrExpired')
    case 'emailNotVerified':
      return t('pluginLink.errors.emailNotVerified')
    case 'alreadyApproved':
      return t('pluginLink.errors.alreadyApproved')
    default:
      return t('pluginLink.errors.unknown')
  }
})

onMounted(() => {
  if (!code.value || !CODE_PATTERN.test(code.value)) {
    status.value = 'invalid'
    return
  }
  if (!authStore.token) {
    // approve 需要 Bearer，所以未登录时先送回登录页，并把当前完整路径（含 code）放进
    // redirect，登录成功后由 LoginView 跳回本页继续授权。
    router.replace('/login?redirect=' + encodeURIComponent(route.fullPath))
    return
  }
  status.value = 'confirm'
})

/** 业务错误码在 body 的 `error` 字段；网络失败等异常响应可能没有 body，逐层防御性读取。 */
function classifyError(err: unknown) {
  const response = (err as { response?: { status?: number; data?: { error?: unknown } } } | undefined)?.response
  const raw = response?.data?.error
  const errorCode = typeof raw === 'string' ? raw : ''
  const httpStatus = typeof response?.status === 'number' ? response.status : 0

  // 只有带业务错误码的 403 才是「邮箱未验证」；裸 403 是 Security 在 controller 之前
  // 拒绝的（未登录 / token 失效），由 approve() 的 catch 单独处理成重新登录。
  if (errorCode === 'invalid_or_expired_code' || (!errorCode && httpStatus === 400)) {
    errorKind.value = 'invalidOrExpired'
  } else if (errorCode === 'email_not_verified') {
    errorKind.value = 'emailNotVerified'
  } else if (errorCode === 'code_already_approved' || (!errorCode && httpStatus === 409)) {
    errorKind.value = 'alreadyApproved'
  } else {
    errorKind.value = 'unknown'
  }
}

async function approve() {
  // 提交中或非确认态直接忽略；按钮本身也已是 disabled。
  if (submitting.value || status.value !== 'confirm') return
  status.value = 'submitting'
  try {
    const res = await api.post<{ status?: string; keyName?: string }>('/plugin/device-code/approve', {
      code: code.value
    })
    keyName.value = typeof res.data?.keyName === 'string' ? res.data.keyName : ''
    status.value = 'success'
  } catch (err) {
    // 未登录 / token 已失效：Security 在 controller 之前返回 403，body 里没有业务错误码。
    // 这不是「邮箱未验证」，送回登录页，登录成功后会绕回本页继续这次授权。
    const response = (err as { response?: { status?: number; data?: { error?: unknown } } } | undefined)?.response
    if (response?.status === 403 && !response?.data?.error) {
      router.replace('/login?redirect=' + encodeURIComponent(route.fullPath))
      return
    }
    classifyError(err)
    status.value = 'error'
  }
}

function cancel() {
  if (submitting.value) return
  status.value = 'cancelled'
}
</script>

<style scoped>
.permission {
  margin: 4px 0 16px;
}
.code-row {
  margin: 0 0 16px;
  font-size: 14px;
}
.code-row code {
  margin-left: 8px;
  padding: 2px 8px;
  background: #f5f5f5;
  border-radius: 4px;
  font-size: 14px;
  letter-spacing: 1px;
}
.actions {
  display: flex;
  gap: 12px;
  justify-content: flex-end;
}
.muted {
  color: var(--color-muted);
}
.success {
  color: #2b8a3e;
}
.error-title {
  font-weight: 600;
  margin: 0 0 4px;
}
.error {
  color: var(--color-error);
  margin: 0;
}
.verify-link {
  display: inline-block;
  margin-top: 12px;
  text-decoration: underline;
}
</style>
