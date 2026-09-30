<script setup>
import { onMounted, ref } from 'vue'
import { confirmDialog } from '../utils/confirm'
import ModelTab from './config/ModelTab.vue'
import MemoryTab from './config/MemoryTab.vue'
import SkillsTab from './config/SkillsTab.vue'
import ToolsTab from './config/ToolsTab.vue'
import GeneralTab from './config/GeneralTab.vue'

const emit = defineEmits(['close', 'saved'])

const tabs = [
  { key: 'model', label: '模型配置', comp: ModelTab },
  { key: 'memory', label: '全局记忆', comp: MemoryTab },
  { key: 'skills', label: '技能', comp: SkillsTab },
  { key: 'tools', label: '工具', comp: ToolsTab },
  { key: 'general', label: '通用', comp: GeneralTab }
]

const active = ref('model')
const dirty = ref(false)
const paths = ref(null)

/** 子面板报告「有未保存改动」，关窗前提醒一下 */
function markDirty(v) {
  dirty.value = !!v
}

async function tryClose() {
  if (dirty.value) {
    const ok = await confirmDialog('有未保存的改动，确定关闭吗？', {
      title: '关闭配置中心',
      confirmText: '放弃改动',
      danger: false
    })
    if (!ok) return
  }
  emit('close')
}

onMounted(() => {})
</script>

<template>
  <div class="mask" @click.self="tryClose">
    <div class="dialog">
      <header>
        <span>配置中心</span>
        <button class="ghost" @click="tryClose">×</button>
      </header>

      <nav class="tabs">
        <button
          v-for="t in tabs"
          :key="t.key"
          :class="{ active: active === t.key }"
          @click="active = t.key"
        >
          {{ t.label }}
        </button>
      </nav>

      <div class="body">
        <component
          :is="tabs.find((t) => t.key === active).comp"
          @dirty="markDirty"
          @saved="emit('saved', $event)"
        />
      </div>
    </div>
  </div>
</template>

<style scoped>
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
  width: 720px;
  height: 76vh;
  background: var(--panel);
  border-radius: 14px;
  box-shadow: 0 18px 50px rgba(20, 30, 60, 0.22);
  display: flex;
  flex-direction: column;
  overflow: hidden;
}

header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 14px 18px 10px;
  font-weight: 600;
}

header .ghost {
  font-size: 18px;
  line-height: 1;
  padding: 0 8px;
}

.tabs {
  display: flex;
  gap: 4px;
  padding: 0 14px;
  border-bottom: 1px solid var(--border);
}

.tabs button {
  padding: 7px 14px;
  border: none;
  background: transparent;
  color: var(--text-2);
  font-size: 13px;
  border-bottom: 2px solid transparent;
  cursor: pointer;
}

.tabs button.active {
  color: var(--primary);
  border-bottom-color: var(--primary);
  font-weight: 600;
}

.body {
  flex: 1;
  overflow-y: auto;
  padding: 16px 18px;
}
</style>
