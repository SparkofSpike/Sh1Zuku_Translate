<template>
  <transition name="lt-fade">
    <div v-if="visible" class="lt-backdrop" @click.self="emit('decline')">
      <section class="lt-card" role="dialog" aria-modal="true" :aria-label="t('translate.longText.title')">
        <h3 class="lt-title">{{ t('translate.longText.title') }}</h3>
        <p class="lt-body">{{ t('translate.longText.body', { count: formattedCount }) }}</p>
        <p class="lt-note">{{ t('translate.longText.note') }}</p>
        <div class="lt-actions">
          <button class="lt-btn lt-btn--ghost" @click="emit('decline')">{{ t('translate.longText.decline') }}</button>
          <button class="lt-btn lt-btn--primary" @click="emit('confirm')">{{ t('translate.longText.confirm') }}</button>
        </div>
      </section>
    </div>
  </transition>
</template>

<script setup lang="ts">
import { computed, onMounted, onUnmounted } from 'vue'
import { useI18n } from 'vue-i18n'

const props = defineProps<{
  visible: boolean
  /** Character count of the text that triggered the notice. */
  charCount: number
}>()

const emit = defineEmits<{
  (e: 'confirm'): void
  (e: 'decline'): void
}>()

const { t, locale } = useI18n()

const formattedCount = computed(() => {
  try {
    return new Intl.NumberFormat(locale.value).format(props.charCount)
  } catch {
    return String(props.charCount)
  }
})

/** Escape is the "not now" answer, matching the secondary button. */
function onKeydown(event: KeyboardEvent) {
  if (props.visible && event.key === 'Escape') emit('decline')
}

onMounted(() => window.addEventListener('keydown', onKeydown))
onUnmounted(() => window.removeEventListener('keydown', onKeydown))
</script>

<style scoped>
.lt-backdrop {
  position: fixed;
  z-index: 1100;
  inset: 0;
  display: flex;
  align-items: center;
  justify-content: center;
  padding: 20px;
  background: rgba(0, 0, 0, 0.45);
}

.lt-card {
  width: 100%;
  max-width: 480px;
  padding: 28px 28px 22px;
  background: #fff;
  border: 1px solid #eee;
  border-radius: 10px;
  box-shadow: 0 18px 50px rgba(0, 0, 0, 0.18);
}

.lt-title {
  margin: 0 0 12px;
  font-size: 18px;
  font-weight: 600;
  color: #1a1a1a;
}

.lt-body {
  margin: 0 0 10px;
  font-size: 14px;
  line-height: 1.8;
  color: #222;
}

.lt-note {
  margin: 0 0 20px;
  font-size: 12.5px;
  line-height: 1.7;
  color: #777;
}

.lt-actions {
  display: flex;
  justify-content: flex-end;
  gap: 10px;
}

.lt-btn {
  padding: 8px 20px;
  font-size: 14px;
  border-radius: 6px;
  transition: background 0.2s, border-color 0.2s;
}

.lt-btn--primary {
  background: #1a1a1a;
  color: #fff;
  border: 1px solid #1a1a1a;
}

.lt-btn--primary:hover {
  background: #444;
}

.lt-btn--ghost {
  background: #fff;
  color: #555;
  border: 1px solid #ddd;
}

.lt-btn--ghost:hover {
  background: #f5f5f5;
}

.lt-fade-enter-active,
.lt-fade-leave-active {
  transition: opacity 0.18s ease;
}

.lt-fade-enter-from,
.lt-fade-leave-to {
  opacity: 0;
}
</style>
