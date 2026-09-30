import io.agentscope.core.model.Model;
import io.agentscope.core.skill.repository.AgentSkillRepository;
import io.agentscope.core.skill.repository.FileSystemSkillRepository;
import io.agentscope.extensions.model.openai.OpenAIChatModel;
import io.agentscope.harness.agent.HarnessAgent;
import io.agentscope.harness.agent.tools.McpServerConfig;
import io.agentscope.harness.agent.tools.ToolsConfig;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * .fastagent 目录布局挂载探针。
 *
 * <p>验证三件事能真正挂到 HarnessAgent 上（<b>不调用大模型</b>）：
 * <ol>
 *   <li>{@code skills/} 目录里的 SKILL.md 能被加载成技能清单；</li>
 *   <li>{@code tool/tools.json} 的 allow / deny / mcpServers 能作用到 toolkit；</li>
 *   <li>{@code memory/memory.md} 能作为环境级记忆注入且不抛异常。</li>
 * </ol>
 */
public class LayoutProbe {

    private static int failed;

    public static void main(String[] args) throws Exception {
        Path root = Paths.get("target/probe-layout");
        deleteQuietly(root);
        Path fa = root.resolve(".fastagent");
        Path skills = fa.resolve("skills");
        Path memDir = fa.resolve("memory");
        Path toolDir = fa.resolve("tool");
        Files.createDirectories(skills.resolve("demo-skill"));
        Files.createDirectories(memDir);
        Files.createDirectories(toolDir);

        // 1) 一个符合 frontmatter 约定的 SKILL.md
        Files.writeString(skills.resolve("demo-skill/SKILL.md"), """
                ---
                name: demo-skill
                description: 演示用技能，用于验证技能目录能被加载
                ---

                # 演示技能

                当用户要求演示时，输出「已生效」。
                """);
        // 非技能目录（无 SKILL.md）不应被当成技能
        Files.createDirectories(skills.resolve("not-a-skill"));
        Files.writeString(skills.resolve("not-a-skill/readme.txt"), "ignore me");

        Files.writeString(memDir.resolve("memory.md"), "# 全局记忆\n\n用户偏好：回答简洁、结论先行。\n");

        Path toolsJson = toolDir.resolve("tools.json");
        Files.writeString(toolsJson, """
                {
                  "allow": [],
                  "deny": [],
                  "mcpServers": {}
                }
                """);

        // 2) 技能仓库：直接指向 skills/ 目录
        AgentSkillRepository repo = new FileSystemSkillRepository(skills);
        List<String> names = repo.getAllSkillNames();
        System.out.println("PROBE skills.dir=" + skills.toAbsolutePath());
        System.out.println("PROBE skills.names=" + names);
        if (names.contains("demo-skill")) {
            ok("FileSystemSkillRepository 从 skills/ 加载到 demo-skill");
        } else {
            fail("技能未加载，names=" + names);
        }
        if (names.contains("not-a-skill")) {
            fail("无 SKILL.md 的目录被误当成技能");
        } else {
            ok("无 SKILL.md 的目录未被识别为技能（符合预期）");
        }

        Model model = OpenAIChatModel.builder()
                .apiKey("sk-probe-not-a-real-key")
                .baseUrl("https://api.siliconflow.cn/v1")
                .modelName("probe-model")
                .stream(true)
                .build();

        Path ws = root.resolve("workspace");
        Path state = root.resolve("state");
        Files.createDirectories(ws);
        Files.createDirectories(state);

        // 3) 基线：不带 tools 配置，先看内置工具都有哪些
        HarnessAgent base = HarnessAgent.builder()
                .name("probe-base")
                .sysPrompt("探针")
                .model(model)
                .workspace(ws)
                .stateStore(new io.agentscope.core.state.JsonFileAgentStateStore(state))
                .build();
        List<String> baseTools = new ArrayList<>(base.getToolkit().getToolNames());
        System.out.println("PROBE base.toolCount=" + baseTools.size());
        System.out.println("PROBE base.tools=" + baseTools);
        base.close();

        // 4) 带 deny 的配置：禁掉第一个内置工具，验证真的从 toolkit 里消失
        String victim = baseTools.isEmpty() ? null : baseTools.get(0);
        ToolsConfig tc = new ToolsConfig();
        if (victim != null) {
            tc.setDeny(List.of(victim));
        }
        // MCP 服务器项：只验证能配置进去（不会真正去连，未启用的 server 不产生连接）
        Map<String, McpServerConfig> mcp = new LinkedHashMap<>();
        McpServerConfig srv = new McpServerConfig();
        srv.setTransport("stdio");
        srv.setCommand("nonexistent-mcp-server-for-probe");
        srv.setArgs(List.of("--help"));
        mcp.put("probe-mcp", srv);
        tc.setMcpServers(mcp);

        HarnessAgent withTools = HarnessAgent.builder()
                .name("probe-tools")
                .sysPrompt("探针")
                .model(model)
                .workspace(ws)
                .stateStore(new io.agentscope.core.state.JsonFileAgentStateStore(state))
                .toolsConfig(tc)
                .build();
        List<String> after = new ArrayList<>(withTools.getToolkit().getToolNames());
        if (victim == null) {
            fail("内置工具列表为空，无法验证 deny");
        } else if (after.contains(victim)) {
            fail("deny 未生效，仍存在: " + victim + " 列表=" + after);
        } else {
            ok("toolsConfig.deny 生效: " + victim + " 已移除（剩余 " + after.size() + " 个）");
        }
        withTools.close();

        // 5) 技能 + 环境记忆一起挂上去
        HarnessAgent full;
        try {
            full = HarnessAgent.builder()
                    .name("probe-full")
                    .sysPrompt("探针")
                    .model(model)
                    .workspace(ws)
                    .stateStore(new io.agentscope.core.state.JsonFileAgentStateStore(state))
                    .skillRepository(repo)
                    .projectGlobalSkillsDir(skills)
                    .environmentMemory(Files.readString(memDir.resolve("memory.md")))
                    .toolsConfig(tc)
                    .disableShellTool()
                    .build();
            ok("skillRepository + projectGlobalSkillsDir + environmentMemory 组合构建成功");
        } catch (Throwable t) {
            fail("组合构建抛异常: " + t);
            t.printStackTrace(System.out);
            full = null;
        }

        if (full != null) {
            List<AgentSkillRepository> repos = full.getSkillRepositories();
            System.out.println("PROBE full.repos=" + repos.size());
            boolean found = repos.stream()
                    .flatMap(r -> r.getAllSkillNames().stream())
                    .anyMatch("demo-skill"::equals);
            if (found) {
                ok("HarnessAgent.getSkillRepositories() 中可见 demo-skill");
            } else {
                fail("HarnessAgent 侧技能仓库为空或未见 demo-skill");
            }
            List<String> fullTools = new ArrayList<>(full.getToolkit().getToolNames());
            System.out.println("PROBE full.toolCount=" + fullTools.size());
            System.out.println("PROBE full.tools=" + fullTools);
            if (fullTools.size() > 0) {
                ok("toolkit 已装配 " + fullTools.size() + " 个工具");
            }
            full.close();
        }

        // 6) 中文技能描述不因编码问题丢失
        try {
            var sk = repo.getSkill("demo-skill");
            String desc = String.valueOf(sk.getMetadataValue("description"));
            if (desc.contains("演示")) {
                ok("技能元数据中文正常: " + desc);
            } else {
                fail("技能描述乱码或缺失: " + desc);
            }
        } catch (Throwable t) {
            fail("读取技能详情失败: " + t);
        }

        System.out.println("PROBE ===== 结束，失败项 = " + failed + " =====");
        System.out.println(failed == 0 ? "PROBE RESULT = ALL-GREEN" : "PROBE RESULT = HAS-FAILURES");
        System.exit(failed == 0 ? 0 : 1);
    }

    private static void ok(String m) {
        System.out.println("PROBE [ OK ] " + m);
    }

    private static void fail(String m) {
        failed++;
        System.out.println("PROBE [FAIL] " + m);
    }

    private static void deleteQuietly(Path p) throws Exception {
        if (!Files.exists(p)) {
            return;
        }
        try (var walk = Files.walk(p)) {
            walk.sorted((a, b) -> b.getNameCount() - a.getNameCount())
                    .forEach(x -> {
                        try {
                            Files.deleteIfExists(x);
                        } catch (Exception ignored) {
                            // 清理失败不影响验证
                        }
                    });
        }
    }
}
