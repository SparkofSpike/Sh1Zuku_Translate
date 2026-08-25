<template>
  <div
    class="drop-zone"
    :class="{ 'drop-zone--active': dragging }"
    @dragenter.prevent="dragging = true"
    @dragover.prevent="dragging = true"
    @dragleave.prevent="dragging = false"
    @drop.prevent="onDrop"
    @paste.prevent="onPaste"
    tabindex="0"
  >
    <input
      ref="fileInput"
      type="file"
      accept="image/*,.txt,.md,text/plain,text/markdown"
      style="display:none"
      @change="onFileSelected"
    />
    <div class="drop-zone-hint">
      <p style="font-size:40px; margin:0; font-weight:200; color:#aaa;">+</p>
      <p>拖拽图片或文档到此处，或 <a @click="clickFileInput">点击选择文件</a></p>
      <p style="font-size:13px; color:#999;">支持图片、TXT、MD；图片也可 Ctrl+V 粘贴</p>
    </div>
  </div>
</template>

<script setup lang="ts">
import { ref } from 'vue'

const emit = defineEmits<{
  (e: 'file-selected', file: File): void
}>()

const dragging = ref(false)
const fileInput = ref<HTMLInputElement | null>(null)

function clickFileInput() {
  fileInput.value?.click()
}

function onFileSelected(e: Event) {
  const target = e.target as HTMLInputElement
  const file = target.files?.[0]
  if (file && (file.type.startsWith('image/') || isTextFile(file))) emit('file-selected', file)
}

function onDrop(e: DragEvent) {
  dragging.value = false
  const file = e.dataTransfer?.files?.[0]
  if (file && (file.type.startsWith('image/') || isTextFile(file))) {
    emit('file-selected', file)
  }
}

function isTextFile(file: File) {
  return file.name.toLowerCase().endsWith('.txt') || file.name.toLowerCase().endsWith('.md')
}

function onPaste(e: ClipboardEvent) {
  const items = e.clipboardData?.items
  if (!items) return
  for (const item of items) {
    if (item.type.startsWith('image/')) {
      const file = item.getAsFile()
      if (file) {
        emit('file-selected', file)
        break
      }
    }
  }
}
</script>

<style scoped>
.drop-zone {
  border: 2px dashed #ccc;
  border-radius: 10px;
  padding: 20px;
  text-align: center;
  cursor: pointer;
  transition: all 0.2s;
  outline: none;
}
.drop-zone:hover {
  border-color: #888;
  background: #fafafa;
}
.drop-zone--active {
  border-color: #4a9eff;
  background: #eef6ff;
}
.drop-zone-hint p {
  margin: 6px 0;
}
.drop-zone-hint a {
  color: #4a9eff;
  cursor: pointer;
  text-decoration: underline;
}
</style>
