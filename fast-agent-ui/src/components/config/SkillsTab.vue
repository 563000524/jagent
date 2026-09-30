<script setup>
import { onMounted, ref } from 'vue'
import { api } from '../../api/bridge'

const dir = ref('')
const skills = ref([])
const loading = ref(true)
const error = ref('')

async function load() {
  loading.value = true
  error.value = ''
  try {
    const r = await api.getSkills()
    dir.value = r.dir || ''
    skills.value = r.skills || []
  } catch (e) {
    error.value = String(e?.message || e)
  } finally {
    loading.value = false
  }
}

async function openDir() {
  await api.openDir('skills')
}

onMounted(load)
</script>

<template>
  <div class="wrap">
    <div class="path">
      目录：<code>{{ dir }}</code>
      <button class="link" @click="openDir">打开目录</button>
      <button class="link" @click="load">刷新</button>
    </div>

    <div v-if="loading" class="hint">加载中…</div>

    <div v-else-if="!skills.length" class="empty">
      <p>还没有技能。在技能目录下新建一个子目录，放入 <code>SKILL.md</code> 即可：</p>
      <pre>skills/
  my-skill/
    SKILL.md</pre>
      <pre>---
name: my-skill
description: 一句话说明这个技能解决什么问题、什么时候用
---

# 正文：具体步骤与规范</pre>
      <p class="tip">agent 根据 description 判断是否加载该技能；改动后下次对话生效。</p>
    </div>

    <table v-else class="list">
      <thead>
        <tr>
          <th style="width: 180px">技能</th>
          <th>描述</th>
        </tr>
      </thead>
      <tbody>
        <tr v-for="s in skills" :key="s.name">
          <td class="nm">{{ s.name }}</td>
          <td>
            <div>{{ s.description || '（无描述）' }}</div>
            <div class="sub">{{ s.dir }}</div>
          </td>
        </tr>
      </tbody>
    </table>

    <div v-if="skills.length" class="tip block">
      共 {{ skills.length }} 个技能。新增或修改目录文件后，重新打开对话即可生效。
    </div>

    <div v-if="error" class="error">⚠ {{ error }}</div>
  </div>
</template>

<style scoped>
.wrap {
  display: flex;
  flex-direction: column;
  gap: 12px;
}

.path {
  font-size: 11.5px;
  color: var(--text-3);
  word-break: break-all;
}

.path code,
.empty code {
  background: var(--panel-2);
  padding: 1px 4px;
  border-radius: 3px;
}

.hint {
  padding: 18px 4px;
  color: var(--text-3);
  font-size: 13px;
}

.empty p {
  font-size: 12.5px;
  color: var(--text-2);
  margin: 6px 0;
}

.empty pre {
  background: var(--panel-2);
  border: 1px solid var(--border);
  border-radius: 8px;
  padding: 10px 12px;
  font-size: 11.5px;
  line-height: 1.6;
  overflow-x: auto;
  margin: 0 0 10px;
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
  vertical-align: top;
}

.nm {
  font-weight: 600;
}

.sub {
  font-size: 11px;
  color: var(--text-3);
  word-break: break-all;
  margin-top: 2px;
}

.tip {
  font-size: 11.5px;
  color: var(--text-3);
  line-height: 1.7;
}

.tip.block {
  margin: 0;
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
