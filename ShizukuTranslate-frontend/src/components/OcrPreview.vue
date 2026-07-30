<template>
  <div class="ocr-preview">
    <img :src="preview" alt="上传的图片" />
    <div class="ocr-preview-actions">
      <button class="btn-sm btn-remove" @click="$emit('clear')">移除</button>
      <button class="btn-sm btn-primary" @click="$emit('ocr')" :disabled="loading">
        {{ loading ? '识别中...' : 'PaddleOCR' }}
      </button>
    </div>
    <label style="margin-top:8px;display:inline-flex;align-items:center;gap:6px;font-size:13px;cursor:pointer;">
      <input type="checkbox" :checked="polish" @change="$emit('update:polish', ($event.target as HTMLInputElement).checked)">
      修复分段
    </label>
    <div style="margin-top:8px;display:flex;align-items:center;gap:8px;font-size:13px;">
      <span>置信度:</span>
      <input type="range" min="0.1" max="0.9" step="0.05" :value="threshold" @input="$emit('update:threshold', parseFloat(($event.target as HTMLInputElement).value))" style="width:100px;">
      <input type="number" min="0.1" max="0.9" step="0.05" :value="threshold" @input="$emit('update:threshold', parseFloat(($event.target as HTMLInputElement).value))" style="width:55px;padding:2px 4px;border:1px solid #ccc;border-radius:4px;font-size:13px;">
    </div>
  </div>
</template>

<script setup lang="ts">
defineProps<{
  preview: string
  loading: boolean
  polish: boolean
  threshold: number
}>()

defineEmits<{
  (e: 'ocr'): void
  (e: 'clear'): void
  (e: 'update:polish', value: boolean): void
  (e: 'update:threshold', value: number): void
}>()
</script>

<style scoped>
.ocr-preview {
  max-width: 100%;
}
.ocr-preview img {
  max-height: 300px;
  max-width: 100%;
  border-radius: 6px;
  box-shadow: 0 2px 8px rgba(0,0,0,0.1);
}
.ocr-preview-actions {
  margin-top: 10px;
  display: flex;
  gap: 8px;
  justify-content: center;
}
</style>
