import hljs from 'highlight.js/lib/core'

import bash from 'highlight.js/lib/languages/bash'
import cpp from 'highlight.js/lib/languages/cpp'
import csharp from 'highlight.js/lib/languages/csharp'
import css from 'highlight.js/lib/languages/css'
import go from 'highlight.js/lib/languages/go'
import ini from 'highlight.js/lib/languages/ini'
import java from 'highlight.js/lib/languages/java'
import javascript from 'highlight.js/lib/languages/javascript'
import json from 'highlight.js/lib/languages/json'
import kotlin from 'highlight.js/lib/languages/kotlin'
import lua from 'highlight.js/lib/languages/lua'
import markdown from 'highlight.js/lib/languages/markdown'
import php from 'highlight.js/lib/languages/php'
import properties from 'highlight.js/lib/languages/properties'
import python from 'highlight.js/lib/languages/python'
import ruby from 'highlight.js/lib/languages/ruby'
import rust from 'highlight.js/lib/languages/rust'
import sql from 'highlight.js/lib/languages/sql'
import typescript from 'highlight.js/lib/languages/typescript'
import xml from 'highlight.js/lib/languages/xml'
import yaml from 'highlight.js/lib/languages/yaml'

/**
 * 代码高亮：只用 hljs 的 core + 按需注册语言。
 *
 * 为什么不用整包：全语言版打包后接近 1MB，而这个桌面应用只需要「看得懂」——
 * 上面这二十来种覆盖了日常会遇到的全部文件类型，体积只有几十 KB。
 *
 * 主题样式在 main.js 里全局引入（highlight.js/styles/github.css）：
 * 高亮结果是 v-html 进去的，scoped 样式管不到它。
 */
const LANGS = {
  bash, cpp, csharp, css, go, ini, java, javascript, json, kotlin, lua,
  markdown, php, properties, python, ruby, rust, sql, typescript, xml, yaml
}

let registered = false

function register() {
  if (registered) {
    return
  }
  registered = true
  Object.entries(LANGS).forEach(([name, def]) => hljs.registerLanguage(name, def))
}

/** 扩展名 → hljs 语言名 */
const LANG_BY_EXT = {
  java: 'java',
  js: 'javascript', mjs: 'javascript', cjs: 'javascript', jsx: 'javascript',
  ts: 'typescript', tsx: 'typescript',
  vue: 'xml', html: 'xml', htm: 'xml', svg: 'xml', xml: 'xml',
  json: 'json',
  yml: 'yaml', yaml: 'yaml',
  md: 'markdown', markdown: 'markdown',
  py: 'python', rb: 'ruby', go: 'go', rs: 'rust', php: 'php',
  c: 'cpp', cpp: 'cpp', h: 'cpp', hpp: 'cpp', cs: 'csharp',
  kt: 'kotlin', lua: 'lua', gradle: 'java', swift: 'java',
  sql: 'sql',
  css: 'css', scss: 'css', less: 'css',
  sh: 'bash', bat: 'bash', ps1: 'bash',
  ini: 'ini', conf: 'ini', cfg: 'ini', toml: 'ini', env: 'properties',
  properties: 'properties'
}

function extOf(name) {
  const s = String(name || '')
  const i = s.lastIndexOf('.')
  return i > 0 && i < s.length - 1 ? s.slice(i + 1).toLowerCase() : ''
}

export function langOf(name) {
  return LANG_BY_EXT[extOf(name)] || ''
}

function escapeHtml(s) {
  return String(s)
    .replace(/&/g, '&amp;')
    .replace(/</g, '&lt;')
    .replace(/>/g, '&gt;')
}

/**
 * 高亮一段文本，返回可直接 v-html 的 HTML。
 *
 * 认不出语言或高亮内部出错时退回纯转义文本 —— 预览而已，绝不能因此白屏。
 */
export function highlight(text, lang) {
  const src = String(text == null ? '' : text)
  if (!lang) {
    return escapeHtml(src)
  }
  register()
  try {
    if (!hljs.getLanguage(lang)) {
      return escapeHtml(src)
    }
    return hljs.highlight(src, { language: lang, ignoreIllegals: true }).value
  } catch (e) {
    return escapeHtml(src)
  }
}
