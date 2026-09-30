import { createApp } from 'vue'
import App from './App.vue'
import './style.css'
// 代码高亮的配色主题。必须在全局引（不能放进组件的 scoped 样式）：
// 高亮结果是 v-html 注入的节点，scoped 选择器加不上作用域属性，永远匹配不到
import 'highlight.js/styles/github.css'
import { logFrontend } from './api/bridge'

const app = createApp(App)

// 全部异常都写进 exe 同目录的日志，杜绝「界面没反应却查不到原因」
app.config.errorHandler = (err, instance, info) => {
  logFrontend('error', `Vue 运行时错误 [${info}]: ${err?.message || err}`)
}
window.addEventListener('error', (e) => {
  logFrontend('error', `window.onerror: ${e?.message || e}`)
})
window.addEventListener('unhandledrejection', (e) => {
  logFrontend('error', `unhandledrejection: ${e?.reason?.message || e?.reason}`)
})

app.mount('#app')

// 挂载完成标记：无窗口探针靠它判断「前端是否真的起来了」。
// 比等 load 事件可靠——模块脚本是异步执行的，load 完成时可能还没挂载。
window.__FAST_AGENT_READY__ = true
logFrontend('info', `前端已挂载 href=${location.href} ua=${navigator.userAgent}`)
