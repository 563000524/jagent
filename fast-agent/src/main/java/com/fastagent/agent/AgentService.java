package com.fastagent.agent;

import com.fastagent.AppLog;
import com.fastagent.UserPaths;
import com.fastagent.artifact.ArtifactService;
import com.fastagent.artifact.ArtifactTool;
import com.fastagent.config.AppConfig;
import com.fastagent.config.ConfigService;
import com.fastagent.model.ModelEntry;
import com.fastagent.model.ModelsService;
import com.fastagent.workspace.WorkspaceService;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.Model;
import io.agentscope.core.skill.repository.FileSystemSkillRepository;
import io.agentscope.core.state.JsonFileAgentStateStore;
import io.agentscope.extensions.model.openai.OpenAIChatModel;
import io.agentscope.harness.agent.HarnessAgent;
import io.agentscope.harness.agent.filesystem.spec.LocalFilesystemSpec;
import io.agentscope.harness.agent.memory.compaction.CompactionConfig;
import io.agentscope.harness.agent.tools.ToolsConfig;
import io.agentscope.core.tool.Toolkit;
import io.agentscope.harness.agent.workspace.LocalFsMode;
import org.springframework.stereotype.Service;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * HarnessAgent 实例管理。
 *
 * <p>每个（用户 × 工作空间 × 模型）一个 agent 实例。实例名带 workspaceId（{@code ws-&lt;id&gt;}），
 * 从而把 AgentScope 的状态存储与会话日志都隔离进该工作空间的目录；
 * 实例本身对会话无状态，可被多个 {@code (userId, sessionId)} 并发复用 ——
 * AgentScope 保证同一会话串行、不同会话并行。
 *
 * <p>缓存键含 userId：构建期要装配<b>用户级</b>资产（技能、工具开关、全局记忆、
 * 系统提示词、模型配置），这些每人一份，不能混用。
 *
 * <p>三类用户级资产在构建时装配进来：
 * <ul>
 *   <li>{@code ~/.jagent/<userId>/skills/} → 技能（{@link FileSystemSkillRepository}）</li>
 *   <li>{@code ~/.jagent/<userId>/tool/tools.json} → 内置工具开关与 MCP 服务器</li>
 *   <li>{@code ~/.jagent/<userId>/memory/memory.md} → 全局记忆，注入 environment memory</li>
 * </ul>
 */
@Service
public class AgentService {

    private final ConfigService configService;
    private final ModelsService modelsService;
    private final AssetService assets;
    private final WorkspaceService workspaces;
    private final ArtifactService artifacts;
    private final Map<String, HarnessAgent> cache = new ConcurrentHashMap<>();

    /**
     * 交付约定，追加在用户的系统提示词之后。
     *
     * <p><b>为什么不写进默认提示词</b>：默认提示词只决定「新建配置文件的初始内容」，
     * 老用户的 {@code config.yaml} 里已经是旧文本，改默认值对存量用户无效；
     * 挂在这里则每次构建 agent 都生效。
     *
     * <p>harness 自带的类似提示（「File Isolation Notice」）只在沙箱 / 远程文件系统模式下
     * 注入；本工程用的是本地可写根，那条不会出现，所以这句必须自己写。
     *
     * <p><b>为什么首选 {@code deliver_file}（我们自己的工具）</b>：harness 的
     * {@code deliver_artifact} 只能交付「工作空间里已存在的文件」，于是模型只能先把成品写进
     * 用户的项目目录再交付 —— 用户的工作空间往往就是代码仓库，结果仓库里凭空多出产物目录，
     * 同一个成品还可能出现两份（写一份 + 交付副本）。走 {@code deliver_file} 时内容由模型直接给，
     * 成品只落在 {@code ~/.jagent/<userId>/downloads/}，项目目录干净。
     * 详见 {@link com.fastagent.artifact.ArtifactTool}。
     */
    private static final String ARTIFACT_INSTRUCTIONS = """
            ## 交付成品文件
            产出成品（代码、文档、报告、表格、图片等）时：
            1. **默认用 deliver_file(fileName, content, description) 交付** —— 把成品内容直接放进
               content，应用会把它存到用户自己的下载目录，界面上出现该文件的卡片（可预览、另存为、打开）。
               这样**不要**往用户的项目目录里写文件：用户的工作空间是他的代码仓库或文档目录，
               不该被产物污染。
            2. 同一个成品只交一次，不要重复交付、也不要在别处再留一份。fileName 只写文件名
               （含扩展名，如 `HelloWorld.java`），不要带目录。
            3. 只有「内容超过 2MB」或「成品本来就是工作空间里已有的文件」时，才把文件写在工作空间里，
               再用 deliver_artifact 交付，此时 filePath 用项目现场的绝对路径。

            交付要静默：不要说「已交付」，也不要提这些工具，界面会自动出现文件卡片。
            中间产物、临时脚本，以及含密钥或隐私的文件不要交付。""";

    public AgentService(ConfigService configService, ModelsService modelsService, AssetService assets,
                        WorkspaceService workspaces, ArtifactService artifacts) {
        this.configService = configService;
        this.modelsService = modelsService;
        this.assets = assets;
        this.workspaces = workspaces;
        this.artifacts = artifacts;
    }

    /**
     * 取（必要时创建）某用户某工作空间 + 某模型的 agent。
     *
     * <p>模型与用户级资产都是<b>构建期</b>注入的，所以换了模型或换了用户都必须用另一个实例 ——
     * 缓存键因此是 {@code userId|workspaceId|modelId}。
     *
     * <p>但 {@code workspace()} 与 {@code stateStore} 与模型无关，会话状态按
     * {@code (userId, sessionId)} 存在同一个目录里，所以同一个会话换模型后
     * <b>上下文仍然接得上</b>，这正是切换模型时想要的行为。
     *
     * @param modelId 为空、或指定的模型不存在/不可用，则回落到该用户的默认模型
     */
    public HarnessAgent agentFor(String userId, String workspaceId, String modelId) {
        String key = key(userId, workspaceId, modelId);
        return cache.computeIfAbsent(key, k -> build(userId, workspaceId, modelId));
    }

    /**
     * 只取已存在的实例，不创建。
     *
     * <p>用于「停止生成」这类操作：实例不在缓存里就说明没有正在跑的生成，
     * 不该为了中断而花 1~3 秒构建一个 agent。
     */
    public HarnessAgent find(String userId, String workspaceId, String modelId) {
        return cache.get(key(userId, workspaceId, modelId));
    }

    /** 配置变更后重建某用户在某工作空间的全部实例（各模型一个） */
    public void invalidateWorkspace(String userId, String workspaceId) {
        String prefix = userId + "|" + workspaceId + "|";
        cache.keySet().stream()
                .filter(k -> k.startsWith(prefix))
                .toList()
                .forEach(this::closeAndRemove);
    }

    /** 配置变更后重建某用户的全部实例 */
    public void invalidateUser(String userId) {
        String prefix = userId + "|";
        cache.keySet().stream()
                .filter(k -> k.startsWith(prefix))
                .toList()
                .forEach(this::closeAndRemove);
    }

    public void invalidateAll() {
        cache.keySet().forEach(this::closeAndRemove);
    }

    // ---------- 内部 ----------

    private static String key(String userId, String workspaceId, String modelId) {
        return userId + "|" + workspaceId + "|" + (modelId == null ? "" : modelId);
    }

    private void closeAndRemove(String key) {
        HarnessAgent old = cache.remove(key);
        if (old == null) {
            return;
        }
        try {
            old.close();
        } catch (Exception e) {
            AppLog.warn("关闭 HarnessAgent 失败: %s", e.getMessage());
        }
    }

    private HarnessAgent build(String userId, String workspaceId, String modelId) {
        AppConfig cfg = configService.get(userId);
        ModelEntry model = modelsService.byId(userId, modelId);
        if (model == null || !model.usable()) {
            if (modelId != null && !modelId.isBlank()) {
                AppLog.warn("会话指定的模型不可用，回落默认：%s", modelId);
            }
            model = modelsService.active(userId);
        }
        // 数据目录来自登记表：<用户指定目录>/.jagentspace
        Path ws = workspaces.pathOf(userId, workspaceId);
        Path state = ws.resolve(".state");
        Path skills = UserPaths.of(userId).skillsDir();
        try {
            Files.createDirectories(ws);
            Files.createDirectories(state);
            Files.createDirectories(skills);
        } catch (Exception e) {
            AppLog.error(e, "创建工作空间目录失败: %s", ws);
        }

        AppLog.info("构建 HarnessAgent: user=%s workspace=%s 目录=%s 模型=%s", userId, workspaceId, ws,
                model == null ? "（未配置）" : model.getId());

        // 文件工具的沙箱范围。
        //
        // 默认（LocalFilesystemSpec 无参构造）会把 agent 锁在 cwd —— 也就是
        // .jagentspace/<userId>/ 里，于是它读不到项目现场、更写不了，报
        // "not within an allowed root" / "Path traversal not allowed"。
        // 但「工作空间」本来就是用户指定的**一整个项目目录**，agent 该能在这儿干活。
        //
        // 改成 ROOTED 并显式声明两个根：
        //   - project：工作空间根（= .jagentspace 的上一级），即用户的项目现场
        //   - ws     ：agent 自己的数据目录（cwd 的上两级），保证它写自己的记忆/状态不被拦
        // projectWritable(true) 允许改项目文件 —— 这是"能访问工程根"的实质。
        // 想收紧成只读就改成 false；想彻底关掉沙箱是 LocalFsMode.UNRESTRICTED（不建议）。
        Path projectDir = ws.getParent();

        HarnessAgent.Builder builder = HarnessAgent.builder()
                .name("ws-" + workspaceId)
                .sysPrompt(cfg.getSystemPrompt() + "\n\n" + ARTIFACT_INSTRUCTIONS)
                .model(buildModel(model, cfg))
                .generateOptions(buildGenerateOptions(cfg))
                .workspace(ws)
                // 交付能力：挂上之后 harness 会注册 deliver_artifact 工具，
                // 模型产出成品时调用它，文件被交到 ArtifactService 落盘并出现在下载卡片上。
                // 桌面壳的 WebView 没有下载能力，这是用户唯一能拿到文件的通道。
                .artifactDeliveryTarget(artifacts)
                // 再挂一个我们自己的交付工具 deliver_file：模型直接把内容给过来，
                // 成品只落到 ~/.jagent/<userId>/downloads/，不必先写进用户的项目目录。
                // 传入的 Toolkit 会被 harness 继续注册它的内置工具（见 Builder.build 字节码），
                // 所以这里 new 一个空的即可。
                .toolkit(toolkitWithArtifactTool())
                // 状态存储显式指到工作空间内，避免默认写到 ~/.agentscope 造成数据分散
                .stateStore(new JsonFileAgentStateStore(state))
                .compaction(CompactionConfig.builder()
                        .triggerMessages(40)
                        .keepMessages(12)
                        .build())
                // 事件日志（transcript）的落点必须显式指定，不能吃默认值。
                // 默认 tenant 是字面量 "default"，而 ProjectAwareOverlay 的 workspace 前缀白名单
                // （MEMORY.md/memory/AGENTS.md/agents/skills/knowledge/rules/tools.json/subagents/
                //  plans/.index/.skills-cache/large_tool_results，共 13 项）里没有 default，
                // 于是 <userId>/default/ws-<id>/<sid>/events/*.jsonl 被当成"项目文件"，
                // 经 projectFs 写到了项目现场（工程根）——污染用户目录。
                // 换成白名单内的 "agents"：落 .jagentspace/<userId>/agents/ws-<id>/<sid>/events/。
                .transcriptTenant("agents")
                // 文件工具沙箱（原因见上）：放行「工作空间根 + agent 数据目录」，项目可写
                .filesystem(new LocalFilesystemSpec()
                        .mode(LocalFsMode.ROOTED)
                        .project(projectDir)
                        .projectWritable(true)
                        .addRoot(ws));
        AppLog.info("文件工具范围: mode=ROOTED project=%s（可写） agentDir=%s", projectDir, ws);
        // shell 是否开放由 ~/.jagent/<userId>/tool/tools.json 的 shellEnabled 决定

        // 技能目录。
        // 注意：skillRepository(...) 与 projectGlobalSkillsDir(...) 只能二选一 ——
        // 两者都挂会让同一个技能被注册进两个仓库（实测 alpha/beta 各命中 2 次），
        // 技能清单会在提示词里出现两遍。这里只用前者，语义更直白。
        try {
            builder.skillRepository(new FileSystemSkillRepository(skills));
        } catch (Exception e) {
            AppLog.warn("挂载技能目录失败，本次不加载技能: %s", e.getMessage());
        }

        // 内置工具开关（含 shell）+ MCP 服务器
        ToolsFile toolsFile = assets.toolsFile(userId);
        boolean shellEnabled = toolsFile != null && toolsFile.isShellEnabled();
        if (!shellEnabled) {
            // 默认不给 shell：它能让 agent 执行本机任意命令，桌面应用的默认值应当保守。
            // 需要时在「配置中心 → 工具」打开 —— 打开后 agent 才有 pwd / 删文件 / 跑脚本的能力。
            builder.disableShellTool();
        }
        ToolsConfig tools = assets.toolsConfig(userId);
        if (tools != null) {
            builder.toolsConfig(tools);
        }
        AppLog.info("工具配置: user=%s shell=%s deny=%s mcp=%s",
                userId,
                shellEnabled ? "已启用" : "已禁用",
                tools == null || tools.getDeny() == null ? List.of() : tools.getDeny(),
                tools == null || tools.getMcpServers() == null ? List.of() : tools.getMcpServers().keySet());

        // 全局记忆：只读注入。agent 自己的可写记忆在工作空间内（MEMORY.md / memory/）
        String globalMemory = assets.readGlobalMemory(userId);
        if (globalMemory != null && !globalMemory.isBlank()) {
            builder.environmentMemory(globalMemory);
            AppLog.info("已注入全局记忆: user=%s %s（%d 字）",
                    userId, UserPaths.of(userId).globalMemoryFile(), globalMemory.length());
        }

        return builder.build();
    }

    /**
     * 只放我们自己的工具的空 Toolkit。
     *
     * <p>harness 会把它自己的内置工具（文件、记忆、技能、子 agent…）继续注册到同一个
     * Toolkit 上，所以这里 <b>不要</b>自己加内置工具，否则会注册两遍。
     */
    private Toolkit toolkitWithArtifactTool() {
        Toolkit toolkit = new Toolkit();
        toolkit.registerTool(new ArtifactTool(artifacts));
        return toolkit;
    }

    private Model buildModel(ModelEntry entry, AppConfig cfg) {
        String baseUrl = entry == null ? "" : entry.baseUrl();
        String key = entry == null ? "" : entry.getApiKey();
        String name = entry == null ? "" : entry.getId();
        return OpenAIChatModel.builder()
                .apiKey(key)
                .baseUrl(baseUrl)
                // URL 里带了自定义路径时按 URL 走，否则用 OpenAI 默认的 /chat/completions
                .endpointPath(entry == null ? "/chat/completions" : entry.endpointPath())
                .modelName(name)
                .stream(true)
                .build();
    }

    private GenerateOptions buildGenerateOptions(AppConfig cfg) {
        GenerateOptions.Builder b = GenerateOptions.builder()
                .temperature(cfg.getTemperature());
        // 硅基流动等 OpenAI 兼容服务用 enable_thinking 开关推理过程
        b.additionalBodyParam("enable_thinking", cfg.isEnableThinking());
        return b.build();
    }
}
