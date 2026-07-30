<template>
  <div class="card small-card">
    <h2 style="margin-top:0; font-weight:600;">用户反馈问卷</h2>
    <p style="color:#555; font-size:14px;">欢迎提供反馈，帮助我们改进服务。</p>

    <div style="margin-top:16px;">
      <p style="margin-bottom:6px;">1. 翻译质量满意度（1-5）</p>
      <div style="display:flex; gap:12px;">
        <label v-for="n in 5" :key="n" style="cursor:pointer;">
          <input type="radio" :value="n" v-model="translationQuality" style="width:auto;" /> {{ n }}
        </label>
      </div>
    </div>

    <div style="margin-top:16px;">
      <p style="margin-bottom:6px;">2. 网站使用体验（1-5）</p>
      <div style="display:flex; gap:12px;">
        <label v-for="n in 5" :key="n" style="cursor:pointer;">
          <input type="radio" :value="n" v-model="experienceQuality" style="width:auto;" /> {{ n }}
        </label>
      </div>
    </div>

    <div style="margin-top:16px;">
      <p style="margin-bottom:6px;">3. 您期待的功能</p>
      <select v-model="favoriteFeature" style="width:100%;">
        <option value="">请选择</option>
        <option value="小语种翻译">小语种翻译</option>
        <option value="图片翻译">图片翻译</option>
        <option value="网页爬虫">网页爬虫</option>
        <option value="浏览器插件">浏览器插件</option>
        <option value="流式输出">流式输出</option>
      </select>
    </div>

    <div style="margin-top:16px;">
      <p style="margin-bottom:6px;">4. 建议或意见（选填）</p>
      <textarea v-model="suggestion" rows="4" placeholder="有什么想说的..." style="width:100%;"></textarea>
    </div>

    <button @click="submit" :disabled="submitting" style="margin-top:16px; width:100%;">
      {{ submitting ? '提交中...' : '提交反馈' }}
    </button>
    <p v-if="success" style="color:#2e7d32; margin-top:12px;">{{ success }}</p>
    <p v-if="error" style="color:#e03131; margin-top:12px;">{{ error }}</p>
  </div>
</template>

<script setup>
import { ref } from 'vue'
import api from '../api'

const translationQuality = ref(0)
const experienceQuality = ref(0)
const favoriteFeature = ref('')
const suggestion = ref('')
const submitting = ref(false)
const success = ref('')
const error = ref('')

async function submit() {
  if (!translationQuality.value || !experienceQuality.value) {
    error.value = '请完成评分'
    return
  }
  submitting.value = true
  error.value = ''
  success.value = ''
  try {
    await api.post('/survey', {
      translationQuality: translationQuality.value,
      experienceQuality: experienceQuality.value,
      favoriteFeature: favoriteFeature.value || undefined,
      suggestion: suggestion.value || undefined
    })
    success.value = '提交成功，感谢您的反馈！'
  } catch (e) {
    error.value = e.response?.data?.error || '提交失败'
  } finally {
    submitting.value = false
  }
}
</script>
