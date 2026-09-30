<script setup>
import { computed, onUnmounted, ref } from 'vue'
import FileIcon from './FileIcon.vue'
import { useFileActions } from '../utils/fileActions'

const props = defineProps({
  /** 后端 ArtifactView：{ id, name, size, description, action, preview, createdAt, url } */
  file: { type: Object, required: true },
  /** 是否是当前预览中的那张卡（高亮用） */
  active: { type: Boolean, default: false }
})
const emit = defineEmits(['notify', 'preview'])

const { busy, act } = useFileActions(() => props.file, (m) => emit('notify', m))

/** 右键菜单位置（clientX/clientY）；null = 不显示 */
const menu = ref(null)

/** 不能预览的类型（docx/xlsx/pdf…），点击卡片没有意义 —— 只有能预览的才给「点击查看」提示 */
const canPreview = computed(() => props.file.preview === 'image' || props.file.preview === 'text')

function fmtSize(n) {
  const size = Number(n) || 0
  if (size < 1024) return size + ' B'
  if (size < 1024 * 1024) return (size / 1024).toFixed(1) + ' KB'
  return (size / 1024 / 1024).toFixed(1) + ' MB'
}

function openPreview() {
  if (canPreview.value) emit('preview', props.file)
}

function onContextMenu(e) {
  e.preventDefault()
  // 贴右/下边时往里收，别让菜单跑出窗口
  const x = Math.min(e.clientX, window.innerWidth - 156)
  const y = Math.min(e.clientY, window.innerHeight - 130)
  menu.value = { x, y }
}

function closeMenu() {
  menu.value = null
}

function onKey(e) {
  if (e.key === 'Escape') closeMenu()
}

// 右键菜单只在卡片上开；点别处、按 Esc、滚动都关掉（否则会一直吊在屏幕上）
window.addEventListener('click', closeMenu)
window.addEventListener('keydown', onKey)
window.addEventListener('resize', closeMenu)
window.addEventListener('scroll', closeMenu, true)

onUnmounted(() => {
  window.removeEventListener('click', closeMenu)
  window.removeEventListener('keydown', onKey)
  window.removeEventListener('resize', closeMenu)
  window.removeEventListener('scroll', closeMenu, true)
})
</script>

<template>
  <div
    class="file-card"
    :class="{ active, clickable: canPreview }"
    :title="canPreview ? file.name + '（点击预览）' : file.name"
    @click="openPreview"
    @contextmenu="onContextMenu"
  >
    <FileIcon :name="file.name" :size="30" />
    <div class="info">
      <div class="name">{{ file.name }}</div>
      <div class="size">{{ fmtSize(file.size) }}</div>
    </div>
  </div>

  <div
    v-if="menu"
    class="menu"
    :style="{ left: menu.x + 'px', top: menu.y + 'px' }"
    @click.stop
  >
    <div v-if="canPreview" class="menu-item" @click="openPreview">预览</div>
    <div class="menu-item" :class="{ disabled: busy }" @click="act('save')">另存为…</div>
    <div class="menu-item" :class="{ disabled: busy }" @click="act('open')">打开</div>
    <div class="menu-item" :class="{ disabled: busy }" @click="act('reveal')">打开所在文件夹</div>
  </div>
</template>

<style scoped>
/* 卡片本体只留「图标 + 文件名 + 大小」三样：动作全在右键菜单里，
   把按钮摆在卡片上会让一屏对话全是按钮 */
.file-card {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 12px 14px;
  border-radius: 12px;
  background: #edf6e6;
  border: 1px solid #edf6e6;
  cursor: default;
  transition: border-color 0.15s, background 0.15s;
}

.file-card.clickable {
  cursor: pointer;
}

.file-card:hover {
  border-color: #cfe3bd;
}

.file-card.active {
  background: #e3f1d7;
  border-color: #a9cf8a;
}

.info {
  min-width: 0;
}

.name {
  font-size: 13.5px;
  font-weight: 600;
  color: #2b3328;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.size {
  margin-top: 1px;
  font-size: 12px;
  color: #8b9585;
}

/* 右键菜单：必须用 fixed —— 卡片在气泡里，父级一旦 overflow 裁剪就看不见了 */
.menu {
  position: fixed;
  z-index: 60;
  min-width: 148px;
  padding: 4px;
  background: var(--panel);
  border: 1px solid var(--border);
  border-radius: 8px;
  box-shadow: var(--shadow);
}

.menu-item {
  padding: 6px 10px;
  font-size: 12.5px;
  color: var(--text-2);
  border-radius: 6px;
  cursor: pointer;
}

.menu-item:hover {
  background: var(--primary-soft);
  color: var(--primary);
}

.menu-item.disabled {
  opacity: 0.5;
  pointer-events: none;
}
</style>
