<template>
  <div class="image-preview">
    <div class="image-preview-grid">
      <div v-for="(item, index) in previews" :key="index" class="image-preview-item">
        <img :src="item" :alt="t('components.imagePreview.imageAlt', { index: index + 1 })" />
        <button
          class="image-preview-remove"
          type="button"
          :title="t('components.imagePreview.removeImage')"
          @click="$emit('remove', index)"
        >×</button>
        <span v-if="previews.length > 1" class="image-preview-index">{{ index + 1 }}</span>
      </div>
    </div>
    <div v-if="previews.length > 1" class="image-preview-actions">
      <button class="btn-sm btn-remove" @click="$emit('clear')">{{ t('components.imagePreview.removeAll') }}</button>
    </div>
  </div>
</template>

<script setup lang="ts">
import { useI18n } from 'vue-i18n'

const { t } = useI18n()

defineProps<{
  previews: string[]
}>()

defineEmits<{
  (e: 'clear'): void
  (e: 'remove', index: number): void
}>()
</script>

<style scoped>
.image-preview {
  max-width: 100%;
}
.image-preview-grid {
  display: flex;
  flex-wrap: wrap;
  gap: 12px;
}
.image-preview-item {
  position: relative;
  flex: 0 0 auto;
}
.image-preview-item img {
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
.image-preview-remove {
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
.image-preview-index {
  position: absolute;
  left: 4px;
  bottom: 4px;
  padding: 0 5px;
  border-radius: 8px;
  background: rgba(0,0,0,0.55);
  color: #fff;
  font-size: 11px;
}
.image-preview-actions {
  margin-top: 10px;
  display: flex;
  gap: 8px;
  justify-content: center;
}
</style>
