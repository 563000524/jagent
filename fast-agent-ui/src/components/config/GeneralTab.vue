<script setup>
import { onMounted, reactive, ref } from 'vue'
import { api } from '../../api/bridge'

const emit = defineEmits(['dirty', 'saved'])

const loading = ref(true)
const busy = ref(false)
const error = ref('')
const notice = ref('')
const paths = ref({})

const form = reactive({
  systemPrompt: '',
  temperature: 0.7,
  enableThinking: true,
  maxHistory: 20
})

const dirs = [
  { key: 'agent', label: '数据根目录', open: 'agent' },
  { key: 'models', label: '模型配置目录', open: 'models' },
  { key: 'memory', label: '全局记忆目录', open: 'memory' },
  { key: 'tools', label: '工具配置目录', open: 'tools' },
  { key: 'skills', label: '技能目录', open: 'skills' },
  // 这是登记表文件，不是目录 —— 不给「打开」按钮
  { key: 'workspaces', label: '空间登记表' },
  { key: 'log', label: '日志目录', open: 'log' }
]

async function load() {
  loading.value = true
  error.value = ''
  try {
    const c = await api.getConfig()
    form.systemPrompt = c.systemPrompt || ''
    form.temperature = c.temperature ?? 0.7
    form.enableThinking = !!c.enableThinking
    form.maxHistory = c.maxHistory ?? 20
    paths.value = await api.getPaths()
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
    await api.saveConfig({ ...form })
    notice.value = '已保存'
    emit('dirty', false)
    emit('saved')
  } catch (e) {
    error.value = String(e?.message || e)
  } finally {
    busy.value = false
  }
}

async function openDir(name) {
  try {
    await api.openDir(name)
  } catch (e) {
    error.value = String(e?.message || e)
  }
}

onMounted(load)
</script>

<template>
  <div class="wrap">
    <div v-if="loading" class="hint">加载中…</div>

    <template v-else>
      <label class="field">
        <span class="label">系统提示词</span>
        <textarea v-model="form.systemPrompt" rows="5" @input="emit('dirty', true)"></textarea>
      </label>

      <div class="row2">
        <label class="field">
          <span class="label">温度 {{ form.temperature }}</span>
          <input
            v-model.number="form.temperature"
            type="range"
            min="0"
            max="1.5"
            step="0.1"
            @input="emit('dirty', true)"
          />
        </label>
        <label class="field">
          <span class="label">上下文条数上限</span>
          <input
            v-model.number="form.maxHistory"
            type="number"
            min="1"
            max="100"
            @input="emit('dirty', true)"
          />
        </label>
      </div>

      <label class="switch-field">
        <input
          v-model="form.enableThinking"
          type="checkbox"
          @change="emit('dirty', true)"
        />
        <span>启用深度思考（enable_thinking，部分模型支持）</span>
      </label>

      <div class="foot">
        <span v-if="notice" class="notice">{{ notice }}</span>
        <button class="primary" :disabled="busy" @click="save">{{ busy ? '保存中…' : '保存' }}</button>
      </div>

      <div class="paths">
        <div class="sec-title">数据位置</div>
        <div v-for="d in dirs" :key="d.key" class="path-row">
          <span class="path-label">{{ d.label }}</span>
          <code>{{ paths[d.key] }}</code>
          <button v-if="d.open" class="link" @click="openDir(d.open)">打开</button>
        </div>
      </div>
    </template>

    <div v-if="error" class="error">⚠ {{ error }}</div>
  </div>
</template>

<style scoped>
.wrap {
  display: flex;
  flex-direction: column;
  gap: 14px;
}

.hint {
  padding: 18px 4px;
  color: var(--text-3);
  font-size: 13px;
}

.field {
  display: flex;
  flex-direction: column;
  gap: 6px;
}

.label {
  font-size: 12.5px;
  color: var(--text-2);
}

.row2 {
  display: grid;
  grid-template-columns: 1fr 160px;
  gap: 14px;
}

.switch-field {
  display: flex;
  align-items: center;
  gap: 8px;
  font-size: 13px;
  color: var(--text-2);
}

.switch-field input {
  width: auto;
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

.paths {
  border-top: 1px solid var(--border);
  padding-top: 12px;
  display: flex;
  flex-direction: column;
  gap: 6px;
}

.sec-title {
  font-weight: 600;
  font-size: 12.5px;
  margin-bottom: 2px;
}

.path-row {
  display: flex;
  align-items: center;
  gap: 8px;
  font-size: 11.5px;
  color: var(--text-3);
}

.path-label {
  width: 92px;
  flex: none;
  color: var(--text-2);
}

.path-row code {
  flex: 1;
  word-break: break-all;
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
  flex: none;
}
</style>
