package com.fastagent;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 按登录用户解析数据路径。
 *
 * <p>用户的所有配置与数据都挂在 {@code ~/.jagent/<userId>/} 下：
 * <pre>
 * ~/.jagent/cjh/
 * ├── models.json        模型配置（含 API Key）
 * ├── config.yaml        系统提示词 / 温度 / 上下文条数 / 深度思考
 * ├── workspaces.json    工作空间登记表
 * ├── memory/memory.md   全局记忆（只读注入系统提示词）
 * ├── tool/tools.json    内置工具开关 + MCP 服务器
 * ├── skills/&lt;name&gt;/SKILL.md
 * └── downloads/&lt;文件 id&gt;/   agent 交付给用户的文件副本（下载卡片的实体）
 * </pre>
 *
 * <p>工作空间的数据<b>不在这里</b>：每个工作空间建在用户指定的目录下、名为
 * {@code .jagentspace} 的子目录里（见 {@code Workspace.dataDir()}）。
 *
 * <p><b>为什么按用户分</b>：模型配置里含 API Key，记忆里含个人偏好，多账号共用一台
 * 机器时不该互相看见。代价是这条路径必须<b>显式传参</b>而不能用 ThreadLocal ——
 * 推理跑在虚拟线程上（见 {@code ChatService.generate}），ThreadLocal 传不过去。
 */
public final class UserPaths {

    private final String userId;
    private final Path root;

    private UserPaths(String userId) {
        this.userId = sanitize(userId);
        this.root = AppPaths.baseDir().resolve(this.userId);
    }

    /** 取某用户的路径视图；userId 为空时归到 {@code _anonymous} */
    public static UserPaths of(String userId) {
        return new UserPaths(userId);
    }

    /**
     * 净化成可作目录名的 userId。
     *
     * <p>只放行白名单字符：userId 来自登录名，若含 {@code /} 或 {@code ..} 就能把数据
     * 建到 {@code ~/.jagent/} 之外去。非白名单字符统一换成 {@code _}；
     * 全点（{@code .} / {@code ..}）会指向自身或上级，也一并兜底。
     */
    private static String sanitize(String userId) {
        if (userId == null || userId.isBlank()) {
            return "_anonymous";
        }
        String s = userId.trim().replaceAll("[^A-Za-z0-9_.-]", "_");
        return s.replace(".", "").isEmpty() ? "_anonymous" : s;
    }

    /** 净化后的用户 id，即目录名 */
    public String userId() {
        return userId;
    }

    /** 该用户的数据根，已创建 */
    public Path root() {
        return root;
    }

    public Path modelsFile() {
        return root.resolve("models.json");
    }

    public Path configFile() {
        return root.resolve("config.yaml");
    }

    public Path memoryDir() {
        return root.resolve("memory");
    }

    public Path globalMemoryFile() {
        return memoryDir().resolve("memory.md");
    }

    public Path toolDir() {
        return root.resolve("tool");
    }

    public Path toolsConfigFile() {
        return toolDir().resolve("tools.json");
    }

    public Path skillsDir() {
        return root.resolve("skills");
    }

    public Path workspacesFile() {
        return root.resolve("workspaces.json");
    }

    /**
     * agent 交付给用户的文件目录（下载卡片的实体存放处）。
     *
     * <p>每个文件一个子目录：{@code <id>/meta.json} + {@code <id>/<原始文件名>}。
     * 与工作空间无关，放在用户数据根下 —— 理由见 {@code ArtifactService}。
     */
    public Path downloadsDir() {
        return root.resolve("downloads");
    }

    /**
     * 建齐该用户的数据结构并播种默认文件。幂等。
     *
     * <p>启动时<b>不</b>调用（那时还不知道谁会登录），延后到该用户第一次访问配置时执行。
     * 播种出来的文件都是「用户可直接手改」的。
     */
    public synchronized void ensureLayout() {
        try {
            Files.createDirectories(root);
            Files.createDirectories(memoryDir());
            Files.createDirectories(toolDir());
            Files.createDirectories(skillsDir());
            Files.createDirectories(downloadsDir());
            AppPaths.seedFile(workspacesFile(), "[]\n");
            // 模型列表播成空数组：内容归 ModelsService 管，这里只保证「登录后文件就在」——
            // 用户随时可能直接去文件管理器里找它。格式与 ModelsService 的 pretty printer
            // 略有差别（那边是 "[ ]"），但能正常解析，下次保存时会改写成统一格式。
            AppPaths.seedFile(modelsFile(), "[]\n");
            AppPaths.seedFile(globalMemoryFile(), DEFAULT_MEMORY);
            AppPaths.seedFile(toolsConfigFile(), DEFAULT_TOOLS);
            AppPaths.seedFile(skillsDir().resolve("README.md"), SKILLS_README);
        } catch (IOException e) {
            AppLog.error(e, "初始化用户数据目录失败: %s", root);
        }
    }

    // ---------- 播种内容 ----------

    private static final String DEFAULT_MEMORY = """
            # 全局记忆

            > 本文件对本用户的所有工作空间生效，内容会注入到 agent 的系统提示词中。
            > 记录长期稳定的事实与偏好（例如「回答要结论先行」），不要记录一次性的临时信息。

            ## 用户偏好

            - （在这里补充）

            ## 长期事实

            - （在这里补充）
            """;

    private static final String DEFAULT_TOOLS = """
            {
              "allow": [],
              "deny": [],
              "mcpServers": {},
              "shellEnabled": false
            }
            """;

    private static final String SKILLS_README = """
            # 技能目录

            每个技能一个子目录，目录内必须有 `SKILL.md`：

            ```
            skills/
              my-skill/
                SKILL.md
                scripts/     (可选，配套脚本)
            ```

            `SKILL.md` 以 YAML frontmatter 开头：

            ```markdown
            ---
            name: my-skill
            description: 一句话说明这个技能解决什么问题、什么时候用
            ---

            # 正文：具体步骤与规范
            ```

            agent 会根据 `description` 判断是否加载该技能。
            本文件不会被当成技能。
            """;
}
