<script setup>
import { computed, onMounted, onUnmounted, reactive, ref } from 'vue'
import { api, logFrontend, onEvent } from '../api/bridge'
import { confirmDialog } from '../utils/confirm'
import ChatPanel from '../components/ChatPanel.vue'
import FilePreviewDrawer from '../components/FilePreviewDrawer.vue'

const props = defineProps({
  workspace: { type: Object, required: true }
})
const emit = defineEmits(['back', 'logout', 'config'])

const sessions = ref([])
const currentId = ref('')
const streams = reactive({}) // sessionId -> { content, reasoning, running, error, seq }
const stopping = ref(false)
const toast = ref('')
/** 可选的模型列表（来自模型配置） */
const models = ref([])

const showRecords = ref(false)
const records = ref(null)
const loadingRecords = ref(false)
/** 右侧预览中的文件（来自卡片点击）；null = 收起 */
const previewFile = ref(null)

let offs = []
let pollTimer = null
let toastTimer = null
/** 本组件是否已卸载：onMounted 里有 await，订阅要等 await 之后才注册，见 onMounted 末尾 */
let unmounted = false

/**
 * 会产出文件卡片的工具：交付成品 + 文件类操作。
 * 其他工具（搜索、命令、记忆…）不影响文件列表，没必要每次都拉一遍接口。
 */
const FILE_TOOLS = new Set(['deliver_artifact', 'read_file', 'write_file', 'edit_file'])

const currentSession = computed(
  () => sessions.value.find((s) => s.sessionId === currentId.value) || null
)
const currentStream = computed(() => streams[currentId.value] || null)

/**
 * 当前会话生效的模型。
 *
 * <p>优先用会话自己记录的那个（上次在这个会话里选的），没有就用默认模型 ——
 * 所以「新建会话」自动落到默认模型，「回到老会话」自动恢复它上次用的。
 * 会话记的模型被删掉时也回落默认，不会因为删配置把会话卡死。
 */
const currentModelId = computed(() => {
  const list = models.value
  if (!list.length) {
    return ''
  }
  const saved = currentSession.value?.modelId
  if (saved && list.some((m) => m.id === saved)) {
    return saved
  }
  return (list.find((m) => m.active) || list[0]).id
})

async function loadModels() {
  try {
    const r = await api.listModels()
    models.value = r.models || []
  } catch (e) {
    logFrontend('warn', `加载模型列表失败: ${e?.message || e}`)
  }
}

/** 切换当前会话使用的模型，记到会话索引上，下次进入自动恢复 */
async function pickModel(id) {
  const sid = currentId.value
  if (!sid || !id) return
  try {
    const info = await api.setSessionModel(props.workspace.id, sid, id)
    const s = sessions.value.find((x) => x.sessionId === sid)
    if (s) s.modelId = info.modelId
    notify('已切换模型，这个会话下次进入仍用它')
  } catch (e) {
    notify(String(e?.message || e))
  }
}

function notify(msg) {
  toast.value = msg
  clearTimeout(toastTimer)
  toastTimer = setTimeout(() => (toast.value = ''), 3000)
}

/** 打开该工作空间的数据目录（<用户目录>/.workspace） */
async function openWorkspaceDir() {
  try {
    await api.openWorkspaceDir(props.workspace.id)
  } catch (e) {
    notify(String(e?.message || e))
  }
}

function ensureStream(id) {
  if (!streams[id]) {
    streams[id] = {
      content: '',
      reasoning: '',
      running: false,
      error: '',
      seq: 0,
      durationMs: 0,
      inputTokens: 0,
      outputTokens: 0,
      totalTokens: 0,
      /** 本轮开始时间，AG-UI 不下发耗时，由前端自己算 */
      startedAt: 0,
      /**
       * 工具调用过程（AG-UI 的 TOOL_CALL_* 事件）。
       * 元素：{ id, name, args, status: running|awaiting|done, result }
       */
      tools: [],
      /**
       * 本轮涉及的文件（模型调 deliver_artifact 交付的 + 被读/写/改过的）。
       * 由 refreshFiles() 拉取，只留本轮产生的那些。
       */
      files: []
    }
  }
  return streams[id]
}

/** 开始新一轮前清空该会话的流式状态 */
function resetStream(id) {
  // 上一轮可能还有没落库的增量，先丢弃，免得被写进新一轮
  clearDelta(id)
  const st = ensureStream(id)
  st.content = ''
  st.reasoning = ''
  st.error = ''
  st.running = true
  st.seq += 1
  st.durationMs = 0
  st.inputTokens = 0
  st.outputTokens = 0
  st.totalTokens = 0
  st.startedAt = Date.now()
  st.tools = []
  st.files = []
  return st
}

/**
 * 拉一次本会话涉及的文件（交付物 + 读/写/改过的），把**本轮**新产生的挑出来放进流式状态。
 *
 * 为什么按 startedAt 过滤：接口返回的是整个会话的（历史卡片由 ChatPanel 在 load() 里
 * 按时间挂到各自回答下），而这里只负责「正在生成/刚生成完的这一轮」，
 * 全量塞进来会让当前回答下面出现一堆往轮次的文件。
 */
async function refreshFiles(sessionId) {
  const st = streams[sessionId]
  if (!st) return
  try {
    const list = (await api.listFiles(props.workspace.id, sessionId)) || []
    const since = st.startedAt || 0
    st.files = since ? list.filter((f) => (f.createdAt || 0) >= since) : list
  } catch (e) {
    logFrontend('warn', `加载文件列表失败: ${e?.message || e}`)
  }
}

/** 点卡片 → 右侧滑出预览；再点同一张则收起 */
function openPreview(file) {
  previewFile.value = previewFile.value?.id === file.id ? null : file
}

/** 按 toolCallId 找本轮的工具条目（同一条 run 内 id 唯一） */
function findTool(threadId, toolCallId) {
  const st = streams[threadId]
  if (!st || !st.tools) return null
  return st.tools.find((t) => t.id === toolCallId) || null
}

// ---------- 高频增量的批量落库 ----------

/**
 * 回答正文 / 思考过程 / 工具参数都是**逐 token** 推来的。若每个 delta 都直接写响应式
 * 状态，就会触发等量的组件渲染周期（每个周期都要重跑整页 vnode 与 diff）—— 实测能把
 * 主线程占满，表现就是「生成时页面点不动」。
 *
 * 这里把增量先攒进缓冲，最多每 16ms 落到响应式状态一次：渲染次数降一到两个
 * 数量级，视觉上仍是连续出字。所有收尾路径（RUN_FINISHED / 停止 / 新一轮 / 卸载）都必须
 * 先 flush，否则会丢字。
 */
const FLUSH_MS = 16
const deltaBuf = new Map() // threadId -> { content, reasoning, args: Map<toolCallId, string>, timer }

function bufFor(threadId) {
  let b = deltaBuf.get(threadId)
  if (!b) {
    b = { content: '', reasoning: '', args: new Map(), timer: 0 }
    deltaBuf.set(threadId, b)
  }
  return b
}

/** 把缓冲写进响应式状态；幂等，可被收尾路径强制调用 */
function flushDelta(threadId) {
  const b = deltaBuf.get(threadId)
  if (!b) return
  if (b.timer) {
    clearTimeout(b.timer)
    b.timer = 0
  }
  if (b.content) {
    ensureStream(threadId).content += b.content
    b.content = ''
  }
  if (b.reasoning) {
    ensureStream(threadId).reasoning += b.reasoning
    b.reasoning = ''
  }
  if (b.args.size) {
    b.args.forEach((txt, id) => {
      const t = findTool(threadId, id)
      if (t) t.args += txt
    })
    b.args.clear()
  }
}

function scheduleFlush(threadId, b) {
  if (!b.timer) b.timer = setTimeout(() => flushDelta(threadId), FLUSH_MS)
}

/** 丢弃某会话的未落库增量（删除会话 / 开始新一轮时调用，避免写入已作废的状态） */
function clearDelta(threadId) {
  const b = deltaBuf.get(threadId)
  if (!b) return
  if (b.timer) clearTimeout(b.timer)
  deltaBuf.delete(threadId)
}

// ---------- 轮询兜底 ----------
function stopPolling() {
  if (pollTimer) {
    clearInterval(pollTimer)
    pollTimer = null
  }
}

function startPolling(sessionId) {
  stopPolling()
  // 2 秒一次：这只是 AG-UI 事件通道失效时的兜底。原来 250ms 会在整轮生成期间
  // 持续拉取整段回答（每秒 4 次 × 几十 KB），白白占带宽和主线程。
  pollTimer = setInterval(async () => {
    try {
      const snap = await api.getStreamSnapshot(props.workspace.id, sessionId)
      const st = streams[sessionId]
      if (!st) return
      if (snap.content && snap.content.length > st.content.length) st.content = snap.content
      if (snap.reasoning && snap.reasoning.length > st.reasoning.length) st.reasoning = snap.reasoning
      if (snap.error && !st.error) st.error = snap.error
      // 事件通道失效时靠快照补齐耗时与 token（后端两处都存了）
      if (snap.durationMs) st.durationMs = snap.durationMs
      if (snap.inputTokens) st.inputTokens = snap.inputTokens
      if (snap.outputTokens) st.outputTokens = snap.outputTokens
      if (snap.totalTokens) st.totalTokens = snap.totalTokens
      if (!snap.running) {
        const wasRunning = st.running
        st.running = false
        stopPolling()
        if (wasRunning) st.seq += 1
      }
    } catch (e) {
      logFrontend('warn', `轮询快照失败: ${e?.message || e}`)
    }
  }, 2000)
}

// ---------- 会话 ----------
async function refreshSessions() {
  sessions.value = (await api.listSessions(props.workspace.id)) || []
}

async function openSession(sid) {
  if (sid === currentId.value) return
  // 预览窗口是会话内的东西，换会话就收起来，免得看着上一个会话的文件
  previewFile.value = null
  currentId.value = sid
}

async function newSession() {
  const s = await api.createSession(props.workspace.id)
  await refreshSessions()
  currentId.value = s.sessionId
}

async function removeSession(sid) {
  const ok = await confirmDialog('删除这个会话？对话记录会被清空。', {
    title: '删除会话',
    confirmText: '删除'
  })
  if (!ok) return
  await api.deleteSession(props.workspace.id, sid)
  // 该会话的未落库增量一并丢弃，否则定时器会把状态又建回来
  clearDelta(sid)
  delete streams[sid]
  await refreshSessions()
  if (currentId.value === sid) {
    currentId.value = sessions.value[0]?.sessionId || ''
    if (!currentId.value) await newSession()
  }
}

// ---------- 发送 ----------
async function send(text) {
  const id = currentId.value
  if (!id) {
    notify('请先新建会话')
    return
  }
  const st = resetStream(id)
  stopping.value = false
  stopPolling()

  logFrontend('info', `发送(AG-UI): ws=${props.workspace.id} session=${id} 长度=${text.length}`)

  try {
    // 带上当前选择的模型：切换后立即生效，同时后端会把它记到该会话上
    const realId = await api.sendAguiRun(props.workspace.id, id, text, currentModelId.value)
    if (realId && realId !== id) {
      const carry = streams[id]
      streams[realId] = carry
      delete streams[id]
      currentId.value = realId
    }
    startPolling(currentId.value)
    await refreshSessions()
  } catch (e) {
    st.running = false
    st.error = String(e?.message || e)
    logFrontend('error', `sendAguiRun 失败: ${st.error}`)
    if (st.error.includes('模型')) notify('请先到「配置中心 → 模型配置」添加模型并填写 API Key')
  }
}

async function stop() {
  stopping.value = true
  try {
    await api.stopStream(props.workspace.id, currentId.value)
  } finally {
    // 中断时刻可能还有没落库的增量，收尾前先补齐
    flushDelta(currentId.value)
    stopping.value = false
    stopPolling()
  }
}

// ---------- 工作空间级记录 ----------
async function loadRecords() {
  loadingRecords.value = true
  try {
    records.value = await api.workspaceRecords(props.workspace.id)
  } catch (e) {
    notify(String(e?.message || e))
  } finally {
    loadingRecords.value = false
  }
}

function toggleRecords() {
  showRecords.value = !showRecords.value
  if (showRecords.value && !records.value) loadRecords()
}

onMounted(async () => {
  try {
    // 模型列表先加载：会话的模型选择依赖它才知道默认是哪个
    await loadModels()
    await refreshSessions()
    if (!sessions.value.length) await newSession()
    else currentId.value = sessions.value[0].sessionId
  } catch (e) {
    logFrontend('error', `加载会话失败: ${e?.message || e}`)
  }

  // AG-UI 事件流：事件名就是协议里的 type（见 api/bridge.js 的 sendAguiRun）。
  // 老的自定义协议（chat:delta / chat:done / chat:error）后端仍保留，前端不再使用。
  offs = [
    onEvent('agui:RUN_STARTED', (p) => {
      const st = ensureStream(p.threadId)
      st.running = true
      st.error = ''
    }),
    onEvent('agui:TEXT_MESSAGE_CONTENT', (p) => {
      // 状态变更走缓冲，不要在事件回调里直接改响应式状态（见「高频增量的批量落库」）
      const st = ensureStream(p.threadId)
      if (!st.running) st.running = true
      const b = bufFor(p.threadId)
      b.content += p.delta || ''
      scheduleFlush(p.threadId, b)
    }),
    onEvent('agui:REASONING_MESSAGE_CONTENT', (p) => {
      const st = ensureStream(p.threadId)
      if (!st.running) st.running = true
      const b = bufFor(p.threadId)
      b.reasoning += p.delta || ''
      scheduleFlush(p.threadId, b)
    }),
    onEvent('agui:TOOL_CALL_START', (p) => {
      // 工具相关事件都低频：先落库再处理，保证与增量之间的顺序
      flushDelta(p.threadId)
      const st = ensureStream(p.threadId)
      st.tools.push({
        id: p.toolCallId,
        name: p.toolCallName,
        args: '',
        status: 'running',
        result: ''
      })
    }),
    onEvent('agui:TOOL_CALL_ARGS', (p) => {
      if (!p.delta) return
      // 参数也是逐字推的，同样走缓冲
      const b = bufFor(p.threadId)
      b.args.set(p.toolCallId, (b.args.get(p.toolCallId) || '') + p.delta)
      scheduleFlush(p.threadId, b)
    }),
    onEvent('agui:TOOL_CALL_END', (p) => {
      flushDelta(p.threadId)
      const t = findTool(p.threadId, p.toolCallId)
      // 参数流结束，进入「等待结果」态
      if (t && t.status === 'running') t.status = 'awaiting'
    }),
    onEvent('agui:TOOL_CALL_RESULT', (p) => {
      flushDelta(p.threadId)
      const t = findTool(p.threadId, p.toolCallId)
      if (t) {
        t.status = 'done'
        t.result = p.content || ''
        // 文件类工具跑完 => 后端已经把「涉及的文件」记下来了（交付工具则已落盘完成），
        // 拉一次列表把卡片显示出来。数据以列表接口为准，不解析事件里的文本。
        if (FILE_TOOLS.has(t.name)) refreshFiles(p.threadId)
      }
    }),
    onEvent('agui:CUSTOM', (p) => {
      // 只关心 token 用量，其余自定义事件忽略
      if (p.name !== 'token_usage') return
      const st = ensureStream(p.threadId)
      const cum = (p.value && p.value.cumulative) || {}
      if (cum.inputTokens != null) st.inputTokens = cum.inputTokens
      if (cum.outputTokens != null) st.outputTokens = cum.outputTokens
      if (cum.totalTokens != null) st.totalTokens = cum.totalTokens
    }),
    onEvent('agui:RUN_FINISHED', (p) => {
      // 先落库：最后几个 token 可能还躺在缓冲里，不 flush 会少字
      flushDelta(p.threadId)
      const st = ensureStream(p.threadId)
      st.running = false
      st.tools.forEach((t) => {
        if (t.status !== 'done') t.status = 'done'
      })
      // AG-UI 不下发耗时，前端从发起算到收尾
      if (st.startedAt) st.durationMs = Date.now() - st.startedAt
      st.seq += 1
      // 收尾再拉一次：最后一步交付可能没走到 TOOL_CALL_RESULT（例如被中断），
      // 而且此刻也顺手把这轮的文件与历史对齐
      refreshFiles(p.threadId)
      refreshSessions()
      if (showRecords.value) loadRecords()
    }),
    onEvent('agui:RUN_ERROR', (p) => {
      if (!p.threadId) return
      flushDelta(p.threadId)
      const st = ensureStream(p.threadId)
      st.running = false
      st.error = p.message || '调用失败'
      st.seq += 1
      logFrontend('error', `生成失败: ${st.error}`)
    })
  ]

  // 上面有 await：若组件在这期间已被卸载，onUnmounted 跑的时候 offs 还是空的，
  // 这批订阅就会永久留在事件总线上（下次再进工作空间又多一套，事件被重复消费）。
  // 这里补一次退订。
  if (unmounted) {
    offs.forEach((off) => typeof off === 'function' && off())
    offs = []
  }
})

onUnmounted(() => {
  unmounted = true
  offs.forEach((off) => typeof off === 'function' && off())
  stopPolling()
  clearTimeout(toastTimer)
  // 缓冲区里的定时器必须清掉，否则组件卸载后仍会往已废弃的 streams 上写
  deltaBuf.forEach((b) => {
    if (b.timer) clearTimeout(b.timer)
  })
  deltaBuf.clear()
})
</script>

<template>
  <div class="app">
    <aside class="sidebar">
      <div class="ws-brand">
        <button class="ghost back" title="返回工作空间列表" @click="emit('back')">‹</button>
        <span class="ws-brand-name">{{ workspace.name }}</span>
      </div>

      <button class="primary new-session" @click="newSession">＋ 新建会话</button>

      <div class="session-list">
        <div
          v-for="s in sessions"
          :key="s.sessionId"
          class="session-item"
          :class="{ active: s.sessionId === currentId }"
          @click="openSession(s.sessionId)"
        >
          <span class="title">{{ s.title || '新会话' }}</span>
          <span v-if="streams[s.sessionId]?.running" class="dot" title="生成中"></span>
          <button class="del ghost" title="删除" @click.stop="removeSession(s.sessionId)">×</button>
        </div>
        <div v-if="!sessions.length" class="empty-tip">暂无会话</div>
      </div>

      <div class="sidebar-foot">
        <button class="ghost full" :class="{ on: showRecords }" @click="toggleRecords">
          📒 工作空间记录
        </button>
        <button class="ghost full" @click="openWorkspaceDir">📂 打开工作目录</button>
        <button class="ghost full" @click="emit('config')">⚙ 配置中心</button>
        <button class="ghost full" @click="api.openLogDir()">📄 打开日志</button>
        <button class="ghost full" @click="emit('logout')">退出登录</button>
      </div>
    </aside>

    <main class="main">
      <header class="topbar">
        <div class="topbar-title">{{ currentSession?.title || '新会话' }}</div>
        <div class="topbar-meta">
          <span class="ws-tag">{{ workspace.name }}</span>
        </div>
      </header>

      <div class="body-row">
        <div class="chat-col">
          <div v-if="currentStream?.running" class="status running">
            <span class="spinner"></span> 正在生成…
          </div>
          <div v-else-if="currentStream?.error" class="status error">
            <span>⚠ {{ currentStream.error }}</span>
            <button class="ghost link" @click="api.openLogDir()">查看日志</button>
          </div>

          <ChatPanel
            :workspace-id="workspace.id"
            :session-id="currentId"
            :stream="currentStream"
            :stopping="stopping"
            :models="models"
            :model-id="currentModelId"
            :preview-file-id="previewFile?.id || ''"
            @send="send"
            @stop="stop"
            @pick-model="pickModel"
            @notify="notify"
            @preview="openPreview"
          />
        </div>

        <!-- 右侧预览：并排的独立一列（不是浮层），滑出时聊天区让位，不遮任何东西 -->
        <FilePreviewDrawer :file="previewFile" @close="previewFile = null" @notify="notify" />
      </div>
    </main>

    <aside v-if="showRecords" class="records">
      <header class="records-head">
        <span>工作空间记录</span>
        <button class="ghost" @click="showRecords = false">×</button>
      </header>
      <div v-if="loadingRecords" class="records-loading">加载中…</div>
      <div v-else-if="!records" class="records-loading">暂无内容</div>
      <div v-else class="records-body">
        <p class="records-path">{{ records.root }}</p>

        <section class="records-sec">
          <h4>MEMORY.md <span class="tag">工作空间级记忆</span></h4>
          <pre>{{ records.memory || '（还没有积累长期记忆）' }}</pre>
        </section>

        <section class="records-sec">
          <h4>AGENTS.md <span class="tag">项目约定</span></h4>
          <pre>{{ records.agents || '（无）' }}</pre>
        </section>

        <section class="records-sec">
          <h4>每日记录 <span class="tag">memory/</span></h4>
          <template v-if="records.daily && records.daily.length">
            <details v-for="d in records.daily" :key="d.date" class="daily">
              <summary>{{ d.date }}</summary>
              <pre>{{ d.content }}</pre>
            </details>
          </template>
          <p v-else class="records-empty">（暂无按日记录）</p>
        </section>
      </div>
    </aside>

    <transition name="fade">
      <div v-if="toast" class="toast">{{ toast }}</div>
    </transition>
  </div>
</template>

<style scoped>
.app {
  display: flex;
  height: 100%;
}

.sidebar {
  width: 240px;
  flex: 0 0 240px;
  display: flex;
  flex-direction: column;
  background: var(--panel);
  border-right: 1px solid var(--border);
  padding: 14px 12px;
  gap: 12px;
}

.ws-brand {
  display: flex;
  align-items: center;
  gap: 6px;
  padding: 2px 0 6px;
}

.back {
  font-size: 20px;
  line-height: 1;
  padding: 0 8px;
}

.ws-brand-name {
  font-size: 15px;
  font-weight: 600;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.new-session {
  width: 100%;
}

.session-list {
  flex: 1;
  overflow-y: auto;
  display: flex;
  flex-direction: column;
  gap: 2px;
}

.session-item {
  display: flex;
  align-items: center;
  gap: 6px;
  padding: 8px 8px 8px 10px;
  border-radius: 8px;
  cursor: pointer;
  color: var(--text-2);
}

.session-item:hover {
  background: var(--panel-2);
}

.session-item.active {
  background: var(--primary-soft);
  color: var(--primary);
  font-weight: 600;
}

.session-item .title {
  flex: 1;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  font-size: 13px;
}

.session-item .del {
  opacity: 0;
  padding: 0 6px;
  font-size: 16px;
  line-height: 1;
}

.session-item:hover .del {
  opacity: 1;
}

.dot {
  width: 7px;
  height: 7px;
  border-radius: 50%;
  background: var(--primary);
  animation: pulse 1.1s infinite ease-in-out;
}

@keyframes pulse {
  0%,
  100% {
    opacity: 0.25;
  }
  50% {
    opacity: 1;
  }
}

.empty-tip {
  color: var(--text-3);
  font-size: 12px;
  text-align: center;
  padding: 20px 0;
}

.sidebar-foot {
  border-top: 1px solid var(--border);
  padding-top: 10px;
  display: flex;
  flex-direction: column;
  gap: 6px;
}

.full {
  width: 100%;
  text-align: left;
}

.full.on {
  color: var(--primary);
  background: rgba(47, 107, 255, 0.08);
}

.main {
  flex: 1;
  display: flex;
  flex-direction: column;
  min-width: 0;
}

/* 聊天区 + 右侧预览并排。预览是独立一列而不是浮层：滑出时聊天区让位，不遮挡内容 */
.body-row {
  flex: 1;
  display: flex;
  min-height: 0;
}

.chat-col {
  flex: 1;
  display: flex;
  flex-direction: column;
  min-width: 0;
  min-height: 0;
}

.topbar {
  height: 52px;
  flex: 0 0 52px;
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 0 20px;
  background: var(--panel);
  border-bottom: 1px solid var(--border);
}

.topbar-title {
  font-size: 14px;
  font-weight: 600;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.ws-tag {
  font-size: 12px;
  color: var(--text-2);
  background: var(--panel-2);
  border: 1px solid var(--border);
  border-radius: 999px;
  padding: 3px 10px;
}

.status {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 8px 20px;
  font-size: 12.5px;
}

.status.running {
  color: var(--text-2);
  background: #f2f6ff;
  border-bottom: 1px solid #e2ebff;
}

.status.error {
  color: var(--danger);
  background: #fef1f1;
  border-bottom: 1px solid #f8dcdc;
  justify-content: space-between;
}

.status.error .link {
  color: var(--danger);
  text-decoration: underline;
  font-size: 12.5px;
}

.spinner {
  width: 10px;
  height: 10px;
  border-radius: 50%;
  border: 2px solid #c8d8ff;
  border-top-color: var(--primary);
  animation: spin 0.8s linear infinite;
}

@keyframes spin {
  to {
    transform: rotate(360deg);
  }
}

.records {
  width: 380px;
  flex: 0 0 380px;
  background: var(--panel);
  border-left: 1px solid var(--border);
  display: flex;
  flex-direction: column;
}

.records-head {
  height: 52px;
  flex: 0 0 52px;
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 0 16px;
  border-bottom: 1px solid var(--border);
  font-weight: 600;
  font-size: 13.5px;
}

.records-loading,
.records-empty {
  padding: 20px;
  font-size: 12.5px;
  color: var(--text-3);
}

.records-body {
  flex: 1;
  overflow-y: auto;
  padding: 14px 16px 24px;
}

.records-path {
  font-size: 11px;
  color: var(--text-3);
  word-break: break-all;
  margin: 0 0 14px;
}

.records-sec {
  margin-bottom: 18px;
}

.records-sec h4 {
  margin: 0 0 8px;
  font-size: 12.5px;
  font-weight: 600;
  display: flex;
  align-items: center;
  gap: 6px;
}

.tag {
  font-size: 10.5px;
  font-weight: 400;
  color: var(--text-3);
  background: var(--panel-2);
  border: 1px solid var(--border);
  border-radius: 4px;
  padding: 1px 5px;
}

.records-sec pre {
  margin: 0;
  font-size: 11.5px;
  line-height: 1.65;
  color: var(--text-2);
  background: var(--panel-2);
  border: 1px solid var(--border);
  border-radius: 8px;
  padding: 10px;
  white-space: pre-wrap;
  word-break: break-word;
  max-height: 320px;
  overflow-y: auto;
  font-family: Consolas, monospace;
}

.daily {
  margin-bottom: 6px;
}

.daily summary {
  cursor: pointer;
  font-size: 12px;
  color: var(--text-2);
  padding: 4px 0;
}

.toast {
  position: fixed;
  left: 50%;
  bottom: 28px;
  transform: translateX(-50%);
  background: rgba(31, 35, 41, 0.9);
  color: #fff;
  font-size: 13px;
  padding: 9px 16px;
  border-radius: 8px;
}

.fade-enter-active,
.fade-leave-active {
  transition: opacity 0.2s;
}

.fade-enter-from,
.fade-leave-to {
  opacity: 0;
}
</style>
