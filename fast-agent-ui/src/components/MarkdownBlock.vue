<script setup>
import { computed } from 'vue'
import MarkdownIt from 'markdown-it'

/**
 * Markdown 正文渲染块。
 *
 * <p><b>为什么单独抽成组件</b>：渲染结果必须是「可缓存的计算属性」。
 * 父组件（ChatPanel）在一次流式回答里会重渲染几百次（每个 token 一次），
 * 而它的模板里遍历的是**整个会话的所有消息**。若把 `md.render()` 直接写在
 * 父组件模板的属性表达式里，每条历史回答都会被反复重新解析
 * （几十条 × 每条几 KB × 每秒几十次）—— 主线程会被占满，页面卡到点不动。
 * 放进子组件后，`props.text` 不变 ⇒ `computed` 不重算 ⇒ 已成型的历史消息零开销，
 * 每次流式重渲染只更新「正在变的那一条」。
 *
 * `html: false` 是安全底线：模型输出里的原始 HTML 标签一律转义，只有 Markdown
 * 语法会被解析，因此 v-html 不会引入注入面。
 * `breaks: true` 让单个换行也生效（模型经常不写空行）。
 * `linkify: true` 让裸 URL 成链接。
 */
const md = new MarkdownIt({
  html: false,
  linkify: true,
  breaks: true
})

const props = defineProps({
  text: { type: String, default: '' }
})

const html = computed(() => md.render(String(props.text || '')))

/**
 * 正文里渲染出的链接**一律阻止跳转**。
 *
 * <p>WebView 一旦导航到外部地址，整个单页应用就被那个网页替换掉了 —— 界面直接
 * 消失且无法返回（只能 Ctrl+R 重载）。这里只阻止默认行为，链接作为文字展示；
 * 将来若要「点开系统浏览器」，在这里改为调用后端打开即可。
 */
function onClick(e) {
  const a = e.target && e.target.closest ? e.target.closest('a') : null
  if (a) e.preventDefault()
}
</script>

<template>
  <div class="text" v-html="html" @click="onClick"></div>
</template>

<style scoped>
/* v-html 插入的节点**不会**带 scoped 的 data-v 属性，所以所有后代选择器都必须
   写成 :deep(...)，否则会被编译成 `.text p[data-v-x]` 而永不匹配 ——
   表现就是「Markdown 渲染出来了但一点样式都没有」。 */

.text {
  line-height: 1.7;
  word-break: break-word;
}

.text :deep(p) {
  margin: 0 0 8px;
}

/* 首尾元素不留外边距，免得气泡上下多出空隙 */
.text :deep(p:first-child) {
  margin-top: 0;
}

.text :deep(p:last-child) {
  margin-bottom: 0;
}

.text :deep(h1),
.text :deep(h2),
.text :deep(h3),
.text :deep(h4),
.text :deep(h5),
.text :deep(h6) {
  margin: 14px 0 8px;
  line-height: 1.4;
}

.text :deep(h1) {
  font-size: 18px;
}

.text :deep(h2) {
  font-size: 16.5px;
}

.text :deep(h3) {
  font-size: 15px;
}

.text :deep(h4),
.text :deep(h5),
.text :deep(h6) {
  font-size: 14px;
}

.text :deep(ul),
.text :deep(ol) {
  margin: 0 0 8px;
  padding-left: 22px;
}

.text :deep(li) {
  margin: 2px 0;
}

/* 嵌套列表不再额外留白 */
.text :deep(li > ul),
.text :deep(li > ol) {
  margin-bottom: 0;
}

.text :deep(blockquote) {
  margin: 8px 0;
  padding: 2px 0 2px 10px;
  border-left: 3px solid var(--border);
  color: var(--text-2);
}

.text :deep(hr) {
  border: none;
  border-top: 1px solid var(--border);
  margin: 12px 0;
}

.text :deep(a) {
  color: var(--primary);
  text-decoration: none;
}

.text :deep(a):hover {
  text-decoration: underline;
}

/* 行内代码 */
.text :deep(code) {
  background: #f6f8fb;
  border: 1px solid var(--border);
  border-radius: 4px;
  padding: 0 4px;
  font-family: Consolas, "Courier New", monospace;
  font-size: 12.5px;
}

/* 代码块：markdown-it 输出 pre > code，要把行内代码的样子脱掉 */
.text :deep(pre) {
  background: #f6f8fb;
  border: 1px solid var(--border);
  border-radius: 8px;
  padding: 10px 12px;
  margin: 8px 0;
  font-family: Consolas, "Courier New", monospace;
  font-size: 12.5px;
  line-height: 1.6;
  white-space: pre-wrap;
  overflow-x: auto;
}

.text :deep(pre code) {
  background: none;
  border: none;
  border-radius: 0;
  padding: 0;
  font-size: inherit;
}

/* 表格：内容可能很宽，允许横向滚动而不是把气泡撑破 */
.text :deep(table) {
  border-collapse: collapse;
  margin: 8px 0;
  font-size: 13px;
  display: block;
  overflow-x: auto;
}

.text :deep(th),
.text :deep(td) {
  border: 1px solid var(--border);
  padding: 5px 9px;
  text-align: left;
}

.text :deep(th) {
  background: #f6f8fb;
  font-weight: 600;
}
</style>
