<script setup>
import { computed, onMounted, ref } from 'vue'
import { api } from '../../api/bridge'

const emit = defineEmits(['dirty', 'saved'])

const path = ref('')
const tools = ref([])
const allowText = ref('')
const mcpText = ref('{\n}')
/** 是否允许 agent 执行本机命令（默认关） */
const shellEnabled = ref(false)
const loading = ref(true)
const busy = ref(false)
const error = ref('')
const notice = ref('')

/** 勾选状态：name -> 是否启用 */
const checked = ref({})

const builtin = computed(() => tools.value.filter((t) => t.source === '内置'))
const mcp = computed(() => tools.value.filter((t) => t.source === 'MCP'))

async function load() {
  loading.value = true
  error.value = ''
  try {
    const r = await api.getTools()
    path.value = r.path || ''
    tools.value = r.tools || []
    const cfg = r.config || {}
    allowText.value = (cfg.allow || []).join(', ')
    mcpText.value = JSON.stringify(cfg.mcpServers || {}, null, 2)
    shellEnabled.value = !!cfg.shellEnabled
    const map = {}
    for (const t of tools.value) {
      map[t.name] = t.enabled
    }
    checked.value = map
  } catch (e) {
    error.value = String(e?.message || e)
  } finally {
    loading.value = false
  }
}

function toggle(name) {
  checked.value = { ...checked.value, [name]: !checked.value[name] }
  emit('dirty', true)
}

function toggleShell() {
  shellEnabled.value = !shellEnabled.value
  emit('dirty', true)
}

async function save() {
  busy.value = true
  error.value = ''
  notice.value = ''
  let mcpServers
  try {
    mcpServers = mcpText.value.trim() ? JSON.parse(mcpText.value) : {}
  } catch (e) {
    error.value = 'MCP 服务器不是合法 JSON：' + (e?.message || e)
    busy.value = false
    return
  }
  const allow = allowText.value
    .split(',')
    .map((s) => s.trim())
    .filter(Boolean)
  const deny = builtin.value.filter((t) => !checked.value[t.name]).map((t) => t.name)

  try {
    const r = await api.saveTools({ allow, deny, mcpServers, shellEnabled: shellEnabled.value })
    tools.value = r.tools || []
    notice.value = '已保存，新的工具配置在下次对话时生效'
    emit('dirty', false)
    emit('saved')
  } catch (e) {
    error.value = String(e?.message || e)
  } finally {
    busy.value = false
  }
}

async function openDir() {
  await api.openDir('tools')
}

onMounted(load)
</script>

<template>
  <div class="wrap">
    <div class="path">
      文件：<code>{{ path }}</code>
      <button class="link" @click="openDir">打开目录</button>
      <button class="link" @click="load">刷新</button>
    </div>

    <div v-if="loading" class="hint">加载中…</div>

    <template v-else>
      <section>
        <div class="sec-title">Shell 命令执行</div>
        <label class="chip" :class="{ off: !shellEnabled }">
          <input type="checkbox" :checked="shellEnabled" @change="toggleShell" />
          <span>允许 agent 执行本机命令（默认关闭）</span>
        </label>
        <p class="tip">
          关闭时 agent <b>不能执行任何命令，也就没有「删除文件」的能力</b>，同时不知道自己
          实际处于哪个目录（容易把文件建到意料之外的位置）。开启后它能删文件、跑脚本、
          确认自身位置，代价是可以在本机执行任意命令 —— 请自行评估。
          改动<b>下次对话生效</b>（工具集是构建期装配的）。
        </p>
      </section>

      <section>
        <div class="sec-title">内置工具（{{ builtin.length }}）</div>
        <p class="tip">取消勾选即写入 deny 列表；不勾选任何一项等价于全部启用。</p>
        <div class="chips">
          <label v-for="t in builtin" :key="t.name" class="chip" :class="{ off: !checked[t.name] }">
            <input type="checkbox" :checked="checked[t.name]" @change="toggle(t.name)" />
            <span class="mono">{{ t.name }}</span>
          </label>
        </div>
      </section>

      <section>
        <div class="sec-title">白名单（allow）</div>
        <p class="tip">留空表示不限制；填了则只启用列出的工具，优先级高于上面的勾选。</p>
        <input v-model="allowText" placeholder="留空 = 不限制" />
      </section>

      <section>
        <div class="sec-title">MCP 服务器</div>
        <p class="tip">
          直接编辑 JSON。注意：<b>配置的服务器会在构建 agent 时立刻连接</b>，
          命令写错会导致启动变慢并刷错误日志，所以保留空对象最安全。
        </p>
        <textarea v-model="mcpText" rows="6" spellcheck="false"></textarea>
        <div v-if="mcp.length" class="mcp-list">已配置：{{ mcp.map((m) => m.name).join('、') }}</div>
      </section>

      <div class="foot">
        <span v-if="notice" class="notice">{{ notice }}</span>
        <button class="primary" :disabled="busy" @click="save">{{ busy ? '保存中…' : '保存' }}</button>
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

.path {
  font-size: 11.5px;
  color: var(--text-3);
  word-break: break-all;
}

.path code {
  background: var(--panel-2);
  padding: 1px 4px;
  border-radius: 3px;
}

.hint {
  padding: 18px 4px;
  color: var(--text-3);
  font-size: 13px;
}

section {
  display: flex;
  flex-direction: column;
  gap: 6px;
}

.sec-title {
  font-weight: 600;
  font-size: 13px;
}

.tip {
  margin: 0;
  font-size: 11.5px;
  color: var(--text-3);
  line-height: 1.7;
}

.chips {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
}

.chip {
  display: inline-flex;
  align-items: center;
  gap: 5px;
  padding: 3px 9px;
  border: 1px solid var(--border);
  border-radius: 999px;
  font-size: 11.5px;
  cursor: pointer;
  background: var(--panel-2);
}

.chip.off {
  opacity: 0.5;
  text-decoration: line-through;
}

.chip input {
  width: auto;
  margin: 0;
}

.mono {
  font-family: ui-monospace, Consolas, monospace;
}

textarea {
  width: 100%;
  font-family: ui-monospace, Consolas, monospace;
  font-size: 12px;
  line-height: 1.6;
  resize: vertical;
}

.mcp-list {
  font-size: 11.5px;
  color: var(--text-3);
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
}
</style>
