# fast-agent-ui · 前端

办公智能体的 Vue 3 + Vite 6 界面。**不是独立部署的应用** —— 构建产物输出到后端工程的
`src/main/resources/static`，由后端随 jar 一起提供，最终跑在 JavaFX `WebView` 里。

后端在同级的 [`../fast-agent`](../fast-agent/README.md)。

## 开发

后端要用**固定端口**启动，vite 已把 `/api` 代理到 `127.0.0.1:18080`：

```powershell
# 另开一个终端，在后端工程里
cd ..\fast-agent
mvn spring-boot:run "-Dspring-boot.run.arguments=--server.port=18080"

# 这里
npm install
npm run dev            # http://localhost:5173
```

浏览器访问 http://localhost:5173 ，用 `cjh / 123456` 登录。

浏览器里调试比打包快得多，不必反复跑 jpackage。**首次使用**要先在「配置中心 → 模型配置」
里添加模型并填 API Key。

## 构建

```powershell
npm run build          # 产出到 ../fast-agent/src/main/resources/static
```

注意 `vite.config.js` 里 `emptyOutDir` 是关掉的：带 safe-delete 钩子的环境会把递归删除
拦下来导致构建失败，清理旧产物由根目录的 `build.ps1` 负责。**直接 `npm run build` 不会清旧文件**，
多次改哈希后会留下旧 bundle（无害，但会被打进 jar），需要时手工删掉 `static/assets` 下的旧文件。

## 结构

```
src/
├── main.js                    挂载 + 全局错误上报 + ready 标记
├── App.vue                    顶层编排：登录 → 工作空间列表 → 会话
├── api/bridge.js              仅有的对外入口：api（方法）+ onEvent（流式事件）
├── utils/confirm.js           命令式确认框（替代 window.confirm）
├── views/
│   ├── LoginView.vue
│   ├── WorkspaceListView.vue
│   └── WorkspaceView.vue      左侧会话列表 + 聊天区 + 工作空间记录面板
└── components/
    ├── ChatPanel.vue          消息区 / 输入区（含模型选择）/ 流式渲染 + 耗时·token 元信息
    ├── ConfigCenter.vue       配置中心（五个页签）
    ├── ConfirmDialog.vue      全局确认框，配 utils/confirm.js 用
    └── config/
        ├── ModelTab.vue       模型增删改 + 默认模型 + 优先级排序
        ├── MemoryTab.vue      全局记忆（memory.md）
        ├── SkillsTab.vue      技能清单
        ├── ToolsTab.vue       内置工具开关 + MCP 服务器
        └── GeneralTab.vue     系统提示词 / 温度 / 上下文条数 / 数据位置
```

**改接口时只动 `bridge.js`**：`views` 与 `components` 只通过它调用后端，桌面版与浏览器调试
走的是同一套代码路径。

## 四个 WebView 相关的坑

1. **`window.confirm` 恒返回 false**：`WebEngine` 默认没有 confirm handler，`confirm()` 一律
   返回 false，于是 `if (!confirm(...)) return` 会**静默失效** —— 表现就是「点删除没反应」。
   **不要用 `window.confirm`**，改用 `utils/confirm.js` 的 `confirmDialog()`（Promise 化，
   浏览器与桌面行为一致，样式也统一）。壳里另有 `setConfirmHandler` 兜底。
2. **`e.isComposing` 不可信**：打包环境里它可能恒为 true，导致
   `if (e.key === 'Enter' && !e.isComposing)` 永远不成立 —— 表现就是「回车只换行、不发送」。
   `ChatPanel` 改为**自己维护组合状态**（`@compositionstart` / `@compositionend`），
   并额外认 `keyCode === 229` 识别输入法占用。
3. **流式读取必须用异步 XHR**：WebKit 禁止主线程同步 XHR，`bridge.js` 靠
   `readyState === 3` 增量读 `responseText` 拼 SSE 帧。
4. **加载完成 ≠ 挂载完成**：`main.js` 在 `mount()` 后打 `window.__FAST_AGENT_READY__`，
   打包探针（`fast-agent/tools/FxProbe.java`）轮询这个标记来判断界面是否真的起来了。
