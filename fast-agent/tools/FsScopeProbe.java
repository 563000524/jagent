import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.state.JsonFileAgentStateStore;
import io.agentscope.core.tool.AgentTool;
import io.agentscope.core.tool.ToolCallParam;
import io.agentscope.extensions.model.openai.OpenAIChatModel;
import io.agentscope.harness.agent.HarnessAgent;
import io.agentscope.harness.agent.IsolationScope;
import io.agentscope.harness.agent.filesystem.spec.LocalFilesystemSpec;
import io.agentscope.harness.agent.workspace.LocalFsMode;
import io.agentscope.harness.agent.workspace.WorkspaceManager;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 文件系统隔离范围（{@code IsolationScope}）对比探针 —— 用来给 {@code AgentService} 选对配置。
 *
 * <p>背景：用户让 agent 生成一个 java 文件，结果项目现场里冒出了 {@code <项目根>/<userId>/outputs/…}
 * —— 相对路径被套上了一层与登录用户同名的目录。原因就是文件系统的命名空间默认按 USER 隔离。
 *
 * <p>本探针把 USER 与 GLOBAL 两种配置并排跑一遍，看四件事：
 * <ol>
 *   <li>{@code write_file} 的相对路径落在哪；</li>
 *   <li>{@code deliver_artifact} 能不能按同一条相对路径交付（落点若在 {@code <项目根>/<userId>/} 就交付不了）；</li>
 *   <li>运行时数据（memory / sessions / 事件日志）落在哪 —— 不能因为改隔离范围就把记忆变成全用户共用；</li>
 *   <li>{@code getWorkspace()} 与 skills 目录是否变化。</li>
 * </ol>
 * 不联网：模型用假 Key 构造，只用来建 agent。
 *
 * <pre>
 * W=C:/.../fast-agent/target/scope-run &amp;&amp; mkdir -p "$W" &amp;&amp; cd "$W"
 * java "-Dfile.encoding=UTF-8" "-Dfastagent.data.dir=$W/.jagent" "-Duser.home=$W" \
 *      -cp ".../target/probe;.../target/classes;$(cat .../target/cp.txt)" FsScopeProbe
 * </pre>
 */
public class FsScopeProbe {

    private static final String USER = "probe";

    public static void main(String[] args) throws Exception {
        Path run = isolate();
        for (IsolationScope scope : new IsolationScope[]{IsolationScope.USER, IsolationScope.GLOBAL}) {
            System.out.println("\n===== IsolationScope." + scope + " =====");
            probe(run, scope);
        }
        System.out.println("\n（对比完，按上面的落点决定 AgentService 用哪个）");
    }

    private static void probe(Path run, IsolationScope scope) throws Exception {
        Path base = run.resolve("scope-" + scope.name().toLowerCase());
        Path project = base.resolve("project");
        Path ws = project.resolve(".jagentspace");
        Files.createDirectories(ws.resolve(".state"));

        HarnessAgent agent = HarnessAgent.builder()
                .name("ws-probe")
                .sysPrompt("probe")
                .model(OpenAIChatModel.builder()
                        .apiKey("sk-probe-not-used")
                        .baseUrl("http://127.0.0.1:1/v1")
                        .modelName("probe")
                        .stream(false)
                        .build())
                .workspace(ws)
                .stateStore(new JsonFileAgentStateStore(ws.resolve(".state")))
                .filesystem(new LocalFilesystemSpec()
                        .mode(LocalFsMode.ROOTED)
                        .project(project)
                        .projectWritable(true)
                        .addRoot(ws)
                        .isolationScope(scope))
                .build();

        RuntimeContext ctx = RuntimeContext.builder().userId(USER).sessionId("sess-probe").build();
        WorkspaceManager wm = agent.getWorkspaceManager();
        System.out.println("  getWorkspace()        = " + rel(run, wm.getWorkspace()));
        System.out.println("  getMemoryDir(ctx)     = " + rel(run, wm.getMemoryDir(ctx)));
        System.out.println("  getSkillsDir()        = " + rel(run, wm.getSkillsDir()));
        System.out.println("  getSessionDir(ctx)    = " + rel(run, wm.getSessionDir(ctx, "sess-probe")));
        System.out.println("  resolveRuntimeDataPath(default) = " + rel(run, wm.resolveRuntimeDataPath(ctx, "default")));

        invoke(agent, ctx, "write_file", Map.of("path", "outputs/落点.md", "content", "x\n"));
        System.out.println("  write_file 相对路径 outputs/落点.md →");
        System.out.println("      项目根/outputs/            = " + Files.isRegularFile(project.resolve("outputs").resolve("落点.md")));
        System.out.println("      项目根/<userId>/outputs/   = " + Files.isRegularFile(project.resolve(USER).resolve("outputs").resolve("落点.md")));

        String delivered = deliver(agent, ctx, "outputs/落点.md");
        System.out.println("  deliver_artifact 同一条相对路径 = "
                + (delivered.contains("Error") ? "交付不了" : "可交付"));

        agent.close();
    }

    /** 相对隔离根显示，太长看不出来 */
    private static String rel(Path run, Path p) {
        if (p == null) {
            return "null";
        }
        String s = p.toAbsolutePath().toString().replace('\\', '/');
        String r = run.toAbsolutePath().toString().replace('\\', '/');
        return s.startsWith(r) ? s.substring(r.length() + 1) : s;
    }

    private static String deliver(HarnessAgent agent, RuntimeContext ctx, String filePath) {
        Map<String, Object> input = new LinkedHashMap<>();
        input.put("filePath", filePath);
        return invoke(agent, ctx, "deliver_artifact", input);
    }

    private static String invoke(HarnessAgent agent, RuntimeContext ctx,
                                 String toolName, Map<String, Object> input) {
        AgentTool tool = agent.getToolkit().getTool(toolName);
        if (tool == null) {
            return "Error: 工具未注册";
        }
        ToolResultBlock result = tool.callAsync(ToolCallParam.builder()
                        .toolUseBlock(ToolUseBlock.builder()
                                .id("call-" + toolName).name(toolName).input(input).build())
                        .input(input)
                        .runtimeContext(ctx)
                        .build())
                .block();
        StringBuilder sb = new StringBuilder();
        if (result != null && result.getOutput() != null) {
            result.getOutput().forEach((b) -> sb.append(String.valueOf(b)).append(' '));
        }
        return sb.toString().replace('\n', ' ').trim();
    }

    private static Path isolate() {
        String dataDir = System.getProperty("fastagent.data.dir");
        String home = System.getProperty("user.home");
        if (dataDir == null || home == null || !Paths.get(dataDir).startsWith(Paths.get(home))) {
            System.err.println("缺少隔离属性，拒绝运行：需要同时给 -Dfastagent.data.dir 与 -Duser.home");
            System.exit(2);
        }
        return Paths.get("").toAbsolutePath();
    }
}
