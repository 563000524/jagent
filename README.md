# 办公智能体 · fast-agent

> 一个 Windows 桌面端 AI 办公助手。双击 `FastAgent.exe`，打开就是一个聊天框。

不用装服务端、不用开浏览器、不用连外网控制台 —— 单文件目录自带 JRE，配置与会话全部留在本机。
面向公文写作、会议纪要整理、数据汇总分析、日常事务处理这类办公场景。

![Java](https://img.shields.io/badge/Java-21-orange)
![Spring Boot](https://img.shields.io/badge/Spring%20Boot-3.3.6-brightgreen)
![Vue](https://img.shields.io/badge/Vue-3.5-42b883)
![JavaFX](https://img.shields.io/badge/JavaFX-21.0.5-blueviolet)
![AgentScope](https://img.shields.io/badge/AgentScope%20Java-2.0.3-blue)
![Platform](https://img.shields.io/badge/platform-Windows-lightgrey)

---

## 特性

| 能力 | 说明 |
| --- | --- |
| 桌面应用 | JavaFX `Stage` 原生窗口 + 内嵌 `WebView`（WebKit）渲染界面，jpackage 打包成含内嵌 JRE 的免安装目录 |
| 流式对话 | REST + SSE，增量 token 实时上屏；支持深度思考过程单独展示 |
| 工具调用 | 内置文件读写、命令执行、脚本、记忆、子智能体等能力，逐个可开关 |
| 技能（Skill） | `SKILL.md` 形式的可插拔能力包，agent 按 `description` 自行判断是否加载 |
| MCP 支持 | 可配置 MCP 服务器，把外部系统能力接进来 |
| 工作空间 | 以「目录」为单位组织项目，agent 只在 `<目录>/.jagentspace/` 里读写，不污染你的文件 |
| 三层记忆 | 全局记忆（跨空间共用）+ 项目记忆（`MEMORY.md` + 每日事实）+ 会话上下文自动压缩 |
| 模型无关 | 走 OpenAI 兼容协议，供应商可切换（DeepSeek / 硅基流动 / OpenAI / Ollama 本地…） |
| 文件卡片与预览 | agent 读写的文件与产出的成品自动汇总成卡片列表，右侧独立预览列，图片 / Markdown / 代码 / CSV 直接看 |
| 多用户隔离 | 模型配置、密钥、记忆、技能按登录用户分目录存放 |

## 技术栈

| 层 | 选型 | 说明 |
| --- | --- | --- |
| 外壳 | JavaFX 21.0.5 | 必须显式声明平台 classifier；`javafx-web` 是界面本体 |
| 界面 | Vue 3.5 + Vite 6 | 构建产物编译进 jar 的 `static/`，由 WebView 渲染，不单独部署 |
| 智能体 | AgentScope Java 2.0.3 | `agentscope-harness`（HarnessAgent）提供工作区、会话持久化、长期记忆与压缩 |
| 后端 | Spring Boot 3.3.6 / Java 21 | 内嵌 Tomcat，**随机端口**，只监听 `127.0.0.1` |
| 通信 | REST + SSE | 两条通道：自有 SSE + AG-UI 标准事件流；前端在浏览器里也能完整调试 |
| 打包 | jpackage（JDK 21 自带） | `--type app-image`，产出含 JRE 的绿色目录 |

## 架构

单进程，两个线程组：JavaFX 持有 UI 线程并渲染窗口，Spring Boot 在内嵌 Tomcat 上提供接口。
窗口内容区是一个 `WebView` 控件，页面之间走标准 HTTP，**没有任何专有桥接**。

```
┌───────────────────────── FastAgent.exe（单进程） ─────────────────────────┐
│                                                                          │
│  ┌─ JavaFX 应用线程 ──────────────────────────────────────────────┐     │
│  │  Stage「办公智能体」（原生窗口，可拖动 / 最小化 / 关闭）        │     │
│  │    └─ Scene                                                    │     │
│  │         └─ WebView 控件（内嵌 WebKit）                          │     │
│  │              └─ Vue 3 页面：会话侧栏 │ 消息区 │ 文件区 │ 配置中心 │     │
│  └──────────────────────────┬─────────────────────────────────────┘     │
│                             │ HTTP + SSE（127.0.0.1:随机端口，仅本机）   │
│  ┌──────────────────────────┴─────────────────────────────────────┐     │
│  │  Spring Boot 应用层                                             │     │
│  │   鉴权 │ 工作空间 │ 会话 │ 上下文装配 │ 模型网关 │ 文件与交付物   │     │
│  └──────────────────────────┬─────────────────────────────────────┘     │
│                             │                                            │
│  ┌───────────┬──────────────┴─────────┬──────────────┬─────────────┐   │
│  │ AgentScope│  本地存储 (JSON)        │ 技能 / 工具   │ MCP 客户端   │   │
│  └───────────┴────────────────────────┴──────────────┴─────────────┘   │
└──────────────────────────────────────────────────────────────────────────┘
                              │ HTTPS（OpenAI 兼容协议）
                     ┌────────┴────────┐
                     │  大模型服务 API  │
                     └─────────────────┘
```

**为什么是 WebView 而不是 JavaFX 原生控件**：两条路都实际做过 —— 先落地了纯 JavaFX 控件版
（`ListView` + `TextFlow` + `TextArea`，约 1300 行 Java + 150 行 CSS），跑通后回退到 WebView。
决定性因素是样式与富文本能力：Markdown 表格 / 代码高亮 / 文档预览，原生控件每一样都要手写渲染器，
而 WebView 引一个前端库就够。代价是 32MB 的 WebKit 内核与多一层 HTTP。

## 目录结构

```
015-fast-agent/
├── fast-agent/                  后端工程（Spring Boot + JavaFX 外壳）★ 主要代码
│   ├── src/main/java/com/fastagent/
│   │   ├── auth/                登录与令牌（写死账号，令牌存内存）
│   │   ├── workspace/          工作空间登记与会话索引
│   │   ├── agent/              AgentScope 装配（HarnessAgent 构建、提示词）
│   │   ├── service/            SSE 流式对话、AG-UI 事件流、统计
│   │   ├── model/ config/      模型配置、系统提示词等
│   │   ├── artifact/           交付物与文件引用
│   │   ├── web/                REST 控制器
│   │   └── ui/                 JavaFX 窗口、目录选择、原生保存框
│   ├── src/main/resources/     application.yml + static/（前端构建产物，勿手改）
│   ├── tools/                  无窗口冒烟探针（不参与 mvn 构建）
│   └── dist/                   jpackage 产物（跑 build.ps1 才生成）
├── fast-agent-ui/               前端工程（Vue 3 + Vite 6）
│   └── src/{views,components,api,utils}/
├── build.ps1                    一键构建：前端 → 后端 → jpackage
├── docs/                        设计文档
└── JAGENT.md                    工程上下文（给在本仓库工作的 agent）
```

## 快速开始

### 前置要求

| 依赖 | 版本 | 说明 |
| --- | --- | --- |
| JDK | **21+** | 用了 record / switch 表达式 / 虚拟线程；**JDK 8 编不过** |
| Node.js | 18+ | 只用于构建前端 |
| Maven | 3.9+ | 后端构建 |

### 开发模式跑（前端热更新 + 后端）

```powershell
# 后端：固定端口 18080（vite 已把 /api 代理到这里）
mvn -f fast-agent\pom.xml spring-boot:run "-Dspring-boot.run.arguments=--server.port=18080"

# 前端（另开一个终端）
cd fast-agent-ui
npm install
npm run dev            # http://localhost:5173
```

浏览器打开 http://localhost:5173 ，用默认账号登录。

**首次使用**：登录后会自动弹出「配置中心 → 模型配置」，添加一条模型、填好 Base URL 与 API Key
即可开始对话。密钥只写在本机用户目录，不会进仓库。

### 打包桌面应用

```powershell
powershell -ExecutionPolicy Bypass -File build.ps1
# 产物：fast-agent\dist\FastAgent\FastAgent.exe
```

`build.ps1` 三阶段（顺序有依赖）：前端 `npm run build` → 后端 `mvn clean package` → jpackage。
可选参数：`-SkipFrontend`（复用已有前端产物）、`-SkipJpackage`（只出 jar）。
脚本里会**显式设置 `JAVA_HOME`**，因为本机系统默认可能是 JDK 8 —— 打包前按需改成你的 JDK 21 路径。

## 数据放在哪

### 用户数据 `~/.jagent/<userId>/`

模型配置、密钥、全局记忆、技能、工具开关都按登录用户分开存，多账号共用一台机器时互不可见。
目录在**登录成功时**创建。

```
~/.jagent/<userId>/
├── models.json        模型配置（含 API Key）
├── config.yaml        系统提示词 / 温度 / 上下文条数 / 深度思考
├── workspaces.json    工作空间登记表（有哪些空间、各在哪个目录）
├── memory/memory.md   全局记忆：该用户所有工作空间共用
├── tool/tools.json    内置工具开关 + MCP 服务器
└── skills/<名字>/SKILL.md
```

放**用户主目录**而不是程序目录：程序目录可能在只读位置（如 `Program Files`），
也会随版本升级被整体替换，不该带着用户配置一起搬。
测试或特殊部署可用 `-Dfastagent.data.dir=<路径>` 覆盖整个数据根；
`userId` 落盘前会做白名单净化（只留 `A-Za-z0-9_.-`），避免 `..` / `/` 逃出数据根。

### 工作空间：建在你指定的目录里

新建工作空间时要**指定一个目录**（可以是已有项目），agent 的数据都放在该目录下的 `.jagentspace/`：

```
D:\projects\my-project/      ← 你指定的目录
├── （你自己的文件，agent 不会碰）
└── .jagentspace/
    ├── AGENTS.md           agent 人格与项目约定（空间级，可编辑）
    ├── sessions.json       会话索引
    ├── .state/<userId>/<sessionId>/   对话状态
    └── <userId>/           agent 的工作目录
        ├── MEMORY.md       长期记忆（agent 自动维护）
        ├── memory/<日期>.md 每日事实（agent 自动维护）
        └── agents/…        会话原始日志
```

删除工作空间默认**只摘登记、不动磁盘**；显式 `purge=true` 也只删该目录下的 `.jagentspace`，
绝不碰你自己的文件。

## 核心概念

| 概念 | 说明 |
| --- | --- |
| 工作空间 | 建在你指定目录下的 `.jagentspace/`，是「项目」的载体，有自己的 `AGENTS.md` 与用户级 `MEMORY.md` |
| 会话 | 工作空间下的一次对话，按 `sessionId` 隔离，正文由 AgentScope 持久化 |
| 模型配置 | 可维护多条模型并指定当前使用哪条，密钥只在本地 |
| 全局记忆 | 该用户所有工作空间共用，作为 `environment memory` 注入 agent |
| 技能 | `SKILL.md` 能力包，agent 按 `description` 决定是否加载 |
| 工具 | 内置工具 allow/deny + MCP 服务器配置 |
| 交付物 | agent 产出的成品单独落盘并生成卡片，可在界面里预览 / 另存为 / 打开 |

界面右侧有「工作空间记录」面板可直接查看编辑记忆文件，配置统一在「配置中心」（五个页签）。

## 安全与隐私

- **默认只监听 `127.0.0.1`**，端口由系统随机分配，不对局域网暴露。
- **API Key 不入库**：密钥只写在本机用户目录 `~/.jagent/<userId>/models.json`，接口返回时脱敏，
  仓库里没有任何真实密钥（`fast-agent/tools/` 下的 `sk-probe-not-a-real-key` 之类都是探针占位串）。
- **`.gitignore` 已排除全部运行期数据**：`.jagent/`、`.jagentspace/`、`cjh/`、`*.log`、
  `target/`、`node_modules/`、前端构建产物。这些目录含完整对话正文与本机绝对路径，不要提交。
- **账号当前是写死的占位实现**（见 `auth/AuthService.java`，令牌存内存、进程重启失效），
  仅供本机单用户使用；接真实用户体系时只需替换该类实现，对外接口不变。

## Roadmap

- [ ] 接入真实用户体系与持久化令牌（替换写死账号）
- [ ] 文档解析：Word / PDF / Excel 内容提取进上下文
- [ ] 企业知识库检索（本地向量索引）
- [ ] 更多内置工具与技能模板
- [ ] macOS / Linux 打包（classifier 已预留，未实测）

## 文档

| 文档 | 内容 |
| --- | --- |
| [办公智能体概要设计](docs/办公智能体概要设计.md) | 项目定位、功能模块清单、架构、里程碑 |
| [Java 方案可行性分析](docs/Java方案可行性分析.md) | 选型对比（Java 各路线 vs 原生壳），含取舍与风险 |
| [JAGENT.md](JAGENT.md) | 工程上下文：布局、跨工程关系、不变式、路径口径、常见陷阱 |

## 许可证

暂未指定开源许可证。如需使用或引用，请先联系作者。
