<script setup>
import { computed, nextTick, onMounted, ref, watch } from 'vue'
import { api, logFrontend } from '../api/bridge'
import MarkdownBlock from './MarkdownBlock.vue'
import FileCard from './FileCard.vue'

const props = defineProps({
  workspaceId: { type: String, default: '' },
  sessionId: { type: String, default: '' },
  stream: { type: Object, default: null },
  stopping: { type: Boolean, default: false },
  /** 可选模型列表（来自模型配置） */
  models: { type: Array, default: () => [] },
  /** 当前会话生效的模型 id */
  modelId: { type: String, default: '' },
  /** 右侧正在预览的文件 id，用于高亮对应的卡片 */
  previewFileId: { type: String, default: '' }
})
const emit = defineEmits(['send', 'stop', 'pick-model', 'notify', 'preview'])

const messages = ref([])
const pending = ref([])
const input = ref('')
const scroller = ref(null)
const box = ref(null)

const running = computed(() => !!props.stream?.running)

/** 归一化正文：去掉全部空白 */
function normText(s) {
  return String(s == null ? '' : s).replace(/\s+/g, '')
}

/**
 * 流式正文是否已被「落库的权威正文」覆盖。
 *
 * <p>不能直接比字符串相等：流式那份是逐 token 拼出来的，落库那份由会话状态装配
 * （一轮里多段回答之间补 {@code \n\n}、两侧还可能各自裁掉首尾空白），差一个字符就会被
 * 判成「两条回答」，界面上于是叠出一条几乎一模一样的 —— 也就是「响应会重复」。
 * 这里按归一化后的包含关系判定，并要求长度接近，避免把上一轮那条短回答误判成本轮正文。
 */
function sameAnswer(stored, streamed) {
  const a = normText(stored)
  const b = normText(streamed)
  if (!a || !b) return false
  if (a === b) return true
  const [short, long] = a.length <= b.length ? [a, b] : [b, a]
  return long.includes(short) && short.length >= long.length * 0.6
}

/**
 * 输入法是否正在组合输入。
 *
 * <p>自己维护而不是用 `e.isComposing`：JavaFX WebView 里那个字段可能恒为 true，
 * 会让回车彻底失效（表现为「按回车只是换行」）。
 */
const composing = ref(false)

const items = computed(() => {
  const list = [...messages.value, ...pending.value]
  const st = props.stream
  if (!st) return list

  const live = {
    role: 'assistant',
    content: st.content,
    reasoning: st.reasoning,
    // 光标只跟 running 走：写死 true 会让生成结束后光标一直闪
    streaming: !!st.running,
    // 本轮耗时 / token：耗时前端自算，token 来自 AG-UI 的 CUSTOM(token_usage) 事件
    durationMs: st.durationMs || 0,
    inputTokens: st.inputTokens || 0,
    outputTokens: st.outputTokens || 0,
    totalTokens: st.totalTokens || 0,
    // 本轮的工具调用过程（AG-UI 的 TOOL_CALL_* 事件）
    tools: st.tools || [],
    // 本轮涉及的文件（交付物 + 读/写/改过的，见 WorkspaceView.refreshFiles）
    files: st.files || []
  }

  if (!st.running && !st.content && !st.reasoning && !live.tools.length) {
    if (st.error) {
      list.push({ role: 'assistant', content: '', error: st.error })
    }
    return list
  }

  // 生成结束后这条回答会被写进会话状态，权威版本由 load() 读回来（带时间与 token）。
  // 那时不要再叠一条 —— 否则界面上会出现两条一模一样的回答。
  const last = list[list.length - 1]
  if (!st.running && last && last.role === 'assistant' && sameAnswer(last.content, st.content)) {
    // 并进权威那条的只有「本轮过程信息」：耗时（后端不落盘）、思考与工具调用（会话状态里没有）
    return [
      ...list.slice(0, -1),
      {
        ...last,
        durationMs: last.durationMs || live.durationMs,
        reasoning: last.reasoning || live.reasoning,
        tools: live.tools.length ? live.tools : last.tools
      }
    ]
  }

  // 走到这里说明两份正文没能对上，界面上会多出一条。记一条日志便于定位：
  // 两份分别来自「逐 token 拼接」与「会话状态装配」，口径不同时长度就会不同。
  if (!st.running && last && last.role === 'assistant' && st.content) {
    logFrontend(
      'warn',
      `回答未与落库版本对齐（界面会多一条）：落库 ${(last.content || '').length} 字 / 流式 ${st.content.length} 字`
    )
  }

  list.push(live)
  return list
})

// ---------- 元信息格式化 ----------

function fmtTime(s) {
  if (!s) return ''
  const d = new Date(s)
  if (!isNaN(d.getTime())) {
    return d.toLocaleTimeString('zh-CN', { hour12: false })
  }
  // 后端给的可能已是可读串（如 "2026-09-24 17:00:00"），WebKit 解析不了就抓时间部分
  const m = String(s).match(/(\d{1,2}:\d{2}(:\d{2})?)/)
  return m ? m[1] : String(s)
}

function fmtDuration(ms) {
  if (!ms || ms <= 0) return ''
  if (ms < 1000) return ms + 'ms'
  const sec = ms / 1000
  if (sec < 60) return sec.toFixed(1) + 's'
  const min = Math.floor(sec / 60)
  return min + '分' + Math.round(sec - min * 60) + '秒'
}

/** 回答下方那行小字：时间 · 耗时 · token */
function metaParts(m) {
  const out = []
  const t = fmtTime(m.timestamp)
  if (t) out.push(t)
  const d = fmtDuration(m.durationMs)
  if (d) out.push('耗时 ' + d)
  if (m.totalTokens > 0) {
    out.push(m.totalTokens.toLocaleString() + ' tokens')
  }
  return out
}

// ---------- 工具调用卡片 ----------

/** 工具名 → 图标。工具名由后端/AgentScope 给（英文），认不出就用通用扳手 */
const TOOL_ICONS = {
  read_file: '📖',
  write_file: '✏️',
  edit_file: '✏️',
  list_files: '📂',
  glob: '🔍',
  grep: '🔍',
  bash: '⌨️',
  shell: '⌨️',
  execute_command: '⌨️',
  web_search: '🌐',
  web_fetch: '🌐'
}

const TOOL_LABELS = {
  read_file: '读取文件',
  write_file: '写入文件',
  edit_file: '编辑文件',
  list_files: '列出目录',
  glob: '查找文件',
  grep: '搜索内容',
  bash: '执行命令',
  shell: '执行命令',
  execute_command: '执行命令',
  web_search: '联网搜索',
  web_fetch: '抓取网页'
}

function toolIcon(name) {
  return TOOL_ICONS[name] || '🔧'
}

function toolLabel(name) {
  return TOOL_LABELS[name] || name
}

/**
 * 结果可能是整个文件的内容（几十 KB），卡片折叠态只给一段摘要。
 *
 * 只处理前 400 个字符再压空白：对全文跑正则的代价与文件大小成正比，
 * 而这个函数每次重渲染都会被调用（流式期间每秒几十次）。
 */
function shortResult(s) {
  const src = String(s == null ? '' : s)
  if (!src) return ''
  const head = src.length > 400 ? src.slice(0, 400) : src
  const t = head.trim().replace(/\s+/g, ' ')
  return t.length > 160 ? t.slice(0, 160) + '…' : t
}

const suggestions = [
  { icon: '📝', text: '帮我把下面这段会议记录整理成结构化纪要' },
  { icon: '📊', text: '把这份周报数据汇总成表格并给出趋势结论' },
  { icon: '✉️', text: '起草一封项目延期的对外沟通邮件' },
  { icon: '🔍', text: '总结这份合同的付款条款和风险点' }
]

async function load() {
  if (!props.workspaceId || !props.sessionId) {
    messages.value = []
    return
  }
  // 消息与交付文件并行取：两者互不依赖，串行只是白等一个来回
  const [list, files] = await Promise.all([
    api.getMessages(props.workspaceId, props.sessionId),
    api.listFiles(props.workspaceId, props.sessionId).catch((e) => {
      logFrontend('warn', `加载交付文件失败: ${e?.message || e}`)
      return []
    })
  ])
  // 元信息（时间 · 耗时 · token）在这里算一次并挂到消息上。
  // 放到模板里现算的话，流式期间每次重渲染都要对**每一条**历史回答重算
  // （toLocaleTimeString 不便宜），几十条 × 每秒几十次会明显吃主线程。
  const msgs = (list || []).map((m) => (m.role === 'assistant' ? { ...m, meta: metaParts(m) } : m))
  messages.value = attachFiles(msgs, files || [])
  pending.value = []
  await nextTick()
  scrollToBottom()
}

// ---------- 涉及的文件 ----------

/**
 * 解析后端 Msg 的时间戳（`yyyy-MM-dd HH:mm:ss.SSS`）。
 *
 * 不直接用 `new Date(str)`：这个格式不是 ISO，WebKit 的解析结果不可靠；
 * 按本地时区逐字段构造，与后端写出的时区一致。
 */
function parseTs(s) {
  if (!s) return null
  const m = String(s).match(/^(\d{4})-(\d{2})-(\d{2})[ T](\d{2}):(\d{2}):(\d{2})(?:\.(\d{1,3}))?/)
  if (!m) return null
  return new Date(+m[1], +m[2] - 1, +m[3], +m[4], +m[5], +m[6], +(m[7] || 0)).getTime()
}

/**
 * 把涉及的文件挂到「时间上最接近的那条回答」下面。
 *
 * 不用「大于/小于」比较大小：消息时间戳是**这条消息被创建的时刻**，而文件是在一轮生成
 * 中途被碰到的，两者先后关系不固定（取决于时间戳打在消息头还是消息尾）。取最近的一条，
 * 两种口径下都指向同一条回答。
 */
function attachFiles(msgs, files) {
  if (!files || !files.length) return msgs
  const out = msgs.map((m) => ({ ...m }))
  const asst = []
  out.forEach((m, i) => {
    if (m.role === 'assistant') asst.push(i)
  })
  if (!asst.length) return out

  for (const f of files) {
    let target = asst[asst.length - 1]
    let bestDiff = Infinity
    for (const i of asst) {
      const t = parseTs(out[i].timestamp)
      if (t == null) continue
      const diff = Math.abs(t - f.createdAt)
      if (diff < bestDiff) {
        bestDiff = diff
        target = i
      }
    }
    const m = out[target]
    out[target] = { ...m, files: [...(m.files || []), f] }
  }
  return out
}

watch(
  () => props.sessionId,
  () => {
    pending.value = []
    load()
  },
  { immediate: true }
)

// 生成结束（done / error）后由 App 递增 seq，触发一次权威数据重载
watch(
  () => props.stream?.seq,
  () => {
    if (!running.value) load()
  }
)

watch(
  () => [props.stream?.content, props.stream?.reasoning, items.value.length],
  () => scrollToBottom()
)

/** 跨环境取 rAF：JavaFX WebView 的 WebKit 支持，兜底用定时器 */
const raf = typeof requestAnimationFrame === 'function'
  ? requestAnimationFrame
  : (fn) => setTimeout(fn, 16)

let scrollScheduled = false

/**
 * 滚到底部。
 *
 * <p>合并同一帧内的多次请求：流式期间每个 delta 都会调到这里，而读
 * {@code scrollHeight} 会强制同步布局（reflow），逐个执行会把主线程占满 ——
 * 表现就是生成回答时界面点不动。
 */
function scrollToBottom() {
  if (scrollScheduled) return
  scrollScheduled = true
  raf(() => {
    scrollScheduled = false
    const el = scroller.value
    if (!el) return
    el.scrollTop = el.scrollHeight
  })
}

function submit() {
  const text = input.value.trim()
  if (!text || running.value) return
  pending.value.push({ role: 'user', content: text })
  input.value = ''
  emit('send', text)
  nextTick(scrollToBottom)
}

function onKeydown(e) {
  // 同时认 key 与 keyCode：不同 WebKit 版本给的字段不完全一致
  const isEnter = e.key === 'Enter' || e.keyCode === 13
  if (!isEnter) return
  // keyCode 229 = 这个按键正被输入法消化（此时回车用于确认候选词）
  const imeBusy = composing.value || e.keyCode === 229
  if (e.shiftKey || imeBusy) {
    if (imeBusy) {
      // 记一条：打包环境里回车不发送时，这条日志能立刻区分「被输入法吃掉」还是别的原因
      logFrontend('info', `回车交给输入法处理（组合中）key=${e.key} keyCode=${e.keyCode}`)
    }
    return
  }
  // 生成中不抢占回车：让用户能继续把下一段打好，而不是按了毫无反应
  if (running.value) {
    logFrontend('info', '生成中，回车未触发发送')
    return
  }
  e.preventDefault()
  submit()
}

function useSuggestion(text) {
  input.value = text
  nextTick(() => box.value?.focus())
}

// 正文渲染交给 markdown-it（见文件顶部），这里不再做「按 ``` 分段」的土办法

onMounted(() => scrollToBottom())
</script>

<template>
  <div class="chat">
    <div ref="scroller" class="messages">
      <div v-if="!items.length" class="welcome">
        <div class="welcome-title">有什么可以帮你？</div>
        <div class="welcome-sub">
          我可以协助公文写作、会议纪要、数据汇总、事务提醒等日常办公工作
        </div>
        <div class="suggestions">
          <div v-for="s in suggestions" :key="s.text" class="suggestion" @click="useSuggestion(s.text)">
            <span class="ico">{{ s.icon }}</span>
            <span>{{ s.text }}</span>
          </div>
        </div>
      </div>

      <div v-for="(m, i) in items" :key="i" class="row" :class="m.role">
        <div v-if="m.role === 'assistant'" class="avatar assistant">AI</div>
        <div class="bubble">
          <details v-if="m.reasoning" class="reasoning">
            <summary>思考过程</summary>
            <div class="reasoning-body">{{ m.reasoning }}</div>
          </details>

          <!-- 工具调用过程（AG-UI 的 TOOL_CALL_* 事件）。
               默认折叠：一次任务可能读十几个文件，全展开会把回答挤出屏幕。
               折叠态只留「图标 + 名称 + 状态」，参数与结果点开才看。 -->
          <div v-if="m.tools && m.tools.length" class="tools">
            <details v-for="t in m.tools" :key="t.id" class="tool" :class="t.status">
              <summary class="tool-head">
                <span class="tool-ico">{{ toolIcon(t.name) }}</span>
                <span class="tool-name">{{ toolLabel(t.name) }}</span>
                <span v-if="t.status === 'done'" class="tool-ok">✓</span>
                <span v-else class="tool-dot"></span>
              </summary>
              <div v-if="t.args" class="tool-line">
                <span class="tool-k">参数</span><code>{{ t.args }}</code>
              </div>
              <div v-if="t.result" class="tool-line">
                <span class="tool-k">结果</span><code>{{ shortResult(t.result) }}</code>
              </div>
            </details>
          </div>

          <!-- 正文。
               生成中走纯文本：`{{ }}` 只更新文本节点，而 v-html + markdown 渲染会重建
               整棵 DOM 子树 —— 模型每秒能推几十个 delta，那样会把主线程占满（页面点不动）。
               生成结束再渲染 Markdown（MarkdownBlock 内部按内容缓存渲染结果，
               历史消息在后续流式重渲染中零开销）。 -->
          <div v-if="m.streaming && m.content" class="text plain">{{ m.content }}</div>
          <MarkdownBlock v-else-if="m.content" :text="m.content" />

          <span v-if="m.streaming" class="caret"></span>

          <!-- 本轮涉及的文件（交付物 + 读/写/改过的）。
               点卡片 → 右侧滑出预览；右键 → 另存为 / 打开 / 所在文件夹。 -->
          <div v-if="m.files && m.files.length" class="files">
            <FileCard
              v-for="f in m.files"
              :key="f.id"
              :file="f"
              :active="previewFileId === f.id"
              @notify="(t) => emit('notify', t)"
              @preview="(f) => emit('preview', f)"
            />
          </div>

          <div v-if="m.error" class="error">⚠ {{ m.error }}</div>
          <!-- 回答下方一行小字：时间 · 耗时 · token（生成中不显示；meta 在 load() 里算好） -->
          <div
            v-if="m.role === 'assistant' && !m.streaming && !m.error && m.meta && m.meta.length"
            class="meta"
          >
            <span v-for="(p, pi) in m.meta" :key="pi">{{ p }}</span>
          </div>
        </div>
        <div v-if="m.role === 'user'" class="avatar user">我</div>
      </div>
    </div>

    <div class="composer">
      <div class="composer-box">
        <textarea
          ref="box"
          v-model="input"
          rows="2"
          placeholder="输入内容，Enter 发送 / Shift + Enter 换行"
          @keydown="onKeydown"
          @compositionstart="composing = true"
          @compositionend="composing = false"
        ></textarea>
        <div class="composer-actions">
          <div class="composer-left">
            <label v-if="models.length" class="model-pick">
              <span class="pick-label">模型</span>
              <select
                :value="modelId"
                :disabled="running"
                :title="'切换当前会话使用的模型（会记住，下次进入这个会话自动恢复）'"
                @change="emit('pick-model', $event.target.value)"
              >
                <option v-for="m in models" :key="m.id" :value="m.id">
                  {{ m.name || m.id }}{{ m.hasApiKey ? '' : '（未配 Key）' }}
                </option>
              </select>
            </label>
            <span v-else class="hint">未配置模型，请到「配置中心 → 模型配置」添加</span>
            <span class="hint">Enter 发送 · Shift+Enter 换行</span>
          </div>
          <button v-if="running" @click="emit('stop')" :disabled="stopping">
            {{ stopping ? '停止中…' : '■ 停止' }}
          </button>
          <button v-else class="primary" :disabled="!input.trim()" @click="submit">发送</button>
        </div>
      </div>
    </div>
  </div>
</template>

<style scoped>
.chat {
  flex: 1;
  display: flex;
  flex-direction: column;
  min-height: 0;
}

.messages {
  flex: 1;
  overflow-y: auto;
  padding: 24px 28px 8px;
  scroll-behavior: smooth;
}

.welcome {
  max-width: 620px;
  margin: 8vh auto 0;
  text-align: center;
}

.welcome-title {
  font-size: 20px;
  font-weight: 600;
  margin-bottom: 8px;
}

.welcome-sub {
  color: var(--text-2);
  font-size: 13px;
  margin-bottom: 24px;
}

.suggestions {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 10px;
  text-align: left;
}

.suggestion {
  display: flex;
  gap: 8px;
  align-items: flex-start;
  background: var(--panel);
  border: 1px solid var(--border);
  border-radius: var(--radius);
  padding: 12px;
  font-size: 13px;
  color: var(--text-2);
  cursor: pointer;
  transition: all 0.15s;
}

.suggestion:hover {
  border-color: #bcd0ff;
  color: var(--primary);
  box-shadow: var(--shadow);
}

.suggestion .ico {
  line-height: 1.3;
}

.row {
  display: flex;
  gap: 10px;
  margin-bottom: 18px;
  align-items: flex-start;
}

.row.user {
  justify-content: flex-end;
}

.avatar {
  width: 28px;
  height: 28px;
  flex: 0 0 28px;
  border-radius: 8px;
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: 11px;
  font-weight: 600;
}

.avatar.assistant {
  background: var(--primary);
  color: #fff;
}

.avatar.user {
  background: #e7ebf5;
  color: var(--text-2);
}

.bubble {
  max-width: 72%;
  padding: 10px 14px;
  border-radius: var(--radius);
  background: var(--panel);
  border: 1px solid var(--border);
  box-shadow: var(--shadow);
  line-height: 1.7;
  word-break: break-word;
}

.row.user .bubble {
  background: var(--primary);
  border-color: var(--primary);
  color: #fff;
  box-shadow: none;
}

/* ---------- 回答正文 ----------
   Markdown 渲染块（MarkdownBlock.vue）自带排版样式，这里只留两态共用的基础样式
   与「生成中纯文本」的换行规则。 */

.text {
  line-height: 1.7;
  word-break: break-word;
}

/* 生成中的纯文本：保留换行与缩进，等生成结束再换成 Markdown 渲染的节点 */
.text.plain {
  white-space: pre-wrap;
}

.reasoning {
  margin-bottom: 8px;
  font-size: 12.5px;
  color: var(--text-2);
  border-left: 2px solid var(--border);
  padding-left: 8px;
}

.reasoning summary {
  cursor: pointer;
  color: var(--text-3);
  outline: none;
}

.reasoning-body {
  white-space: pre-wrap;
  margin-top: 6px;
}

/* ---------- 工具调用卡片（AG-UI 的 TOOL_CALL_* 事件） ---------- */

.tools {
  display: flex;
  flex-direction: column;
  gap: 6px;
  margin-bottom: 8px;
}

.tool {
  border: 1px solid var(--border);
  border-radius: 8px;
  padding: 6px 10px;
  font-size: 12.5px;
}

.tool.done {
  opacity: 0.82;
}

.tool-head {
  display: flex;
  align-items: center;
  gap: 6px;
  color: var(--text-2);
  cursor: pointer;
}

/* summary 用了 flex，浏览器默认的展开三角会被吃掉，这里自己画一个 */
summary.tool-head::-webkit-details-marker {
  display: none;
}

summary.tool-head::before {
  content: '▸';
  flex: none;
  font-size: 11px;
  color: var(--text-3);
}

details[open] > summary.tool-head::before {
  content: '▾';
}

.tool-ico {
  flex: none;
}

.tool-name {
  font-weight: 500;
}

.tool-ok {
  margin-left: auto;
  color: var(--primary);
}

/* 执行中的小圆点：复用 caret 的 blink 动画，不引额外图标 */
.tool-dot {
  margin-left: auto;
  width: 7px;
  height: 7px;
  border-radius: 50%;
  background: var(--primary);
  animation: blink 1s steps(2) infinite;
}

.tool-line {
  display: flex;
  gap: 6px;
  margin-top: 4px;
  align-items: baseline;
  color: var(--text-3);
}

.tool-k {
  flex: none;
}

.tool-line code {
  font-family: ui-monospace, SFMono-Regular, Menlo, Consolas, monospace;
  font-size: 12px;
  white-space: pre-wrap;
  word-break: break-all;
  color: var(--text-2);
}

/* ---------- 涉及的文件 ---------- */

/* 一行两张卡（宽度 50%），多了自动换行；单张时也只占一半，不会拉成一条长板 */
.files {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  margin-top: 8px;
}

.files :deep(.file-card) {
  flex: 0 0 calc(50% - 4px);
  max-width: calc(50% - 4px);
}

.caret {
  display: inline-block;
  width: 7px;
  height: 14px;
  background: var(--primary);
  vertical-align: -2px;
  animation: blink 1s steps(2) infinite;
}

@keyframes blink {
  50% {
    opacity: 0;
  }
}

.error {
  color: var(--danger);
  font-size: 12.5px;
  margin-top: 6px;
}

.meta {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 6px;
  margin-top: 9px;
  padding-top: 7px;
  border-top: 1px dashed var(--border);
  font-size: 11px;
  color: var(--text-3);
  user-select: text;
}

.meta span + span::before {
  content: '·';
  margin-right: 6px;
  color: var(--text-3);
  opacity: 0.6;
}

.composer {
  padding: 12px 28px 20px;
}

.composer-box {
  background: var(--panel);
  border: 1px solid var(--border);
  border-radius: 12px;
  padding: 10px 12px 8px;
  box-shadow: var(--shadow);
  transition: border-color 0.15s;
}

.composer-box:focus-within {
  border-color: #bcd0ff;
}

.composer-box textarea {
  border: none;
  padding: 2px 0 6px;
  resize: none;
  max-height: 180px;
  line-height: 1.6;
}

.composer-box textarea:focus {
  box-shadow: none;
}

.composer-actions {
  display: flex;
  align-items: center;
  justify-content: flex-end;
  gap: 10px;
}

.hint {
  font-size: 11.5px;
  color: var(--text-3);
}

.composer-left {
  flex: 1;
  display: flex;
  align-items: center;
  gap: 12px;
  min-width: 0;
}

.model-pick {
  display: inline-flex;
  align-items: center;
  gap: 5px;
  font-size: 11.5px;
  color: var(--text-3);
  flex: 0 1 auto;
  min-width: 0;
}

.pick-label {
  flex: none;
}

.model-pick select {
  max-width: 210px;
  padding: 2px 4px;
  font-size: 11.5px;
  color: var(--text-2);
  background: var(--panel);
  border: 1px solid var(--border);
  border-radius: 6px;
}

.model-pick select:disabled {
  opacity: 0.55;
}
</style>
