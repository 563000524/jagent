<script setup>
import { onMounted, onUnmounted, ref } from 'vue'
import { api, isLoggedIn, setToken, logFrontend } from './api/bridge'
import LoginView from './views/LoginView.vue'
import WorkspaceListView from './views/WorkspaceListView.vue'
import WorkspaceView from './views/WorkspaceView.vue'
import ConfigCenter from './components/ConfigCenter.vue'
import ConfirmDialog from './components/ConfirmDialog.vue'

/** 当前登录用户；null 表示未登录 */
const user = ref(null)
/** 当前进入的工作空间；null 表示停在工作空间列表 */
const currentWorkspace = ref(null)
const showConfig = ref(false)
const booting = ref(true)

let offUnauthorized = null

/**
 * 检查模型配置是否可用。不可用时直接打开配置中心 ——
 * 否则用户发出消息只会收到一句报错，不知道要去哪配。
 */
async function ensureModelConfigured() {
  try {
    const r = await api.listModels()
    const ready = (r.models || []).some((m) => m.hasApiKey)
    if (!ready) {
      showConfig.value = true
      logFrontend('warn', '未检测到可用模型，已打开配置中心')
    }
  } catch (e) {
    logFrontend('warn', `检查模型配置失败: ${e?.message || e}`)
  }
}

async function onLogged(u) {
  user.value = u
  currentWorkspace.value = null
  await ensureModelConfigured()
}

async function logout() {
  try {
    await api.logout()
  } catch {
    /* 令牌可能已失效，忽略 */
  }
  setToken('')
  user.value = null
  currentWorkspace.value = null
  showConfig.value = false
  logFrontend('info', '已退出登录')
}

onMounted(async () => {
  // 令牌失效（后端重启等）时统一回到登录页
  const handler = () => {
    user.value = null
    currentWorkspace.value = null
  }
  window.addEventListener('fastagent:unauthorized', handler)
  offUnauthorized = () => window.removeEventListener('fastagent:unauthorized', handler)

  if (isLoggedIn()) {
    try {
      const me = await api.me()
      await onLogged(me)
    } catch {
      setToken('')
    }
  }
  booting.value = false
})

onUnmounted(() => {
  if (offUnauthorized) offUnauthorized()
})
</script>

<template>
  <div class="root">
    <div v-if="booting" class="boot">
      <span class="spinner"></span>
      <span>正在启动…</span>
    </div>

    <!-- 未登录：只能看到登录页 -->
    <LoginView v-else-if="!user" @logged="onLogged" />

    <!-- 已登录但未进入工作空间：工作空间列表 -->
    <WorkspaceListView
      v-else-if="!currentWorkspace"
      :user="user"
      @open="(ws) => (currentWorkspace = ws)"
      @logout="logout"
      @config="showConfig = true"
    />

    <!-- 进入工作空间 -->
    <WorkspaceView
      v-else
      :workspace="currentWorkspace"
      @back="currentWorkspace = null"
      @logout="logout"
      @config="showConfig = true"
    />

    <ConfigCenter v-if="showConfig" @close="showConfig = false" />

    <!-- 全局确认框：替代 window.confirm（WebView 里它恒返回 false） -->
    <ConfirmDialog />
  </div>
</template>

<style scoped>
.root {
  height: 100%;
}

.boot {
  height: 100%;
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 10px;
  color: var(--text-3);
  font-size: 13px;
}

.spinner {
  width: 12px;
  height: 12px;
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
</style>
