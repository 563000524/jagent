<script setup>
import { computed } from 'vue'
import MarkdownBlock from './MarkdownBlock.vue'
import { highlight, langOf } from '../utils/highlight'

const props = defineProps({
  /** 文件全文 */
  text: { type: String, default: '' },
  /** 文件名（用来认语言 / 判断 md、csv） */
  name: { type: String, default: '' }
})

const ext = computed(() => {
  const s = String(props.name || '')
  const i = s.lastIndexOf('.')
  return i > 0 ? s.slice(i + 1).toLowerCase() : ''
})

const isMarkdown = computed(() => ext.value === 'md' || ext.value === 'markdown')
const isDelimited = computed(() => ['csv', 'tsv'].includes(ext.value))

/** CSV/TSV：解析成表。超过 500 行只显示前 500 行 —— 再多也没人往下翻，还会拖慢渲染 */
const CSV_MAX_ROWS = 500

const table = computed(() => {
  if (!isDelimited.value) {
    return null
  }
  const sep = ext.value === 'tsv' ? '\t' : ','
  const rows = String(props.text)
    .split(/\r?\n/)
    .filter((l) => l.trim() !== '')
    .slice(0, CSV_MAX_ROWS)
    .map((line) => splitCsvLine(line, sep))
  return rows.length ? rows : null
})

const truncatedRows = computed(() => {
  if (!isDelimited.value) return false
  return String(props.text).split(/\r?\n/).filter((l) => l.trim() !== '').length > CSV_MAX_ROWS
})

/** 极简 CSV 切分：支持双引号包裹（含逗号）与 "" 转义 */
function splitCsvLine(line, sep) {
  const out = []
  let cell = ''
  let quoted = false
  for (let i = 0; i < line.length; i++) {
    const ch = line[i]
    if (quoted) {
      if (ch === '"') {
        if (line[i + 1] === '"') {
          cell += '"'
          i++
        } else {
          quoted = false
        }
      } else {
        cell += ch
      }
    } else if (ch === '"') {
      quoted = true
    } else if (ch === sep) {
      out.push(cell)
      cell = ''
    } else {
      cell += ch
    }
  }
  out.push(cell)
  return out
}

/** JSON：先格式化再高亮（一行几万字符的 JSON 谁也读不了） */
const prettyJson = computed(() => {
  if (ext.value !== 'json') {
    return props.text
  }
  try {
    return JSON.stringify(JSON.parse(props.text), null, 2)
  } catch (e) {
    // 不是合法 JSON（例如 JSONL / 片段）就按原文显示
    return props.text
  }
})

const codeHtml = computed(() => highlight(prettyJson.value, langOf(props.name)))
</script>

<template>
  <MarkdownBlock v-if="isMarkdown" :text="text" class="md" />

  <div v-else-if="isDelimited && table" class="table-wrap">
    <table>
      <tbody>
        <tr v-for="(row, ri) in table" :key="ri">
          <td v-for="(cell, ci) in row" :key="ci" :class="{ head: ri === 0 }">{{ cell }}</td>
        </tr>
      </tbody>
    </table>
    <p v-if="truncatedRows" class="more">仅显示前 {{ CSV_MAX_ROWS }} 行</p>
  </div>

  <pre v-else class="code"><code class="hljs" v-html="codeHtml"></code></pre>
</template>

<style scoped>
.md {
  font-size: 13px;
}

/* 代码块：与 GitHub 观感一致（底色与配色来自全局引入的 highlight.js 主题） */
.code {
  margin: 0;
  border: 1px solid var(--border);
  border-radius: 8px;
  overflow: auto;
  font-family: ui-monospace, SFMono-Regular, Menlo, Consolas, monospace;
  font-size: 12px;
  line-height: 1.65;
}

.code code {
  display: block;
  padding: 12px 14px;
  white-space: pre;
}

.table-wrap {
  overflow: auto;
}

table {
  border-collapse: collapse;
  font-size: 12px;
  width: 100%;
}

td {
  border: 1px solid var(--border);
  padding: 4px 8px;
  white-space: nowrap;
  max-width: 220px;
  overflow: hidden;
  text-overflow: ellipsis;
  color: var(--text-2);
}

td.head {
  background: var(--panel-2);
  font-weight: 600;
  color: var(--text);
  position: sticky;
  top: 0;
}

.more {
  margin: 8px 0 0;
  font-size: 11.5px;
  color: var(--text-3);
}
</style>
