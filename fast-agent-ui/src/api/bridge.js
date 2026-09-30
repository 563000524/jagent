/**
 * 桥接层：本机 HTTP 服务 + SSE 流式。所有请求自动带登录令牌。
 *
 * 对外只有 `api`（方法调用）与 `onEvent`（流式事件订阅）两个入口。
 */

const TOKEN_KEY = 'fastagent.token'
const JSON_HEADERS = { 'Content-Type': 'application/json' }

// ---------- 令牌 ----------

export function getToken() {
  try {
    return localStorage.getItem(TOKEN_KEY) || ''
  } catch {
    return ''
  }
}

export function setToken(token) {
  try {
    if (token) localStorage.setItem(TOKEN_KEY, token)
    else localStorage.removeItem(TOKEN_KEY)
  } catch {
    /* 隐私模式等场景忽略 */
  }
}

export function isLoggedIn() {
  return !!getToken()
}

/** 401 时广播，外层监听到就跳登录页 */
function broadcastUnauthorized() {
  window.dispatchEvent(new CustomEvent('fastagent:unauthorized'))
}

// ---------- 请求 ----------

async function req(method, path, body) {
  const headers = {}
  const token = getToken()
  if (token) headers['Authorization'] = 'Bearer ' + token
  if (body !== undefined) {
    Object.assign(headers, JSON_HEADERS)
  }
  const res = await fetch(path, {
    method,
    headers,
    body: body === undefined ? undefined : JSON.stringify(body)
  })
  const text = await res.text()
  if (res.status === 401) {
    setToken('')
    broadcastUnauthorized()
    throw new Error(extractMessage(text, '登录已过期，请重新登录'))
  }
  if (!res.ok) {
    throw new Error(extractMessage(text, `HTTP ${res.status}`))
  }
  return text ? JSON.parse(text) : null
}

/** 后端出错时返回 {"message": "..."}；解析不出来就退回原始文本 */
function extractMessage(text, fallback) {
  if (!text) return fallback
  try {
    const parsed = JSON.parse(text)
    return parsed.message || parsed.error || fallback
  } catch {
    return text.slice(0, 200) || fallback
  }
}

// ---------- 事件总线 ----------

const listeners = new Map()

/** 订阅流式事件，返回取消订阅函数 */
export function onEvent(name, handler) {
  if (!listeners.has(name)) listeners.set(name, new Set())
  listeners.get(name).add(handler)
  return () => listeners.get(name)?.delete(handler)
}

function emit(name, payload) {
  const set = listeners.get(name)
  if (!set) return
  set.forEach((fn) => {
    try {
      fn(payload)
    } catch (e) {
      logFrontend('error', `事件处理异常 ${name}: ${e?.message || e}`)
    }
  })
}

// ---------- 日志 ----------

export async function logFrontend(level, message) {
  const line = `[前端/${level}] ${message}`
  if (level === 'error') console.error(line)
  else if (level === 'warn') console.warn(line)
  else console.log(line)
  try {
    await fetch('/api/system/log', {
      method: 'POST',
      headers: { ...JSON_HEADERS, ...authHeader() },
      body: JSON.stringify({ level, message })
    })
  } catch {
    // 后端不可达时静默
  }
}

function authHeader() {
  const token = getToken()
  return token ? { Authorization: 'Bearer ' + token } : {}
}

// ---------- 流式对话 ----------

/**
 * 发起流式对话。
 *
 * 用异步 XHR 增量读 responseText：WebKit 禁止主线程同步 XHR，
 * 必须靠 readyState === 3 拿到逐步到达的数据。
 *
 * @param {string} [modelId] 本轮使用的模型；不传则后端按「会话上次用的 → 默认模型」解析
 * @returns {Promise<string>} 后端实际使用的 sessionId
 */
function sendStream(workspaceId, sessionId, text, modelId) {
  return new Promise((resolve, reject) => {
    const xhr = new XMLHttpRequest()
    xhr.open('POST', '/api/chat/stream', true)
    xhr.setRequestHeader('Content-Type', 'application/json')
    const token = getToken()
    if (token) xhr.setRequestHeader('Authorization', 'Bearer ' + token)

    let offset = 0
    let buffer = ''
    let settled = false

    const settleOk = (id) => {
      if (!settled) {
        settled = true
        resolve(id)
      }
    }

    const handleFrame = (frame) => {
      let event = 'message'
      let data = ''
      for (const line of frame.split('\n')) {
        if (line.startsWith('event:')) event = line.slice(6).trim()
        else if (line.startsWith('data:')) data += line.slice(5).trim()
      }
      if (!data) return
      let payload
      try {
        payload = JSON.parse(data)
      } catch {
        return
      }
      if (event === 'start') {
        settleOk(payload.sessionId)
        return
      }
      emit(`chat:${event}`, payload)
    }

    const drain = () => {
      const chunk = xhr.responseText.substring(offset)
      offset = xhr.responseText.length
      buffer += chunk.replace(/\r/g, '')
      let idx
      while ((idx = buffer.indexOf('\n\n')) >= 0) {
        const frame = buffer.slice(0, idx)
        buffer = buffer.slice(idx + 2)
        if (frame.trim()) handleFrame(frame)
      }
    }

    xhr.onreadystatechange = () => {
      if (xhr.readyState === 3) {
        drain()
      } else if (xhr.readyState === 4) {
        drain()
        if (xhr.status === 401) {
          setToken('')
          broadcastUnauthorized()
          if (!settled) {
            settled = true
            reject(new Error('登录已过期，请重新登录'))
          }
          return
        }
        if (xhr.status !== 200) {
          const msg = extractMessage(xhr.responseText, `HTTP ${xhr.status}`)
          if (!settled) {
            settled = true
            reject(new Error(msg))
          } else {
            emit('chat:error', { sessionId, message: msg })
          }
          return
        }
        settleOk(sessionId)
      }
    }

    xhr.onerror = () => {
      if (!settled) {
        settled = true
        reject(new Error('无法连接本机服务，请查看日志'))
      }
    }

    xhr.send(JSON.stringify({ workspaceId, sessionId, text, modelId: modelId || '' }))
  })
}

// ---------- AG-UI 流式对话 ----------

/**
 * 发起一轮 AG-UI run（POST /api/agui/run）。
 *
 * 与 sendStream 的两点关键差别：
 *  1. AG-UI 的 SSE 帧**只有 `data:` 行，没有 `event:` 行** —— 事件名在 JSON 的
 *     `type` 字段里（SCREAMING_SNAKE_CASE，如 TEXT_MESSAGE_CONTENT）；
 *  2. 请求体是 AG-UI 的 RunAgentInput 形态（threadId / runId / messages /
 *     forwardedProps），业务参数（workspaceId、modelId）走 forwardedProps 扩展点。
 *
 * 解析仍用异步 XHR 增量读 responseText：EventSource 不支持 POST body 与自定义头。
 * 事件按 `agui:<type>` 广播，例如 `agui:TEXT_MESSAGE_CONTENT`。
 *
 * @param {string} [modelId] 本轮使用的模型；不传则后端按「会话上次用的 → 默认模型」解析
 * @returns {Promise<string>} threadId（即会话 id）
 */
function sendAguiRun(workspaceId, threadId, text, modelId) {
  const runId = 'run-' + Date.now() + '-' + Math.random().toString(36).slice(2, 8)
  return new Promise((resolve, reject) => {
    const xhr = new XMLHttpRequest()
    xhr.open('POST', '/api/agui/run', true)
    xhr.setRequestHeader('Content-Type', 'application/json')
    const token = getToken()
    if (token) xhr.setRequestHeader('Authorization', 'Bearer ' + token)

    let offset = 0
    let buffer = ''
    let settled = false
    let sawRunStarted = false

    const settleOk = (id) => {
      if (!settled) {
        settled = true
        resolve(id)
      }
    }

    const handleFrame = (frame) => {
      let data = ''
      for (const line of frame.split('\n')) {
        // 只有 data: 行；以 ':' 开头的注释行（keep-alive）自然被跳过
        if (line.startsWith('data:')) data += line.slice(5).trim()
      }
      if (!data) return
      let payload
      try {
        payload = JSON.parse(data)
      } catch {
        return
      }
      if (!payload || !payload.type) return
      if (payload.type === 'RUN_STARTED') {
        // 后端可能把空 threadId 换成新建的会话 id，以事件里的为准
        sawRunStarted = true
        settleOk(payload.threadId || threadId)
      }
      emit('agui:' + payload.type, payload)
    }

    const drain = () => {
      const chunk = xhr.responseText.substring(offset)
      offset = xhr.responseText.length
      buffer += chunk.replace(/\r/g, '')
      let idx
      while ((idx = buffer.indexOf('\n\n')) >= 0) {
        const frame = buffer.slice(0, idx)
        buffer = buffer.slice(idx + 2)
        if (frame.trim()) handleFrame(frame)
      }
    }

    xhr.onreadystatechange = () => {
      if (xhr.readyState === 3) {
        drain()
      } else if (xhr.readyState === 4) {
        drain()
        if (xhr.status === 401) {
          setToken('')
          broadcastUnauthorized()
          if (!settled) {
            settled = true
            reject(new Error('登录已过期，请重新登录'))
          }
          return
        }
        if (xhr.status !== 200) {
          const msg = extractMessage(xhr.responseText, `HTTP ${xhr.status}`)
          if (!settled) {
            settled = true
            reject(new Error(msg))
          } else {
            emit('agui:RUN_ERROR', { threadId, message: msg })
          }
          return
        }
        // 一个事件都没收到就结束了（连通但没产出）：当作失败，别让界面一直转圈
        if (!sawRunStarted && !settled) {
          settled = true
          reject(new Error('服务端没有返回任何事件，请查看日志'))
          return
        }
        settleOk(threadId)
      }
    }

    xhr.onerror = () => {
      if (!settled) {
        settled = true
        reject(new Error('无法连接本机服务，请查看日志'))
      }
    }

    xhr.send(
      JSON.stringify({
        threadId: threadId || '',
        runId,
        text,
        // 标准 AG-UI 客户端形态：后端优先读 text，读不到才从 messages 里取最后一条 user 消息
        messages: [{ id: 'msg-' + runId, role: 'user', content: text }],
        forwardedProps: { workspaceId, modelId: modelId || '' }
      })
    )
  })
}

// ---------- 对外 API ----------

export const api = {
  // 登录
  login: (username, password) => req('POST', '/api/auth/login', { username, password }),
  logout: () => req('POST', '/api/auth/logout'),
  me: () => req('GET', '/api/auth/me'),

  // 工作空间
  // directory 是用户指定的目录，agent 的数据落在该目录下的 .workspace
  listWorkspaces: () => req('GET', '/api/workspaces'),
  createWorkspace: (name, directory, description) =>
    req('POST', '/api/workspaces', { name, directory, description }),
  getWorkspace: (id) => req('GET', `/api/workspaces/${id}`),
  /** purge=true 时额外删掉目录下的 .workspace（用户自己的文件不动） */
  deleteWorkspace: (id, purge = false) => req('DELETE', `/api/workspaces/${id}?purge=${purge}`),
  /** 用文件管理器打开该工作空间的数据目录 */
  openWorkspaceDir: (id) => req('POST', `/api/workspaces/${id}/open-dir`),
  /** 工作空间级记录：MEMORY.md / AGENTS.md / memory/*.md */
  workspaceRecords: (id) => req('GET', `/api/workspaces/${id}/records`),

  // 会话（工作空间下）
  listSessions: (wid) => req('GET', `/api/workspaces/${wid}/sessions`),
  createSession: (wid) => req('POST', `/api/workspaces/${wid}/sessions`),
  deleteSession: (wid, sid) => req('DELETE', `/api/workspaces/${wid}/sessions/${sid}`),
  getMessages: (wid, sid) => req('GET', `/api/workspaces/${wid}/sessions/${sid}/messages`),
  /** 记录该会话使用的模型，下次进入即恢复；传空表示回到默认模型 */
  setSessionModel: (wid, sid, modelId) =>
    req('POST', `/api/workspaces/${wid}/sessions/${sid}/model`, { modelId: modelId || '' }),

  // 对话
  sendStream,
  /** AG-UI 协议通道：事件按 `agui:<TYPE>` 广播 */
  sendAguiRun,
  stopStream: (workspaceId, sessionId) => req('POST', '/api/chat/stop', { workspaceId, sessionId }),
  getStreamSnapshot: (workspaceId, sessionId) =>
    req('GET', `/api/chat/snapshot?workspaceId=${encodeURIComponent(workspaceId)}&sessionId=${encodeURIComponent(sessionId)}`),

  // 交付给用户的文件（模型调用 deliver_artifact 时落盘）
  /** 某会话交付过的文件，用于渲染下载卡片 */
  listFiles: (workspaceId, sessionId) =>
    req(
      'GET',
      `/api/files?workspaceId=${encodeURIComponent(workspaceId)}&sessionId=${encodeURIComponent(sessionId)}`
    ),
  /**
   * 另存为：后端弹系统保存框，用户选好路径后复制过去。
   * 返回 `{path, cancelled}`；取消时 path 是空串。
   *
   * WebView 没有下载能力，桌面态只能走这条路（见后端 FileController 注释）。
   */
  saveFileAs: (id) => req('POST', `/api/files/${encodeURIComponent(id)}/save-as`),
  /** 用系统默认程序打开 */
  openFile: (id) => req('POST', `/api/files/${encodeURIComponent(id)}/open`),
  /** 打开所在文件夹并选中 */
  revealFile: (id) => req('POST', `/api/files/${encodeURIComponent(id)}/reveal`),
  /**
   * 直链下载地址（拼上令牌）。只在浏览器里调试时有用：
   * 桌面壳的 WebKit 会静默丢弃下载请求，存不了文件。
   */
  fileDownloadUrl: (id) =>
    `/api/files/${encodeURIComponent(id)}?token=${encodeURIComponent(getToken())}`,
  /**
   * 内联地址（右侧预览用）：图片可以直接塞进 `<img src>`，文本 fetch 回来渲染。
   * 同样把令牌拼在查询串上 —— WebView 里给 `<img>` 加不了请求头。
   */
  fileRawUrl: (id) =>
    `/api/files/${encodeURIComponent(id)}/raw?token=${encodeURIComponent(getToken())}`,

  // 系统
  getHealth: () => req('GET', '/api/system/health'),
  openLogDir: async () => (await req('POST', '/api/system/open-log-dir')).dir,
  /**
   * 弹系统「选择文件夹」对话框，返回 `{dir, cancelled}`。
   * 非桌面模式（浏览器里调试前端）会抛错，那种情况下让用户手填路径。
   */
  pickDirectory: (initialDir) =>
    req('POST', '/api/system/pick-directory', { initialDir: initialDir || '' }),
  /** 打开某个配置目录；name 取值见后端 SystemController */
  openDir: (name) => req('POST', '/api/system/open-dir', { name }),

  // 通用设置（系统提示词 / 温度 / 上下文条数）
  getConfig: () => req('GET', '/api/config'),
  saveConfig: (cfg) => req('POST', '/api/config', cfg),

  // 模型配置：读写 .fastagent/models.json
  // 注意 id 走查询参数 —— 模型 id 里通常带斜杠（deepseek-ai/DeepSeek-V4-Flash），
  // 放进路径会被服务端拦掉
  listModels: () => req('GET', '/api/models'),
  saveModels: (models) => req('PUT', '/api/models', models),
  /**
   * 新增或更新一条。
   *
   * @param {object} m 模型数据
   * @param {string} [originalId] 编辑时传「本条原本的 id」——模型名可改，
   *        改完必须仍是同一条记录，不传会变成新增一条
   */
  upsertModel: (m, originalId) =>
    req('POST', originalId ? `/api/models?originalId=${encodeURIComponent(originalId)}` : '/api/models', m),
  deleteModel: (id) => req('DELETE', `/api/models?id=${encodeURIComponent(id)}`),
  activateModel: (id) => req('POST', `/api/models/activate?id=${encodeURIComponent(id)}`),
  /** 调整优先级：数组顺序即优先级。只传 id 顺序，不碰其他字段 */
  reorderModels: (ids) => req('POST', '/api/models/reorder', ids),

  // 全局资产
  getPaths: () => req('GET', '/api/global/paths'),
  getGlobalMemory: () => req('GET', '/api/global/memory'),
  saveGlobalMemory: (content) => req('PUT', '/api/global/memory', { content }),
  getSkills: () => req('GET', '/api/global/skills'),
  getTools: () => req('GET', '/api/global/tools'),
  saveTools: (cfg) => req('PUT', '/api/global/tools', cfg)
}
