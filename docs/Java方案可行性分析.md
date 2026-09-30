# Go → Java 改造可行性分析

> 现状：Go + Vue3 + Wails，产物 `fast-agent.exe` 12MB
> 诉求：后端换成 Java，Vue3 作壳，仍打包成 exe
> 结论：**可行，但不能 1:1 平移**，体积/启动/内存会差一个数量级，需按新方案重新取舍

> **本文是动工前的选型分析**，下面第 3 节的目录是当时的方案草图（`com.example.fastagent`）。
> 实际落地后的布局见 [办公智能体概要设计](办公智能体概要设计.md) 与仓库根 README：
> 前后端已拆为平级的 `fast-agent/`（后端）与 `fast-agent-ui/`（前端），
> 系统数据在两者同级的 `.fastagent/`，一键构建脚本是根目录的 `build.ps1`。

> **实践反馈（方案已按路线 A 落地）** —— 补充两点本文当时未预见的经验：
>
> ① 实测数据与预估吻合：产物体积 **171MB**（其中 `javafx-web` 的 WebKit 内核单独占 32MB）、
> Spring 容器启动约 **1.6s**、冷启动到窗口约 2~3s。
>
> ② 中途曾改为**纯 JavaFX 原生控件**（去掉 Vue 与 WebView，体积降到 132MB，界面全部手写），
> 功能跑通后**回退到 WebView 方案** —— 因为 Markdown 表格、富文本排版、代码高亮、公文模板
> 这些后续需求在原生控件里都得自己写渲染器，而 HTML/CSS 侧是现成生态。
> **选型时要把「未来半年的排版类需求」算进去，不能只看当前界面的复杂度。**

---

## 1. 结论先行

| 判断 | 说明 |
| --- | --- |
| 能不能改 | **能**。三条路线都验证过工具链可用 |
| 最推荐 | **JavaFX WebView + Spring Boot + jpackage**（纯 Java 栈，用户熟悉，官方打包工具） |
| 最需注意 | Java **默认产不出「单个 exe 文件」**——`jpackage` 输出的是「exe + runtime 目录」，要单文件必须装 WiX 打安装包 |
| 体积代价 | 12MB → **90~150MB**（约 10 倍） |
| 启动代价 | 0.5s → **2~4s**（Spring Boot + JVM） |
| 内存代价 | ~40MB → **300MB+** |
| 前端改动 | 必须改：Wails 的 `window.go` 绑定 + 事件推送 → 换成标准 HTTP REST + SSE（**改完反而更通用、更好调试**） |

---

## 2. 三条路线对比

| 维度 | A. JavaFX WebView<br>（推荐） | B. SWT + Edge WebView2 | C. Electron + Java 子进程 |
| --- | --- | --- | --- |
| 渲染内核 | JavaFX 自带 WebKit<br>（JavaFX 25 = WebKit 620.1 ≈ Safari 18） | **系统 WebView2**<br>（Edge Chromium，最新） | Electron 自带 Chromium |
| 是否依赖系统组件 | 否（自带内核） | 是（Win10 1809+ 自带） | 否 |
| 产物体积 | 100~150MB | **40~60MB** | 220MB+ |
| 启动耗时 | 2~4s | 2~4s | 1~2s + 子进程握手 |
| 内存占用 | 250~400MB | 150~250MB | 400MB+ |
| 打包工具 | `jpackage`（JDK 官方） | `jpackage`（JDK 官方） | electron-builder |
| 进程模型 | 单进程（WebKit 在 JVM 内） | 单进程 | **双进程**（Electron + java） |
| 打包复杂度 | 低 | 中（SWT 平台包 + 显示库） | 高（进程守护、端口协商、退出清理） |
| Web 兼容性 | 好（Safari 18 级） | 最好（Chromium 最新） | 最好 |
| 风险 | WebView **无 DevTools**，调试要外挂浏览器 | SWT 生态老旧，`SWT.EDGE` 偶有兼容问题 | 体积与体验不匹配 |
| 适用 | 内网办公、体积不敏感 | 追求体积接近 Wails | 需要复杂前端能力 |

> **内核版本核实**：JavaFX 24 = WebKit 619.1，JavaFX 25 = WebKit 620.1（≈ Safari 18，2024 年末水平）。
> 现代 CSS（flex gap / grid / `:is()` / 容器查询 / `backdrop-filter`）与 ES2021 均支持，**Vue3 + Vite 产物可直接跑**，把 vite `build.target` 设为 `es2018` 更保险。
> 已知短板：**不支持 WebGL**（本项目用不到）。

---

## 3. 推荐方案（A）落地结构

### 3.1 架构

```
┌─────────────── 单个 JVM 进程 ────────────────┐
│                                              │
│  JavaFX WebView（自带 WebKit 620）           │
│         │ 加载 http://127.0.0.1:<随机端口>    │
│         ▼                                    │
│  Spring Boot（内嵌 Tomcat）                  │
│   ├─ REST  /api/config /api/sessions ...     │
│   ├─ SSE   /api/chat/stream  ← 流式回答       │
│   └─ Service  会话存储 / LLM 客户端            │
│                                              │
└──────────────────────────────────────────────┘
        │ HTTPS
        ▼
   大模型服务 API
```

**关键决策：前端与后端之间用 HTTP，不用 JNI/JSObject 桥。**
- 规避 `file://` 下 ES module 的 CORS 限制
- 前端退化成标准 Web 应用 → **浏览器里就能开发调试**，比 Wails 模式更好用
- 后端端口用 `0`（系统分配）防止冲突，启动完成后把真实端口交给 WebView

### 3.2 目录

```
fast-agent-java/
├── pom.xml
├── src/main/java/com/example/fastagent/
│   ├── Launcher.java            # 非 Application 子类的入口（jpackage 硬要求）
│   ├── AgentApplication.java    # Spring Boot 启动类
│   ├── ui/DesktopShell.java     # JavaFX WebView 窗口
│   ├── controller/
│   │   ├── ChatController.java  # SSE 流式接口
│   │   └── SessionController.java
│   ├── service/{ChatService,LlmClient}.java
│   └── config/{AppConfig,AppProperties}.java
├── src/main/resources/
│   ├── static/                  # Vue 构建产物（vite outDir 指向这里）
│   └── application.yml
├── frontend/                    # 复用现有 Vue3 工程，仅替换 bridge.js
└── package/build.ps1            # jlink + jpackage 脚本
```

### 3.3 三个必须写对的地方

**① 入口类不能用 `Application` 子类**（jpackage 限制）：

```java
public class Launcher {
    public static void main(String[] args) {
        SpringApplication app = new SpringApplication(AgentApplication.class);
        app.setWebApplicationType(WebApplicationType.SERVLET);
        app.run(args);                    // 先起后端
        DesktopShell.launch(DesktopShell.class, args);   // 再开窗口
    }
}
```

**② 等后端就绪再显示窗口**，否则白屏：

```java
@EventListener(ApplicationReadyEvent.class)
public void onReady(ApplicationReadyEvent e) {
    this.port = ((ServletWebServerApplicationContext) e.getApplicationContext())
                    .getWebServer().getPort();
    // 通过静态变量把端口传给 JavaFX
}
```

**③ 流式回答用 SSE**（对应现有 `chat:delta/done/error` 三个事件）：

```java
@PostMapping(value = "/api/chat/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
public SseEmitter stream(@RequestBody ChatRequest req) {
    SseEmitter emitter = new SseEmitter(10 * 60 * 1000L);
    executor.execute(() -> {
        llmClient.stream(req, delta -> {
            emitter.send(SseEmitter.event().name("delta").data(delta));  // 对应 chat:delta
        });
        emitter.send(SseEmitter.event().name("done").data(result));
        emitter.complete();
    });
    return emitter;
}
```

### 3.4 打包流程

```powershell
# 1. 前端
npm --prefix frontend run build          # 产物进 src/main/resources/static

# 2. 后端
mvn -DskipTests package                  # 出 fast-agent.jar

# 3. 精简运行时（关键：含 javafx.web）
jlink --add-modules java.base,java.logging,java.sql,java.desktop,java.naming,\
java.management,javafx.controls,javafx.web --output runtime --strip-debug --no-header-files

# 4. 打包
jpackage --type app-image --name fast-agent `
  --input target --main-jar fast-agent.jar --main-class com.example.fastagent.Launcher `
  --runtime-image runtime `
  --java-options "--enable-native-access=javafx.graphics,javafx.web" `
  --icon app.ico
```

| 命令 | 产物 | 前置条件 |
| --- | --- | --- |
| `jpackage --type app-image` | `fast-agent/fast-agent.exe` + `runtime/`（**目录**） | 无 |
| `jpackage --type exe` | 单个 `.exe` 安装包 | **需装 WiX 3.x**（本机未安装） |
| `jpackage --type msi` | `.msi` 安装包 | 需装 WiX |

---

## 4. 改动清单

| 模块 | 现状（Go） | 改造后（Java） | 工作量 |
| --- | --- | --- | --- |
| 窗口壳 | Wails | JavaFX WebView | 小（~80 行） |
| HTTP 服务 | 无（Wails IPC） | Spring Boot REST + SSE | 中 |
| LLM 客户端 | `internal/llm/client.go`（SSE 解析） | Java 版 SSE 客户端 | 中（~200 行） |
| 会话存储 | JSON 文件 + RWMutex | 同逻辑，`ConcurrentHashMap` + Jackson | 小 |
| 配置 | YAML（config.go） | Spring `@ConfigurationProperties` | 小 |
| 日志 | `internal/logger`（exe 同目录） | Logback + 自定义 appender 写 exe 目录 | 小 |
| **前端 bridge** | `window.go.main.App.*` + `EventsOn` | `fetch('/api/*')` + `EventSource('/api/chat/stream')` | **中，必改** |
| Vue 组件 | — | 基本不动 | 无 |

**总代码量**：Go 侧约 600 行 → Java 侧约 900~1100 行（Spring 样板代码多）+ 前端 bridge 重写约 150 行。

---

## 5. 风险与坑（提前排雷）

| 风险 | 影响 | 应对 |
| --- | --- | --- |
| **单文件 exe 产不出来** | 用户预期是"双击一个 exe" | 装 WiX 出安装包，或交付 `app-image` 目录 + 快捷方式；**这是最大的预期差** |
| JavaFX WebView 无 DevTools | 前端出错难查（正是上次踩过的坑） | 前端开发阶段全部在浏览器完成；发布版内置"打开日志目录"+"诊断页" |
| JVM 启动 2~4s 白屏 | 体验倒退 | 先显示带图标的启动闪屏，后端就绪再切页面 |
| WebView 内存计入 JVM | 长会话可能吃 1GB+ | 限制历史条数；避免长列表 DOM 累积 |
| `--enable-native-access` | JDK 24+ 不加会有告警，未来版本直接失败 | jpackage 里固化该 JVM 参数 |
| jlink 漏模块 | 运行期 `ClassNotFoundException` | `--add-modules` 里必须显式含 `javafx.web`、`java.naming`（Tomcat 需要） |
| 端口占用 | 双击启动失败 | 端口用 `0` 让系统分配，不要写死 8080 |
| 未装 WiX | `jpackage --type exe` 直接报错 | 先 `winget install WiXToolset.WiXToolset` 或用 app-image |

---

## 6. 建议

| 你的真实目的 | 建议 |
| --- | --- |
| 团队技术栈统一到 Java，后续要接 Spring AI / 公司既有 Java 组件 | **走方案 A**，一次投入换来长期可维护 |
| 只是不想维护 Go 代码，但体积要小 | **走方案 B**（SWT + WebView2，40~60MB） |
| 只要现在能用 | **保持现状**。Go 侧仅 600 行，无业务耦合，不需要长期投入 |

**如果确定要改，推荐分两步：**
1. 先做 **PoC**：JavaFX WebView 打开一个本地 HTTP 服务上的 Vue 页面，验证内核渲染、SSE 流式、jpackage 打包体积（半天）
2. PoC 通过后再全量迁移后端逻辑（1~2 天）

> 顺带一提：现有 Vue 前端**大部分可复用**，只需把 `api/bridge.js` 从 Wails 桥改成 HTTP 客户端——这一步做完，前端在浏览器和桌面里都能跑。
