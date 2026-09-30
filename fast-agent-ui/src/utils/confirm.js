import { reactive } from 'vue'

/**
 * 命令式确认框，替代 `window.confirm`。
 *
 * <p>为什么不用 `window.confirm`：JavaFX WebView 里 **`confirm()` 恒返回 false**
 * （`WebEngine` 默认没设 confirm handler），于是 `if (!confirm(...)) return`
 * 会静默失效 —— 表现就是「点了删除没反应」。自实现还顺带保证了
 * 浏览器调试与桌面端行为一致，样式也跟项目统一。
 *
 * 用法：
 * ```js
 * import { confirmDialog } from '../utils/confirm'
 * if (!(await confirmDialog('删除这条模型？'))) return
 * ```
 */
const state = reactive({
  visible: false,
  title: '',
  message: '',
  detail: '',
  confirmText: '确定',
  cancelText: '取消',
  danger: true,
  /** 当前等待结果的 resolve，同一时刻只允许一个确认框 */
  resolver: null
})

/**
 * 弹一个确认框。
 *
 * @param {string} message 主文案
 * @param {{title?: string, detail?: string, confirmText?: string, cancelText?: string, danger?: boolean}} [options]
 * @returns {Promise<boolean>} 确认为 true，取消/关闭为 false
 */
export function confirmDialog(message, options = {}) {
  // 上一个没答完就被新的顶掉：当作「取消」，避免 Promise 永远悬着
  if (state.resolver) {
    const prev = state.resolver
    state.resolver = null
    prev(false)
  }
  state.title = options.title || '请确认'
  state.message = message || ''
  state.detail = options.detail || ''
  state.confirmText = options.confirmText || '确定'
  state.cancelText = options.cancelText || '取消'
  state.danger = options.danger !== false
  state.visible = true
  return new Promise((resolve) => {
    state.resolver = resolve
  })
}

/** 供 ConfirmDialog 组件使用 */
export function confirmState() {
  return state
}

export function answerConfirm(value) {
  state.visible = false
  const resolve = state.resolver
  state.resolver = null
  if (resolve) resolve(!!value)
}
