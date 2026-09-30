import { ref } from 'vue'
import { api } from '../api/bridge'

/**
 * 文件卡片上的三个动作，卡片本体与右侧预览抽屉共用一份。
 *
 * 抽出来是因为两处都要「另存为 / 打开 / 打开所在文件夹」，而复制一遍就会有两个
 * busy 状态、两份浏览器兜底逻辑 —— 迟早改漏一处。
 *
 * @param {() => object} getFile 取当前文件（用函数而不是对象：调用方换文件时不必重建）
 * @param {(msg: string) => void} notify 结果提示
 */
export function useFileActions(getFile, notify) {
  /** 正在执行的动作，避免连点弹两个对话框 */
  const busy = ref('')

  /**
   * 兜底：非桌面模式（浏览器里 `npm run dev`）走直链下载。
   *
   * 桌面壳的 WebKit 没有下载能力，直链点了没反应 —— 那种情况下后端会先返回
   * 可读的「不是桌面模式」提示，这里再退到浏览器下载。
   */
  function anchorDownload(file) {
    const a = document.createElement('a')
    a.href = api.fileDownloadUrl(file.id)
    a.download = file.name
    document.body.appendChild(a)
    a.click()
    a.remove()
  }

  async function act(kind) {
    const file = getFile()
    if (!file || busy.value) return
    busy.value = kind
    try {
      if (kind === 'save') {
        const r = await api.saveFileAs(file.id)
        if (r && r.cancelled) return
        notify(`已保存到 ${r.path}`)
      } else if (kind === 'open') {
        await api.openFile(file.id)
      } else {
        await api.revealFile(file.id)
      }
    } catch (e) {
      const msg = String(e?.message || e)
      if (kind === 'save' && msg.includes('桌面模式')) {
        anchorDownload(file)
        notify('已交给浏览器下载')
      } else {
        notify(msg)
      }
    } finally {
      busy.value = ''
    }
  }

  return { busy, act }
}
