<template>
  <div class="ocr-preview">
    <div class="ocr-preview-grid">
      <div v-for="(item, index) in previews" :key="index" class="ocr-preview-item">
        <img :src="item" :alt="`上传的图片 ${index + 1}`" />
        <button
          class="ocr-preview-remove"
          type="button"
          title="移除这张"
          @click="$emit('remove', index)"
        >×</button>
        <span v-if="previews.length > 1" class="ocr-preview-index">{{ index + 1 }}</span>
      </div>
    </div>
    <div class="ocr-preview-actions">
      <button class="btn-sm btn-remove" @click="$emit('clear')">
        {{ previews.length > 1 ? '移除全部' : '移除' }}
      </button>
      <button class="btn-sm btn-primary" @click="$emit('ocr')" :disabled="loading">
        {{ loading ? '识别中...' : (previews.length > 1 ? `PaddleOCR（${previews.length} 张）` : 'PaddleOCR') }}
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
  previews: string[]
  loading: boolean
  polish: boolean
  threshold: number
}>()

defineEmits<{
  (e: 'ocr'): void
  (e: 'clear'): void
  (e: 'remove', index: number): void
  (e: 'update:polish', value: boolean): void
  (e: 'update:threshold', value: number): void
}>()
</script>

<style scoped>
.ocr-preview {
  max-width: 100%;
}
.ocr-preview-grid {
  display: flex;
  flex-wrap: wrap;
  gap: 12px;
}
.ocr-preview-item {
  position: relative;
  flex: 0 0 auto;
}
.ocr-preview-item img {
  display: block;
  max-height: 150px;
  max-width: 150px;
  min-width: 64px;
  min-height: 64px;
  object-fit: cover;
  border-radius: 6px;
  box-shadow: 0 2px 8px rgba(0,0,0,0.1);
  background: #f1f3f5;
}
.ocr-preview-remove {
  position: absolute;
  top: -6px;
  right: -6px;
  width: 20px;
  height: 20px;
  padding: 0;
  border: none;
  border-radius: 50%;
  background: #e03131;
  color: #fff;
  font-size: 14px;
  line-height: 1;
  cursor: pointer;
}
.ocr-preview-index {
  position: absolute;
  left: 4px;
  bottom: 4px;
  padding: 0 5px;
  border-radius: 8px;
  background: rgba(0,0,0,0.55);
  color: #fff;
  font-size: 11px;
}
.ocr-preview-actions {
  margin-top: 10px;
  display: flex;
  gap: 8px;
  justify-content: center;
}
</style>
