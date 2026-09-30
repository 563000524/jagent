<script setup>
import { computed, onMounted, reactive, ref } from 'vue'
import { api } from '../../api/bridge'
import { confirmDialog } from '../../utils/confirm'

const emit = defineEmits(['dirty', 'saved'])

const models = ref([])
const activeId = ref('')
/**
 * 编辑的是哪一条（本来的 id）。
 * 模型名（id）就是发给模型的模型名，界面上允许改；带上原 id，后端才知道
 * 「这是同一条记录改了名」而不是「新增了一条」。
 */
const originalId = ref('')
const modelsPath = ref('')
const presets = ref({})
const loading = ref(true)
const busy = ref(false)
const error = ref('')
const notice = ref('')

/** 编辑器：open=false 时表示不在编辑 */
const editing = ref(false)
const isNew = ref(false)
const form = reactive({
  id: '',
  name: '',
  vendor: '',
  url: '',
  apiKey: '',
  supportsToolCall: true,
  supportsImages: false,
  supportsReasoning: true
})

const presetList = computed(() =>
  Object.entries(presets.value || {}).map(([key, v]) => ({ key, ...v }))
)

async function load() {
  loading.value = true
  error.value = ''
  try {
    const r = await api.listModels()
    models.value = r.models || []
    activeId.value = r.activeId || ''
    modelsPath.value = r.modelsPath || ''
    presets.value = r.presets || {}
  } catch (e) {
    error.value = String(e?.message || e)
  } finally {
    loading.value = false
  }
}

function openNew() {
  isNew.value = true
  originalId.value = ''
  Object.assign(form, {
    id: '',
    name: '',
    vendor: '',
    url: '',
    apiKey: '',
    supportsToolCall: true,
    supportsImages: false,
    supportsReasoning: true
  })
  editing.value = true
  emit('dirty', true)
}

function openEdit(m) {
  isNew.value = false
  originalId.value = m.id
  Object.assign(form, {
    id: m.id,
    name: m.name || '',
    vendor: m.vendor || '',
    url: m.url || '',
    // 留空：后端会在 key 为空时保留已存的真 Key
    apiKey: '',
    supportsToolCall: !!m.supportsToolCall,
    supportsImages: !!m.supportsImages,
    supportsReasoning: !!m.supportsReasoning
  })
  editing.value = true
  emit('dirty', true)
}

function cancelEdit() {
  editing.value = false
  originalId.value = ''
  emit('dirty', false)
}

function applyPreset(key) {
  const p = presets.value?.[key]
  if (!p) return
  form.vendor = p.vendor
  form.url = p.url
  if (!form.id) form.id = p.model
  if (!form.name) form.name = p.model
}

async function save() {
  error.value = ''
  notice.value = ''
  if (!form.id || !form.id.trim()) {
    error.value = '模型名不能为空'
    return
  }
  if (!form.url || !form.url.trim()) {
    error.value = '接口地址不能为空'
    return
  }
  busy.value = true
  try {
    const wasNew = isNew.value
    const from = originalId.value
    const renamed = !wasNew && from && from !== form.id.trim()
    // 编辑时必须带 originalId：模型名可改，但改完仍是同一条记录
    await api.upsertModel({ ...form }, wasNew ? '' : from)
    editing.value = false
    originalId.value = ''
    emit('dirty', false)
    await load()
    emit('saved')
    notice.value = wasNew ? '模型已新增' : renamed ? '已保存（模型名已修改）' : '模型已保存'
  } catch (e) {
    error.value = String(e?.message || e)
  } finally {
    busy.value = false
  }
}

async function activate(id) {
  busy.value = true
  error.value = ''
  try {
    await api.activateModel(id)
    await load()
    emit('saved')
    notice.value = '已设为默认模型（新会话默认用它）'
  } catch (e) {
    error.value = String(e?.message || e)
  } finally {
    busy.value = false
  }
}

/** 上移 / 下移：数组顺序即优先级 */
async function move(index, delta) {
  const target = index + delta
  if (target < 0 || target >= models.value.length) return
  const ids = models.value.map((m) => m.id)
  const [moved] = ids.splice(index, 1)
  ids.splice(target, 0, moved)
  busy.value = true
  error.value = ''
  notice.value = ''
  try {
    const r = await api.reorderModels(ids)
    models.value = r.models || []
    activeId.value = r.activeId || ''
    emit('saved')
    notice.value = '优先级已调整'
  } catch (e) {
    error.value = String(e?.message || e)
  } finally {
    busy.value = false
  }
}

async function remove(m) {
  const ok = await confirmDialog(`确定删除模型「${m.name || m.id}」吗？`, {
    title: '删除模型',
    detail: m.id,
    confirmText: '删除'
  })
  if (!ok) return
  busy.value = true
  error.value = ''
  try {
    const r = await api.deleteModel(m.id)
    models.value = r.models || []
    activeId.value = r.activeId || ''
    emit('saved')
    notice.value = '已删除'
  } catch (e) {
    error.value = String(e?.message || e)
  } finally {
    busy.value = false
  }
}

async function openDir() {
  try {
    await api.openDir('models')
  } catch (e) {
    error.value = String(e?.message || e)
  }
}

onMounted(load)
</script>

<template>
  <div class="wrap">
    <div class="bar">
      <div class="path">
        配置文件：<code>{{ modelsPath }}</code>
        <button class="link" @click="openDir">打开目录</button>
      </div>
      <button class="primary" :disabled="busy || editing" @click="openNew">新增模型</button>
    </div>

    <p v-if="!loading && models.length" class="order-tip">
      顺序即优先级：标 <b>●</b> 的是<b>默认模型</b>（新会话默认用它）；它被删掉或没配 Key 时，
      按顺序取下一个可用的。对话时可以临时换模型 —— 会记在该会话上，下次进入自动恢复。
    </p>

    <div v-if="loading" class="hint">加载中…</div>
    <div v-else-if="!models.length" class="hint">
      还没有配置模型。点「新增模型」添加一条，填好接口地址与 API Key 即可开始对话。
    </div>

    <table v-else class="list">
      <thead>
        <tr>
          <th style="width: 48px">默认</th>
          <th style="width: 62px">优先级</th>
          <th>名称</th>
          <th>模型名</th>
          <th>厂商</th>
          <th style="width: 80px">Key</th>
          <th style="width: 126px"></th>
        </tr>
      </thead>
      <tbody>
        <tr v-for="(m, i) in models" :key="m.id" :class="{ on: m.active }">
          <td>
            <span v-if="m.active" class="dot" title="默认模型：新会话用它">●</span>
            <button v-else class="link" :disabled="busy" @click="activate(m.id)" title="设为默认模型">○</button>
          </td>
          <td class="order">
            <button class="link" :disabled="busy || i === 0" title="上移（优先级更高）" @click="move(i, -1)">
              ↑
            </button>
            <button
              class="link"
              :disabled="busy || i === models.length - 1"
              title="下移"
              @click="move(i, 1)"
            >
              ↓
            </button>
          </td>
          <td>
            <div class="nm">{{ m.name || m.id }}</div>
            <div class="url">{{ m.url }}</div>
          </td>
          <td class="mono">{{ m.id }}</td>
          <td>{{ m.vendor }}</td>
          <td>
            <span :class="m.hasApiKey ? 'ok' : 'bad'">{{ m.hasApiKey ? '已配置' : '未配置' }}</span>
          </td>
          <td class="ops">
            <button class="link" :disabled="busy || editing" @click="openEdit(m)">编辑</button>
            <button class="link danger" :disabled="busy" @click="remove(m)">删除</button>
          </td>
        </tr>
      </tbody>
    </table>

    <!-- 编辑器 -->
    <div v-if="editing" class="editor">
      <div class="editor-title">{{ isNew ? '新增模型' : '编辑模型' }}</div>

      <label class="field">
        <span class="label">供应商预设</span>
        <select :value="''" @change="applyPreset($event.target.value)">
          <option value="">（选择后自动填入地址与模型名）</option>
          <option v-for="p in presetList" :key="p.key" :value="p.key">
            {{ p.vendor }} — {{ p.model }}
          </option>
        </select>
      </label>

      <div class="grid">
        <label class="field">
          <span class="label">模型名（同时作为接口的 model 参数）</span>
          <input v-model="form.id" placeholder="deepseek-ai/DeepSeek-V4-Flash" />
        </label>
        <label class="field">
          <span class="label">显示名称</span>
          <input v-model="form.name" placeholder="留空则用模型名" />
        </label>
      </div>

      <label class="field">
        <span class="label">接口地址（完整路径）</span>
        <input v-model="form.url" placeholder="https://api.siliconflow.cn/v1/chat/completions" />
        <span class="tip">填到 /chat/completions；只填到 /v1 也可以，程序会补全默认路径</span>
      </label>

      <div class="grid">
        <label class="field">
          <span class="label">厂商</span>
          <input v-model="form.vendor" placeholder="Custom" />
        </label>
        <label class="field">
          <span class="label">API Key</span>
          <input
            v-model="form.apiKey"
            type="password"
            :placeholder="isNew ? 'sk-...' : '留空则不修改已保存的 Key'"
          />
        </label>
      </div>

      <div class="switches">
        <label><input v-model="form.supportsToolCall" type="checkbox" /> 支持工具调用</label>
        <label><input v-model="form.supportsImages" type="checkbox" /> 支持图片输入</label>
        <label><input v-model="form.supportsReasoning" type="checkbox" /> 支持推理过程</label>
      </div>

      <div class="editor-foot">
        <button @click="cancelEdit">取消</button>
        <button class="primary" :disabled="busy" @click="save">{{ busy ? '保存中…' : '保存' }}</button>
      </div>
    </div>

    <div v-if="notice" class="notice">{{ notice }}</div>
    <div v-if="error" class="error">⚠ {{ error }}</div>

    <p class="foot-tip">
      模型按「一条记录 = 一个可用模型」维护，保存后写入 <code>models.json</code>，
      与 WorkBuddy 的同名文件结构一致，可以互相替换。列表顺序即优先级，会按顺序写进文件。
    </p>
  </div>
</template>

<style scoped>
.wrap {
  display: flex;
  flex-direction: column;
  gap: 12px;
}

.bar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
}

.path {
  font-size: 11.5px;
  color: var(--text-3);
  word-break: break-all;
}

.path code,
.foot-tip code {
  background: var(--panel-2);
  padding: 1px 4px;
  border-radius: 3px;
}

.hint {
  padding: 18px 4px;
  color: var(--text-3);
  font-size: 13px;
}

.order-tip {
  margin: 0;
  font-size: 11.5px;
  line-height: 1.75;
  color: var(--text-3);
  background: var(--panel-2);
  border: 1px solid var(--border);
  border-radius: 8px;
  padding: 9px 12px;
}

.order-tip b {
  color: var(--text-2);
}

.order {
  white-space: nowrap;
}

.order .link {
  padding: 2px 3px;
}

.list {
  width: 100%;
  border-collapse: collapse;
  font-size: 12.5px;
}

.list th {
  text-align: left;
  font-weight: 500;
  color: var(--text-3);
  padding: 6px 8px;
  border-bottom: 1px solid var(--border);
}

.list td {
  padding: 8px;
  border-bottom: 1px solid var(--border);
  vertical-align: middle;
}

.list tr.on {
  background: rgba(64, 128, 255, 0.06);
}

.nm {
  font-weight: 600;
}

.url {
  font-size: 11px;
  color: var(--text-3);
  word-break: break-all;
}

.mono {
  font-family: ui-monospace, Consolas, monospace;
  font-size: 11.5px;
}

.dot {
  color: var(--primary);
}

.ok {
  color: var(--text-2);
}

.bad {
  color: var(--danger);
}

.ops {
  text-align: right;
  white-space: nowrap;
}

.link {
  border: none;
  background: none;
  color: var(--primary);
  cursor: pointer;
  font-size: 12px;
  padding: 2px 4px;
}

.link.danger {
  color: var(--danger);
}

.link:disabled {
  opacity: 0.45;
  cursor: default;
}

.editor {
  border: 1px solid var(--border);
  border-radius: 10px;
  padding: 14px;
  background: var(--panel-2);
  display: flex;
  flex-direction: column;
  gap: 12px;
}

.editor-title {
  font-weight: 600;
  font-size: 13px;
}

.grid {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 12px;
}

.field {
  display: flex;
  flex-direction: column;
  gap: 5px;
}

.label {
  font-size: 12px;
  color: var(--text-2);
}

.tip {
  font-size: 11px;
  color: var(--text-3);
}

.switches {
  display: flex;
  flex-wrap: wrap;
  gap: 16px;
  font-size: 12.5px;
  color: var(--text-2);
}

.switches input {
  width: auto;
}

.editor-foot {
  display: flex;
  justify-content: flex-end;
  gap: 8px;
}

.notice {
  font-size: 12.5px;
  color: var(--primary);
}

.error {
  font-size: 12.5px;
  color: var(--danger);
}

.foot-tip {
  font-size: 11.5px;
  color: var(--text-3);
  line-height: 1.6;
  margin: 0;
}
</style>
