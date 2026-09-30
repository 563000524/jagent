<script setup>
import { computed, onUnmounted, ref, watch } from 'vue'
import { api } from '../api/bridge'
import FileIcon from './FileIcon.vue'
import SourceView from './SourceView.vue'
import { useFileActions } from '../utils/fileActions'

const props = defineProps({
  /** 要预览的文件（后端 ArtifactView）；null = 不显示 */
  file: { type: Object, default: null }
})
const emit = defineEmits(['notify', 'close'])

const { busy, act } = useFileActions(() => props.file, (m) => emit('notify', m))

const content = ref('')
const loading = ref(false)
const error = ref('')

/** 超过这个大小不做文本预览：整份塞进 DOM 会让界面卡住，也没人读得下去 */
const TEXT_LIMIT = 1024 * 1024

const kind = computed(() => props.file?.preview || 'none')
const tooBig = computed(() => (props.file?.size || 0) > TEXT_LIMIT)
const rawUrl = computed(() => (props.file ? api.fileRawUrl(props.file.id) : ''))

function fmtSize(n) {
  const size = Number(n) || 0
  if (size < 1024) return size + ' B'
  if (size < 1024 * 1024) return (size / 1024).toFixed(1) + ' KB'
  return (size / 1024 / 1024).toFixed(2) + ' MB'
}

/**
 * 取文本内容。
 *
 * 用 fetch 而不是 `<iframe src>`：令牌拼在查询串上（`fileRawUrl` 已带），
 * 而 WebView 里 iframe 的滚动/高度很难收拾，文本直接进 DOM 更好控制。
 */
async function load() {
  content.value = ''
  error.value = ''
  const f = props.file
  if (!f || kind.value !== 'text' || tooBig.value) return
  loading.value = true
  try {
    const res = await fetch(rawUrl.value)
    if (!res.ok) {
      throw new Error(res.status === 404 ? '文件不存在或已被清理' : `读取失败（HTTP ${res.status}）`)
    }
    content.value = await res.text()
  } catch (e) {
    error.value = String(e?.message || e)
  } finally {
    loading.value = false
  }
}

watch(() => props.file?.id, load, { immediate: true })

function onKey(e) {
  if (e.key === 'Escape') emit('close')
}

window.addEventListener('keydown', onKey)
onUnmounted(() => window.removeEventListener('keydown', onKey))
</script>

<template>
  <transition name="slide">
    <aside v-if="file" class="preview">
      <header class="head">
        <FileIcon :name="file.name" :size="34" />
        <div class="head-info">
          <div class="name" :title="file.name">{{ file.name }}</div>
          <div class="sub">{{ fmtSize(file.size) }}</div>
        </div>
        <button class="icon-btn" title="关闭（Esc）" @click="emit('close')">×</button>
      </header>

      <div class="acts">
        <button class="ghost" :disabled="!!busy" @click="act('save')">
          {{ busy === 'save' ? '…' : '另存为' }}
        </button>
        <button class="ghost" :disabled="!!busy" @click="act('open')">打开</button>
        <button class="ghost" :disabled="!!busy" @click="act('reveal')">所在文件夹</button>
        <button
          v-if="kind === 'text' && !tooBig"
          class="ghost reload"
          :disabled="loading"
          title="重新读取（文件可能刚被改过）"
          @click="load"
        >
          ↻
        </button>
      </div>

      <div class="body">
        <div v-if="loading" class="tip">读取中…</div>
        <div v-else-if="error" class="tip error">⚠ {{ error }}</div>

        <!-- 图片：直接出图。WebView 能渲染 png/jpg/gif/bmp/webp/svg -->
        <div v-else-if="kind === 'image'" class="image">
          <img :src="rawUrl" :alt="file.name" />
        </div>

        <!-- 文本：md 走渲染、csv/tsv 出表格、json 格式化、代码高亮（见 SourceView） -->
        <template v-else-if="kind === 'text'">
          <div v-if="tooBig" class="tip">
            文件较大（{{ fmtSize(file.size) }}），不在面板里预览。
            <button class="ghost" @click="act('open')">用系统程序打开</button>
          </div>
          <SourceView v-else-if="content" :text="content" :name="file.name" />
          <div v-else class="tip">（空文件）</div>
        </template>

        <!-- 其余类型（docx / xlsx / pdf / 压缩包…）：WebView 渲染不了 -->
        <div v-else class="empty">
          <FileIcon :name="file.name" :size="52" />
          <p>这类文件不能在面板里预览</p>
          <button class="ghost" @click="act('open')">用系统程序打开</button>
        </div>
      </div>
    </aside>
  </transition>
</template>

<style scoped>
/* 右侧独立一列（父级 .body-row 是 flex 行），滑出时聊天区让位而不是被盖住 */
.preview {
  flex: 0 0 auto;
  width: min(520px, 55%);
  display: flex;
  flex-direction: column;
  min-height: 0;
  background: var(--panel);
  border-left: 1px solid var(--border);
}

.head {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 12px 14px;
  border-bottom: 1px solid var(--border);
}

.head-info {
  flex: 1;
  min-width: 0;
}

.name {
  font-size: 13.5px;
  font-weight: 600;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.sub {
  margin-top: 2px;
  font-size: 12px;
  color: var(--text-3);
}

.icon-btn {
  flex: none;
  border: none;
  background: transparent;
  padding: 0 8px;
  font-size: 20px;
  line-height: 1;
  color: var(--text-3);
  cursor: pointer;
}

.icon-btn:hover {
  color: var(--text);
}

.acts {
  display: flex;
  gap: 6px;
  padding: 10px 14px;
  border-bottom: 1px solid var(--border);
}

.acts button {
  font-size: 12px;
  padding: 4px 11px;
}

.acts button:disabled {
  opacity: 0.5;
}

.reload {
  margin-left: auto;
  padding: 4px 9px !important;
}

.body {
  flex: 1;
  overflow: auto;
  padding: 14px 14px 24px;
}

.tip {
  font-size: 12.5px;
  color: var(--text-3);
  display: flex;
  align-items: center;
  gap: 8px;
  flex-wrap: wrap;
}

.tip.error {
  color: var(--danger);
}

.image {
  display: flex;
  justify-content: center;
  padding: 4px;
}

.image img {
  max-width: 100%;
  border: 1px solid var(--border);
  border-radius: 8px;
  background: #fff;
}

.empty {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 12px;
  padding: 48px 0 0;
  color: var(--text-3);
}

.empty p {
  margin: 0;
  font-size: 12.5px;
}

/* 滑动效果：只有 transform，别用 width/left —— 那会触发整页重排 */
.slide-enter-active,
.slide-leave-active {
  transition: transform 0.18s ease, opacity 0.18s ease;
}

.slide-enter-from,
.slide-leave-to {
  transform: translateX(100%);
  opacity: 0;
}
</style>
