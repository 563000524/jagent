<script setup>
import { ref, onMounted } from 'vue'
import { api, setToken, logFrontend } from '../api/bridge'

const emit = defineEmits(['logged'])

const username = ref('cjh')
const password = ref('')
const loading = ref(false)
const error = ref('')

async function submit() {
  const u = username.value.trim()
  if (!u || !password.value) {
    error.value = '请输入用户名和密码'
    return
  }
  loading.value = true
  error.value = ''
  try {
    const res = await api.login(u, password.value)
    setToken(res.token)
    logFrontend('info', `登录成功: ${res.userId}`)
    emit('logged', res)
  } catch (e) {
    error.value = String(e?.message || e)
    logFrontend('warn', `登录失败: ${error.value}`)
  } finally {
    loading.value = false
  }
}

onMounted(() => {
  // 本地已有令牌时先探一下是否还有效，有效就直接进主界面
  api
    .me()
    .then((me) => emit('logged', me))
    .catch(() => {
      /* 令牌失效，停留在登录页 */
    })
})
</script>

<template>
  <div class="login-wrap">
    <form class="login-card" @submit.prevent="submit">
      <div class="login-brand">
        <span class="logo">AI</span>
        <span class="login-title">办公智能体</span>
      </div>
      <p class="login-sub">请登录后使用</p>

      <label class="field">
        <span class="label">用户名</span>
        <input v-model="username" autocomplete="username" placeholder="请输入用户名" />
      </label>
      <label class="field">
        <span class="label">密码</span>
        <input
          v-model="password"
          type="password"
          autocomplete="current-password"
          placeholder="请输入密码"
        />
      </label>

      <div v-if="error" class="error">⚠ {{ error }}</div>

      <button class="primary login-btn" type="submit" :disabled="loading">
        {{ loading ? '登录中…' : '登录' }}
      </button>

      <p class="login-tip">当前为开发阶段，账号写死在服务端（cjh / 123456）</p>
    </form>
  </div>
</template>

<style scoped>
.login-wrap {
  height: 100%;
  display: flex;
  align-items: center;
  justify-content: center;
  background: var(--bg);
}

.login-card {
  width: 360px;
  background: var(--panel);
  border: 1px solid var(--border);
  border-radius: 14px;
  padding: 28px 26px 22px;
  box-shadow: 0 12px 40px rgba(20, 30, 60, 0.1);
  display: flex;
  flex-direction: column;
  gap: 14px;
}

.login-brand {
  display: flex;
  align-items: center;
  gap: 10px;
}

.login-title {
  font-size: 17px;
  font-weight: 600;
}

.login-sub {
  margin: -6px 0 6px;
  font-size: 12.5px;
  color: var(--text-3);
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

.login-btn {
  margin-top: 4px;
  width: 100%;
  padding: 9px 0;
  font-size: 14px;
}

.login-tip {
  margin: 2px 0 0;
  font-size: 11.5px;
  color: var(--text-3);
  text-align: center;
}

.error {
  font-size: 12.5px;
  color: var(--danger);
}
</style>
