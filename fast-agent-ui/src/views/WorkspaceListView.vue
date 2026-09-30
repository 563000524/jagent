<script setup>
import { ref, onMounted } from 'vue'
import { api, logFrontend } from '../api/bridge'
import { confirmDialog } from '../utils/confirm'

const props = defineProps({
  user: { type: Object, default: null }
})
const emit = defineEmits(['open', 'logout', 'config'])

const list = ref([])
const loading = ref(false)
const error = ref('')
const showCreate = ref(false)
const creating = ref(false)
const picking = ref(false)
const form = ref({ name: '', directory: '', description: '' })

/**
 * 弹系统文件夹选择器，把选中的路径填进「工作目录」。
 *
 * 后端会让 JavaFX 侧弹原生对话框，所以这个请求会一直挂着直到用户选完或取消 ——
 * 期间按钮置灰，避免连点开出多个框。
 */
async function pickDirectory() {
  picking.value = true
  error.value = ''
  try {
    const r = await api.pickDirectory(form.value.directory.trim())
    if (r && r.dir) form.value.directory = r.dir
  } catch (e) {
    // 非桌面模式等情况下退回手填，提示但不打断
    error.value = String(e?.message || e)
  } finally {
    picking.value = false
  }
}

/** 打开新建弹窗：清掉页面上的旧错误，否则弹窗里会显示上一次的加载失败 */
function openCreate() {
  error.value = ''
  showCreate.value = true
}

async function refresh() {
  loading.value = true
  error.value = ''
  try {
    list.value = (await api.listWorkspaces()) || []
  } catch (e) {
    error.value = String(e?.message || e)
    logFrontend('error', `加载工作空间失败: ${error.value}`)
  } finally {
    loading.value = false
  }
}

async function create() {
  const name = form.value.name.trim()
  const directory = form.value.directory.trim()
  if (!name) {
    error.value = '请填写工作空间名称'
    return
  }
  if (!directory) {
    error.value = '请填写工作目录（绝对路径）'
    return
  }
  creating.value = true
  error.value = ''
  try {
    const ws = await api.createWorkspace(name, directory, form.value.description.trim())
    showCreate.value = false
    form.value = { name: '', directory: '', description: '' }
    await refresh()
    emit('open', ws)
  } catch (e) {
    error.value = String(e?.message || e)
  } finally {
    creating.value = false
  }
}

async function remove(ws) {
  const ok = await confirmDialog(`从列表移除「${ws.name}」？`, {
    title: '移除工作空间',
    detail: `只摘掉登记，磁盘上的内容不会动：\n${ws.dataDir}\n（要连数据一起删，请自己到该目录下删掉 .workspace）`,
    confirmText: '移除'
  })
  if (!ok) return
  try {
    await api.deleteWorkspace(ws.id, false)
    await refresh()
  } catch (e) {
    error.value = String(e?.message || e)
  }
}

function fmt(ts) {
  if (!ts) return ''
  return String(ts).replace('T', ' ').slice(0, 16)
}

onMounted(refresh)
</script>

<template>
  <div class="ws-page">
    <header class="ws-topbar">
      <div class="ws-topbar-left">
        <span class="logo">AI</span>
        <span class="topbar-title">办公智能体</span>
      </div>
      <div class="ws-topbar-right">
        <span class="user-tag">{{ user?.userId || '未登录' }}</span>
        <button class="ghost" @click="emit('config')">配置中心</button>
        <button class="ghost" @click="emit('logout')">退出登录</button>
      </div>
    </header>

    <main class="ws-main">
      <div class="ws-head">
        <div>
          <h2 class="ws-title">工作空间</h2>
          <p class="ws-sub">
            工作空间建在你指定的目录下（<code>.workspace</code>），各自拥有项目记忆与会话记录
          </p>
        </div>
        <button class="primary" @click="openCreate">+ 新建工作空间</button>
      </div>

      <div v-if="error" class="banner error">⚠ {{ error }}</div>

      <div v-if="loading" class="hint">加载中…</div>
      <div v-else-if="!list.length" class="empty">
        <p>还没有工作空间</p>
        <p class="empty-sub">
          新建时指定一个目录，agent 会在该目录下建 <code>.workspace</code> 存放记忆与会话记录
        </p>
      </div>

      <div v-else class="ws-grid">
        <div v-for="ws in list" :key="ws.id" class="ws-card" @click="emit('open', ws)">
          <div class="ws-card-head">
            <span class="ws-name">{{ ws.name }}</span>
            <button class="ghost del" title="移除" @click.stop="remove(ws)">×</button>
          </div>
          <p class="ws-desc">{{ ws.description || '（无描述）' }}</p>
          <p class="ws-dir" :title="ws.dataDir">
            <span v-if="!ws.directoryExists" class="warn">⚠ 目录不存在：</span>
            {{ ws.directory }}
          </p>
          <div class="ws-meta">
            <span class="ws-id">{{ ws.id }}</span>
            <span>{{ fmt(ws.updatedAt) }}</span>
          </div>
        </div>
      </div>
    </main>

    <div v-if="showCreate" class="mask" @click.self="showCreate = false">
      <div class="dialog">
        <header class="dialog-head">
          <span>新建工作空间</span>
          <button class="ghost" @click="showCreate = false">×</button>
        </header>
        <div class="dialog-body">
          <!-- 弹窗会盖住页面顶部的 banner，错误要在弹窗里也显示一份 -->
          <div v-if="error" class="banner error">⚠ {{ error }}</div>
          <label class="field">
            <span class="label">名称</span>
            <input v-model="form.name" placeholder="例如：星图工单系统" @keydown.enter="create" />
          </label>
          <label class="field">
            <span class="label">工作目录（绝对路径）</span>
            <div class="dir-row">
              <input
                v-model="form.directory"
                placeholder="D:\projects\my-project"
                @keydown.enter="create"
              />
              <button type="button" class="ghost pick" :disabled="picking" @click="pickDirectory">
                {{ picking ? '选择中…' : '浏览…' }}
              </button>
            </div>
            <span class="tip">
              agent 的数据会放在该目录下的 <code>.jagentspace</code> 里，不污染你自己的文件；
              目录不存在会自动创建
            </span>
          </label>
          <label class="field">
            <span class="label">描述（会写进 AGENTS.md 作为项目背景）</span>
            <textarea
              v-model="form.description"
              rows="4"
              placeholder="这个项目是做什么的、有哪些约定、常用术语…"
            ></textarea>
          </label>
        </div>
        <footer class="dialog-foot">
          <button @click="showCreate = false">取消</button>
          <button class="primary" :disabled="creating" @click="create">
            {{ creating ? '创建中…' : '创建' }}
          </button>
        </footer>
      </div>
    </div>
  </div>
</template>

<style scoped>
.ws-page {
  height: 100%;
  display: flex;
  flex-direction: column;
  background: var(--bg);
}

.ws-topbar {
  height: 52px;
  flex: 0 0 52px;
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 0 24px;
  background: var(--panel);
  border-bottom: 1px solid var(--border);
}

.ws-topbar-left {
  display: flex;
  align-items: center;
  gap: 8px;
}

.ws-topbar-right {
  display: flex;
  align-items: center;
  gap: 10px;
}

.user-tag {
  font-size: 12.5px;
  color: var(--text-2);
  background: var(--panel-2);
  border: 1px solid var(--border);
  border-radius: 999px;
  padding: 3px 10px;
}

.ws-main {
  flex: 1;
  overflow-y: auto;
  padding: 28px 32px 48px;
}

.ws-head {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  margin-bottom: 22px;
}

.ws-title {
  margin: 0 0 6px;
  font-size: 19px;
  font-weight: 600;
}

.ws-sub {
  margin: 0;
  font-size: 13px;
  color: var(--text-2);
}

.ws-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(280px, 1fr));
  gap: 14px;
}

.ws-card {
  background: var(--panel);
  border: 1px solid var(--border);
  border-radius: 12px;
  padding: 16px;
  cursor: pointer;
  transition: all 0.15s;
}

.ws-card:hover {
  border-color: #bcd0ff;
  box-shadow: var(--shadow);
}

.ws-card-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
}

.ws-name {
  font-size: 14.5px;
  font-weight: 600;
}

.del {
  opacity: 0;
  font-size: 17px;
  line-height: 1;
  padding: 0 6px;
}

.ws-card:hover .del {
  opacity: 1;
}

.ws-desc {
  margin: 8px 0 12px;
  font-size: 12.5px;
  color: var(--text-2);
  line-height: 1.6;
  min-height: 36px;
  display: -webkit-box;
  -webkit-line-clamp: 2;
  -webkit-box-orient: vertical;
  overflow: hidden;
}

.ws-meta {
  display: flex;
  align-items: center;
  justify-content: space-between;
  font-size: 11.5px;
  color: var(--text-3);
}

.ws-dir {
  margin: 0 0 10px;
  font-size: 11px;
  font-family: Consolas, monospace;
  color: var(--text-3);
  word-break: break-all;
  display: -webkit-box;
  -webkit-line-clamp: 2;
  -webkit-box-orient: vertical;
  overflow: hidden;
}

.warn {
  color: var(--danger);
}

.tip {
  font-size: 11.5px;
  color: var(--text-3);
  line-height: 1.6;
}

.tip code,
.empty-sub code {
  background: var(--panel-2);
  padding: 1px 4px;
  border-radius: 3px;
}

.ws-id {
  font-family: Consolas, monospace;
}

.empty {
  text-align: center;
  padding: 70px 0;
  color: var(--text-2);
}

.empty-sub {
  font-size: 12.5px;
  color: var(--text-3);
}

.hint {
  color: var(--text-3);
  font-size: 13px;
}

.banner {
  padding: 9px 14px;
  border-radius: 8px;
  font-size: 12.5px;
  margin-bottom: 14px;
}

.banner.error {
  color: var(--danger);
  background: #fef1f1;
  border: 1px solid #f8dcdc;
}

.mask {
  position: fixed;
  inset: 0;
  background: rgba(24, 30, 45, 0.35);
  display: flex;
  align-items: center;
  justify-content: center;
  z-index: 50;
}

.dialog {
  width: 480px;
  background: var(--panel);
  border-radius: 14px;
  box-shadow: 0 18px 50px rgba(20, 30, 60, 0.22);
  overflow: hidden;
}

.dialog-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 14px 18px;
  border-bottom: 1px solid var(--border);
  font-weight: 600;
}

.dialog-body {
  padding: 16px 18px;
  display: flex;
  flex-direction: column;
  gap: 14px;
}

.dialog-foot {
  display: flex;
  justify-content: flex-end;
  gap: 10px;
  padding: 12px 18px;
  border-top: 1px solid var(--border);
  background: var(--panel-2);
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

/* 「工作目录」输入框 + 浏览按钮同一行；按钮不参与伸缩，输入框占满剩余宽度 */
.dir-row {
  display: flex;
  gap: 8px;
  align-items: stretch;
}

.dir-row input {
  flex: 1;
  min-width: 0;
}

.pick {
  flex: none;
  white-space: nowrap;
}
</style>
