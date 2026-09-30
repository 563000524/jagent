<script setup>
import { onMounted, onUnmounted } from 'vue'
import { answerConfirm, confirmState } from '../utils/confirm'

/**
 * 全局确认框（配合 utils/confirm.js 的命令式 API）。
 * 在 App.vue 里挂一次即可。
 */
const state = confirmState()

function onKeydown(e) {
  if (!state.visible) return
  if (e.key === 'Escape') answerConfirm(false)
  else if (e.key === 'Enter') answerConfirm(true)
}

onMounted(() => window.addEventListener('keydown', onKeydown))
onUnmounted(() => window.removeEventListener('keydown', onKeydown))
</script>

<template>
  <div v-if="state.visible" class="mask" @click.self="answerConfirm(false)">
    <div class="dialog">
      <div class="dialog-title">{{ state.title }}</div>
      <div class="dialog-body">
        <p class="msg">{{ state.message }}</p>
        <p v-if="state.detail" class="detail">{{ state.detail }}</p>
      </div>
      <div class="dialog-foot">
        <button @click="answerConfirm(false)">{{ state.cancelText }}</button>
        <button class="primary" :class="{ danger: state.danger }" @click="answerConfirm(true)">
          {{ state.confirmText }}
        </button>
      </div>
    </div>
  </div>
</template>

<style scoped>
.mask {
  position: fixed;
  inset: 0;
  background: rgba(20, 24, 30, 0.35);
  display: flex;
  align-items: center;
  justify-content: center;
  z-index: 1000;
}

.dialog {
  width: 420px;
  max-width: calc(100vw - 48px);
  background: var(--panel);
  border: 1px solid var(--border);
  border-radius: 12px;
  box-shadow: 0 12px 40px rgba(0, 0, 0, 0.18);
  overflow: hidden;
}

.dialog-title {
  padding: 14px 18px 0;
  font-size: 14px;
  font-weight: 600;
}

.dialog-body {
  padding: 10px 18px 16px;
}

.msg {
  margin: 0;
  font-size: 13px;
  line-height: 1.65;
  color: var(--text-2);
  word-break: break-word;
}

.detail {
  margin: 10px 0 0;
  font-size: 11.5px;
  line-height: 1.6;
  color: var(--text-3);
  background: var(--panel-2);
  border: 1px solid var(--border);
  border-radius: 8px;
  padding: 8px 10px;
  word-break: break-all;
}

.dialog-foot {
  display: flex;
  justify-content: flex-end;
  gap: 8px;
  padding: 12px 18px;
  background: var(--panel-2);
  border-top: 1px solid var(--border);
}

.primary.danger {
  background: var(--danger);
  border-color: var(--danger);
}
</style>
