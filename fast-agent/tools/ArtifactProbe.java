import com.fastagent.UserPaths;
import com.fastagent.artifact.ArtifactMeta;
import com.fastagent.artifact.ArtifactService;
import com.fastagent.artifact.FileRef;
import com.fastagent.artifact.FileRefStore;
import com.fastagent.web.ArtifactView;
import com.fastagent.web.FileController;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.tool.AgentTool;
import io.agentscope.core.tool.ToolCallParam;
import io.agentscope.extensions.model.openai.OpenAIChatModel;
import io.agentscope.harness.agent.HarnessAgent;
import io.agentscope.harness.agent.filesystem.spec.LocalFilesystemSpec;
import io.agentscope.harness.agent.workspace.LocalFsMode;
import io.agentscope.core.state.JsonFileAgentStateStore;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 文件交付能力的冒烟探针：注册 → 写文件 → 交付 → 落盘 → 查询，一次跑完。
 *
 * <p>它验证的是<b>读代码看不出来的三件事</b>：
 * <ol>
 *   <li>挂上 {@code artifactDeliveryTarget} 之后，harness 是否真的注册了
 *       {@code deliver_artifact}（注册条件写在 harness Builder 的字节码里）；</li>
 *   <li>{@code deliver_artifact} 的 filePath 按谁解析 —— 模型给相对路径、绝对路径、
 *       数据目录路径，哪些能交付（提示词怎么写以此为准）；</li>
 *   <li>落盘结构与查询接口是否对得上（meta.json 可读、归属校验生效、同名覆盖）。</li>
 * </ol>
 * 不联网：模型用假 Key 构造，只用来建 agent，不会发起调用。
 *
 * <pre>
 * # 先在 fast-agent 目录下生成类路径、编译探针
 * mvn -q -o dependency:build-classpath "-Dmdep.outputFile=target/cp.txt"
 * javac -encoding UTF-8 -cp "target/classes;$(cat target/cp.txt)" -d target/probe tools/ArtifactProbe.java
 *
 * # 再在隔离目录里跑（两个属性必须都给，路径用 C:/... 形式）
 * W=C:/.../fast-agent/target/pr
 * mkdir -p "$W" &amp;&amp; cd "$W"
 * java "-Dfile.encoding=UTF-8" "-Dfastagent.data.dir=$W/.jagent" "-Duser.home=$W" \
 *      -cp ".../target/probe;.../target/classes;$(cat .../target/cp.txt)" ArtifactProbe
 * </pre>
 *
 * 注意 {@code -Duser.home} 一定写成 {@code C:/...}：写成 git bash 的 {@code /c/...} 时
 * Java 会把它当成 {@code C:\c\...}，探针仍「通过」隔离检查但目录已经跑偏。
 */
public class ArtifactProbe {

    /** 项目现场那份文件的内容，用来核对交付过去的字节是否原样 */
    private static final String PROJECT_BODY = "项目现场的文件\n";

    private static int failed;

    public static void main(String[] args) throws Exception {
        Path run = isolate();
        String userId = "probe";
        String sessionId = "sess-probe";

        // 与 AgentService 保持同一套布局：workspace = <项目现场>/.jagentspace
        Path project = run.resolve("project");
        Path ws = project.resolve(".jagentspace");
        Path userDir = ws.resolve(userId);
        Files.createDirectories(project);
        Files.createDirectories(userDir.resolve("outputs"));

        Path inProject = project.resolve("方案.md");
        Files.writeString(inProject, PROJECT_BODY, StandardCharsets.UTF_8);
        Path inProjectOut = project.resolve("outputs").resolve("报告.md");
        Files.createDirectories(inProjectOut.getParent());
        Files.writeString(inProjectOut, PROJECT_BODY, StandardCharsets.UTF_8);
        Path inUserDir = userDir.resolve("outputs").resolve("周报.md");
        Files.writeString(inUserDir, "数据目录里的文件\n", StandardCharsets.UTF_8);

        // ArtifactService 的第二个参数是工作空间登记表，只在「工具事件 → 文件引用」那条路上用到；
        // 探针手工构造 wsRoot 直接调 FileRefStore，所以这里传 null。
        FileRefStore fileRefs = new FileRefStore();
        ArtifactService artifacts = new ArtifactService(fileRefs, null);
        artifacts.beginRun(userId, "ws-probe", sessionId);

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
                        .addRoot(ws))
                .artifactDeliveryTarget(artifacts)
                // 与 AgentService 一致：挂上自己的 deliver_file 工具。harness 会把它的内置工具
                // 继续注册进这个 Toolkit，所以下面要断言内置工具一个都没少
                .toolkit(toolkitWith(fileRefs, artifacts))
                .build();

        RuntimeContext ctx = RuntimeContext.builder().userId(userId).sessionId(sessionId).build();

        // ① 工具注册
        Set<String> names = agent.getToolkit().getToolNames();
        check("工具已注册 deliver_artifact", names.contains("deliver_artifact"));
        check("工具已注册我们自己的 deliver_file", names.contains("deliver_file"));
        check("harness 内置工具没被我们的 Toolkit 顶掉",
                names.contains("write_file") && names.contains("read_file") && names.contains("memory_save"));
        if (!names.contains("deliver_artifact") || !names.contains("deliver_file")) {
            System.out.println("  实际工具清单: " + names);
            finish();
            return;
        }

        // ② filePath 口径：模型给哪种路径能交付
        System.out.println("\n-- filePath 口径 --");
        matrix(agent, ctx, "项目现场相对路径", "outputs/报告.md");
        matrix(agent, ctx, "项目现场绝对路径", inProject.toString());
        matrix(agent, ctx, "数据目录相对路径", "outputs/周报.md");
        matrix(agent, ctx, "数据目录绝对路径", inUserDir.toString());
        check("项目现场的文件可交付（相对路径）", canDeliver(agent, ctx, "outputs/报告.md"));
        check("项目现场的文件可交付（绝对路径）", canDeliver(agent, ctx, inProject.toString()));
        check("数据目录里的文件交付不了", !canDeliver(agent, ctx, inUserDir.toString()));

        // ③ write_file 用相对路径时落在哪（决定提示词让模型写到哪儿）
        System.out.println("\n-- write_file 落点 --");
        String w1 = invoke(agent, ctx, "write_file", Map.of("path", "outputs/落点.md", "content", "x\n"));
        System.out.println("  返回: " + w1.replace('\n', ' ').trim());
        System.out.println("  项目现场/outputs/落点.md = " + Files.isRegularFile(project.resolve("outputs").resolve("落点.md")));
        System.out.println("  项目现场/<userId>/outputs/落点.md = "
                + Files.isRegularFile(project.resolve(userId).resolve("outputs").resolve("落点.md")));
        System.out.println("  数据目录/outputs/落点.md = " + Files.isRegularFile(userDir.resolve("outputs").resolve("落点.md")));
        System.out.println("  → 按同一条相对路径交付 = "
                + (canDeliver(agent, ctx, "outputs/落点.md") ? "可交付" : "不可交付"));

        String abs = project.resolve("成品").resolve("绝对路径成品.md").toString();
        String w2 = invoke(agent, ctx, "write_file", Map.of("path", abs, "content", "成品\n"));
        System.out.println("  绝对路径写入返回: " + w2.replace('\n', ' ').trim());
        System.out.println("  文件落在指定位置 = " + Files.isRegularFile(Paths.get(abs)));
        System.out.println("  → 按同一条绝对路径交付 = "
                + (canDeliver(agent, ctx, abs) ? "可交付" : "不可交付"));
        check("绝对路径写入 + 绝对路径交付 这条路是通的",
                Files.isRegularFile(Paths.get(abs)) && canDeliver(agent, ctx, abs));

        // ④ 交付并检查落盘
        System.out.println();
        String text = deliver(agent, ctx, inProject.toString(), "周报.md", "探针产出");
        check("交付成功（返回: " + text.replace('\n', ' ').trim() + "）",
                !text.contains("Error") && !text.contains("失败"));

        Path dir = UserPaths.of(userId).downloadsDir();
        check("下载目录已建: " + dir, Files.isDirectory(dir));
        List<ArtifactMeta> list = artifacts.list(userId, sessionId);
        ArtifactMeta meta = list.stream().filter((m) -> "周报.md".equals(m.getName())).findFirst().orElse(null);
        check("列表里能取到刚交付的 周报.md", meta != null);
        if (meta == null) {
            System.out.println("  实际: " + list.stream().map(ArtifactMeta::getName).toList());
            finish();
            return;
        }
        Path file = artifacts.fileOf(meta);
        check("实体存在: " + file, Files.isRegularFile(file));
        check("内容与来源文件一致", Files.readString(file, StandardCharsets.UTF_8).equals(PROJECT_BODY));
        check("元数据字段齐全",
                meta.getName().equals("周报.md")
                        && meta.getUserId().equals(userId)
                        && meta.getSessionId().equals(sessionId)
                        && meta.getWorkspaceId().equals("ws-probe")
                        && meta.getDescription().equals("探针产出")
                        && meta.getSize() == PROJECT_BODY.getBytes(StandardCharsets.UTF_8).length);
        check("按 id 查得到", artifacts.find(userId, meta.getId()) != null);
        check("换一个用户查不到（归属校验）", artifacts.find("someone-else", meta.getId()) == null);
        check("非法 id 查不到（防路径穿越）", artifacts.find(userId, "../../etc") == null);

        // ⑤ 同名再交付：应覆盖同一条，而不是多出一张卡片
        artifacts.deliver(ctx, new io.agentscope.harness.agent.artifact.ArtifactDeliveryRequest(
                inProject.toString(), "改了内容".getBytes(StandardCharsets.UTF_8), "周报.md", "探针产出", false));
        List<ArtifactMeta> dup = artifacts.list(userId, sessionId).stream()
                .filter((m) -> "周报.md".equals(m.getName())).toList();
        check("同名重复交付不新增卡片", dup.size() == 1);
        check("内容已更新",
                Files.readString(artifacts.fileOf(dup.get(0)), StandardCharsets.UTF_8).equals("改了内容"));

        // ⑥ 文件引用：卡片上「读取/写入/编辑」那一类（来源是工具调用参数）
        System.out.println("\n-- 文件引用 --");
        FileRefStore refs = fileRefs;
        refs.record(userId, "ws-probe", sessionId, ws, "outputs/落点.md", "write");
        refs.record(userId, "ws-probe", sessionId, ws, inProject.toString(), "read");
        List<FileRef> refList = refs.list(userId, sessionId);
        refList.forEach((r) -> System.out.println("  ref: " + r.getAction() + " " + r.getName()
                + " size=" + r.getSize() + " path=" + r.getPath()));
        check("记录了 2 条文件引用", refList.size() == 2);
        FileRef written = refList.stream().filter((r) -> "write".equals(r.getAction()))
                .findFirst().orElse(null);
        check("相对路径解析到 <项目现场>/<userId>/outputs/落点.md",
                written != null && written.getPath().replace('\\', '/').endsWith("/probe/outputs/落点.md"));
        check("名字与大小记下来了",
                written != null && "落点.md".equals(written.getName()) && written.getSize() > 0);
        check("按 id 查得到", written != null && refs.find(userId, written.getId()) != null);
        check("归属校验：别的用户查不到",
                written != null && refs.find("someone-else", written.getId()) == null);
        check("索引文件已落盘: " + UserPaths.of(userId).root().resolve("file-refs.json"),
                Files.isRegularFile(UserPaths.of(userId).root().resolve("file-refs.json")));

        refs.record(userId, "ws-probe", sessionId, ws, "outputs/落点.md", "edit");
        check("同一文件重复涉及不新增条目", refs.list(userId, sessionId).size() == 2);
        check("更弱的动作不覆盖更强的（写之后又改，仍显示写入）",
                "write".equals(nameOf(refs.list(userId, sessionId), "落点.md").getAction()));
        refs.record(userId, "ws-probe", sessionId, ws, inProject.toString(), "write");
        check("更重的动作会覆盖（读之后又写，显示写入）",
                "write".equals(nameOf(refs.list(userId, sessionId), "方案.md").getAction()));
        check("解析不到的文件不记录",
                refs.record(userId, "ws-probe", sessionId, ws, "不存在的文件.md", "read") == null);

        // ⑦ 卡片合并：交付物优先，同一个文件不出两张卡
        System.out.println("\n-- 卡片合并 --");
        // 用当前最新的交付记录（前面的 list 是覆盖之前的快照，大小已经过期）
        List<ArtifactMeta> current = artifacts.list(userId, sessionId);
        long artSize = current.stream().filter((m) -> "周报.md".equals(m.getName()))
                .findFirst().orElseThrow().getSize();
        // 造两份「同名」文件：一份与交付物等大（同一个文件的另一种路径写法），一份不等大（另一个版本）
        Path dupSame = project.resolve("dup").resolve("周报.md");
        Path dupOther = project.resolve("dup2").resolve("周报.md");
        Files.createDirectories(dupSame.getParent());
        Files.createDirectories(dupOther.getParent());
        Files.writeString(dupSame, "x".repeat((int) artSize), StandardCharsets.UTF_8);
        Files.writeString(dupOther, "y".repeat((int) artSize + 5), StandardCharsets.UTF_8);
        refs.record(userId, "ws-probe", sessionId, ws, dupSame.toString(), "write");
        refs.record(userId, "ws-probe", sessionId, ws, dupOther.toString(), "write");

        List<ArtifactView> merged = FileController.merge(current, refs.list(userId, sessionId));
        System.out.println("  卡片: " + merged.stream()
                .map((v) -> v.action() + ":" + v.name() + "(" + v.size() + ")").toList());
        check("同名同大小的引用被合并到交付物上（只剩交付那一张）",
                merged.stream().filter((v) -> "周报.md".equals(v.name())
                        && "delivered".equals(v.action())).count() == 1);
        check("同名但大小不同的版本仍各自成卡（不掩盖另一个版本）",
                merged.stream().filter((v) -> "周报.md".equals(v.name())).count() == 2);

        // ⑧ deliver_file：模型直接给内容，成品只落在用户下载目录，不碰项目现场
        System.out.println("\n-- deliver_file（成品直接交给应用）--");
        String src = "public class HelloWorld {\n    public static void main(String[] a) {}\n}\n";
        String r1 = invoke(agent, ctx, "deliver_file", Map.of(
                "fileName", "HelloWorld.java",
                "content", src,
                "description", "最简单的 Java 示例"));
        System.out.println("  返回: " + r1.trim());
        Path dl = UserPaths.of(userId).downloadsDir();
        Path saved = null;
        try (java.util.stream.Stream<Path> dirs = Files.list(dl)) {
            for (Path sub : dirs.toList()) {
                Path f = sub.resolve("HelloWorld.java");
                if (Files.isRegularFile(f)) {
                    saved = f;
                }
            }
        }
        check("成品落进用户下载目录: " + saved, saved != null);
        check("内容一字不差",
                saved != null && Files.readString(saved, StandardCharsets.UTF_8).equals(src));
        check("项目现场没有被写入任何东西",
                !Files.exists(project.resolve("HelloWorld.java"))
                        && !Files.exists(project.resolve(userId).resolve("outputs").resolve("HelloWorld.java")));
        long cards = artifacts.list(userId, sessionId).stream()
                .filter((m) -> "HelloWorld.java".equals(m.getName())).count();
        check("列表里出现这张卡片", cards == 1);
        invoke(agent, ctx, "deliver_file", Map.of("fileName", "HelloWorld.java", "content", src));
        check("同名再交一次不新增卡片（覆盖同一张）",
                artifacts.list(userId, sessionId).stream()
                        .filter((m) -> "HelloWorld.java".equals(m.getName())).count() == 1);
        check("带目录的文件名被净化成纯文件名",
                invoke(agent, ctx, "deliver_file", Map.of(
                        "fileName", "outputs/../x/带目录.txt", "content", "x"))
                        .contains("带目录.txt")
                        && artifacts.list(userId, sessionId).stream()
                        .anyMatch((m) -> "带目录.txt".equals(m.getName())));

        agent.close();
        finish();
    }

    /** 与 AgentService 同样的注册方式：空 Toolkit + 我们自己的交付工具 */
    private static io.agentscope.core.tool.Toolkit toolkitWith(FileRefStore refs, ArtifactService artifacts) {
        io.agentscope.core.tool.Toolkit t = new io.agentscope.core.tool.Toolkit();
        t.registerTool(new com.fastagent.artifact.ArtifactTool(artifacts));
        return t;
    }

    /** 按文件名找一条记录（探针断言用） */
    private static FileRef nameOf(List<FileRef> refs, String name) {
        return refs.stream().filter((r) -> name.equals(r.getName())).findFirst()
                .orElse(new FileRef());
    }

    private static void matrix(HarnessAgent agent, RuntimeContext ctx, String what, String filePath) {
        boolean ok = canDeliver(agent, ctx, filePath);
        System.out.println((ok ? "  [可交付] " : "  [不可交付] ") + what + " → " + filePath);
    }

    /** 试着交付一次，只看成败；成功的话会把文件留在下载目录里（用完即弃，不影响断言） */
    private static boolean canDeliver(HarnessAgent agent, RuntimeContext ctx, String filePath) {
        return !deliver(agent, ctx, filePath, null, null).contains("Error");
    }

    private static String deliver(HarnessAgent agent, RuntimeContext ctx,
                                  String filePath, String fileName, String description) {
        Map<String, Object> input = new LinkedHashMap<>();
        input.put("filePath", filePath);
        if (fileName != null) {
            input.put("fileName", fileName);
        }
        if (description != null) {
            input.put("description", description);
        }
        return invoke(agent, ctx, "deliver_artifact", input);
    }

    /** 按工具名调用一次，返回结果文本（等同模型发起的调用） */
    private static String invoke(HarnessAgent agent, RuntimeContext ctx,
                                 String toolName, Map<String, Object> input) {
        AgentTool tool = agent.getToolkit().getTool(toolName);
        ToolResultBlock result = tool.callAsync(ToolCallParam.builder()
                        .toolUseBlock(ToolUseBlock.builder()
                                .id("call-" + toolName)
                                .name(toolName)
                                .input(input)
                                .build())
                        .input(input)
                        .runtimeContext(ctx)
                        .build())
                .block();
        return result == null ? "" : outputText(result);
    }

    /** 工具结果的文本：内容块里只有文本这一种，直接拼出来看 */
    private static String outputText(ToolResultBlock block) {
        StringBuilder sb = new StringBuilder();
        if (block.getOutput() != null) {
            for (var b : block.getOutput()) {
                sb.append(String.valueOf(b)).append(' ');
            }
        }
        return sb.length() == 0 ? String.valueOf(block) : sb.toString();
    }

    /** 隔离检查：两个系统属性缺一不可，否则会写进真实用户目录 */
    private static Path isolate() {
        String dataDir = System.getProperty("fastagent.data.dir");
        String home = System.getProperty("user.home");
        if (dataDir == null || home == null || !Paths.get(dataDir).startsWith(Paths.get(home))) {
            System.err.println("缺少隔离属性，拒绝运行：需要同时给 -Dfastagent.data.dir 与 -Duser.home");
            System.exit(2);
        }
        return Paths.get("").toAbsolutePath();
    }

    private static void check(String what, boolean ok) {
        System.out.println((ok ? "[ok]   " : "[FAIL] ") + what);
        if (!ok) {
            failed++;
        }
    }

    private static void finish() {
        System.out.println(failed == 0 ? "\n全部通过" : "\n失败 " + failed + " 项");
        System.exit(failed == 0 ? 0 : 1);
    }
}
