<script setup>
import { onMounted, ref } from 'vue'
import { api } from '../../api/bridge'

const content = ref('')
const path = ref('')
const original = ref('')
const loading = ref(true)
const busy = ref(false)
const error = ref('')
const notice = ref('')

const emit = defineEmits(['dirty', 'saved'])

async function load() {
  loading.value = true
  error.value = ''
  try {
    const r = await api.getGlobalMemory()
    content.value = r.content || ''
    original.value = r.content || ''
    path.value = r.path || ''
  } catch (e) {
    error.value = String(e?.message || e)
  } finally {
    loading.value = false
  }
}

async function save() {
  busy.value = true
  error.value = ''
  notice.value = ''
  try {
    const r = await api.saveGlobalMemory(content.value)
    original.value = r.content || ''
    content.value = r.content || ''
    notice.value = '已保存，所有会话立即生效'
    emit('dirty', false)
    emit('saved')
  } catch (e) {
    error.value = String(e?.message || e)
  } finally {
    busy.value = false
  }
}

async function openDir() {
  await api.openDir('memory')
}

function onInput() {
  emit('dirty', content.value !== original.value)
}

onMounted(load)
</script>

<template>
  <div class="wrap">
    <div class="path">
      文件：<code>{{ path }}</code>
      <button class="link" @click="openDir">打开目录</button>
    </div>

    <div v-if="loading" class="hint">加载中…</div>
    <template v-else>
      <p class="tip">
        全局记忆对所有工作空间生效，内容会作为「环境记忆」注入 agent。
        适合写长期稳定的偏好与事实；一次性的临时信息不要放这里。
      </p>

      <textarea v-model="content" rows="16" spellcheck="false" @input="onInput"></textarea>

      <div class="foot">
        <span v-if="notice" class="notice">{{ notice }}</span>
        <button @click="load" :disabled="busy">重新加载</button>
        <button class="primary" :disabled="busy" @click="save">{{ busy ? '保存中…' : '保存' }}</button>
      </div>
    </template>

    <div v-if="error" class="error">⚠ {{ error }}</div>
  </div>
</template>

<style scoped>
.wrap {
  display: flex;
  flex-direction: column;
  gap: 12px;
}

.path {
  font-size: 11.5px;
  color: var(--text-3);
  word-break: break-all;
}

.path code {
  background: var(--panel-2);
  padding: 1px 4px;
  border-radius: 3px;
}

.hint {
  padding: 18px 4px;
  color: var(--text-3);
  font-size: 13px;
}

.tip {
  margin: 0;
  font-size: 12px;
  color: var(--text-3);
  line-height: 1.7;
}

textarea {
  width: 100%;
  min-height: 320px;
  font-family: ui-monospace, Consolas, monospace;
  font-size: 12.5px;
  line-height: 1.7;
  resize: vertical;
}

.foot {
  display: flex;
  align-items: center;
  justify-content: flex-end;
  gap: 10px;
}

.notice {
  margin-right: auto;
  font-size: 12.5px;
  color: var(--primary);
}

.error {
  font-size: 12.5px;
  color: var(--danger);
}

.link {
  border: none;
  background: none;
  color: var(--primary);
  cursor: pointer;
  font-size: 12px;
  padding: 2px 4px;
}
</style>
