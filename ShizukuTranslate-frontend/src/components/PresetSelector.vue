<template>
  <div style="margin-top: 16px;">
    <p style="margin-bottom: 6px; font-size:14px; color:#555;">附加预设（可多选）</p>
    <div class="presets-group">
      <label v-for="preset in options" :key="preset">
        <input type="checkbox" :value="preset" :checked="modelValue.includes(preset)" @change="togglePreset(preset)" />
        {{ preset }}
      </label>
    </div>
  </div>
</template>

<script setup lang="ts">
const props = defineProps<{
  options: string[]
  modelValue: string[]
}>()

const emit = defineEmits<{
  (e: 'update:modelValue', value: string[]): void
}>()

function togglePreset(preset: string) {
  const current = [...props.modelValue]
  const idx = current.indexOf(preset)
  if (idx >= 0) {
    current.splice(idx, 1)
  } else {
    current.push(preset)
  }
  emit('update:modelValue', current)
}
</script>

<style scoped>
.presets-group {
  display: flex;
  flex-wrap: wrap;
  gap: 12px;
}
.presets-group label {
  display: flex;
  align-items: center;
  gap: 4px;
  font-size: 14px;
  cursor: pointer;
}
</style>
