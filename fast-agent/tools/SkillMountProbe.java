import io.agentscope.core.model.Model;
import io.agentscope.core.skill.repository.AgentSkillRepository;
import io.agentscope.core.skill.repository.FileSystemSkillRepository;
import io.agentscope.core.state.JsonFileAgentStateStore;
import io.agentscope.extensions.model.openai.OpenAIChatModel;
import io.agentscope.harness.agent.HarnessAgent;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

/**
 * 技能挂载方式对比探针。
 *
 * <p>目的：确认 {@code skillRepository(...)} 与 {@code projectGlobalSkillsDir(...)}
 * 分别挂载时技能是否真的生效、以及两者同时用时会不会把同一个技能注册两遍
 * （重复注册会导致技能清单在提示词里出现两次）。
 */
public class SkillMountProbe {

    private static int failed;

    public static void main(String[] args) throws Exception {
        Path root = Paths.get("target/probe-skillmount");
        deleteQuietly(root);
        Path fa = root.resolve(".fastagent");
        Path skills = fa.resolve("skills");
        Files.createDirectories(skills.resolve("alpha"));
        Files.createDirectories(skills.resolve("beta"));
        Files.createDirectories(fa.resolve("ws"));
        Files.createDirectories(fa.resolve("state"));
        write(skills.resolve("alpha/SKILL.md"), "alpha", "甲技能：处理甲类事务");
        write(skills.resolve("beta/SKILL.md"), "beta", "乙技能：处理乙类事务");

        Model model = OpenAIChatModel.builder()
                .apiKey("sk-probe")
                .baseUrl("https://api.siliconflow.cn/v1")
                .modelName("probe")
                .stream(true)
                .build();

        report("A: 仅 skillRepository", build(model, fa, skills, Mode.REPO));
        report("B: 仅 projectGlobalSkillsDir", build(model, fa, skills, Mode.GLOBAL_DIR));
        report("C: 两者同时", build(model, fa, skills, Mode.BOTH));
        report("D: 都不挂载（基线）", build(model, fa, skills, Mode.NONE));

        System.out.println("PROBE ===== 结束，失败项 = " + failed + " =====");
        System.out.println(failed == 0 ? "PROBE RESULT = ALL-GREEN" : "PROBE RESULT = HAS-FAILURES");
        System.exit(failed == 0 ? 0 : 1);
    }

    private enum Mode { REPO, GLOBAL_DIR, BOTH, NONE }

    private static HarnessAgent build(Model model, Path fa, Path skills, Mode mode) {
        HarnessAgent.Builder b = HarnessAgent.builder()
                .name("probe-" + mode)
                .sysPrompt("探针")
                .model(model)
                .workspace(fa.resolve("ws"))
                .stateStore(new JsonFileAgentStateStore(fa.resolve("state")));
        if (mode == Mode.REPO || mode == Mode.BOTH) {
            b.skillRepository(new FileSystemSkillRepository(skills));
        }
        if (mode == Mode.GLOBAL_DIR || mode == Mode.BOTH) {
            b.projectGlobalSkillsDir(skills);
        }
        return b.build();
    }

    /** 统计「每个技能在各仓库里出现的次数」——用来判断有没有重复注册 */
    private static void report(String label, HarnessAgent agent) {
        List<AgentSkillRepository> repos = agent.getSkillRepositories();
        int alpha = 0;
        int beta = 0;
        StringBuilder src = new StringBuilder();
        for (AgentSkillRepository r : repos) {
            List<String> names = r.getAllSkillNames();
            src.append('[').append(r.getClass().getSimpleName()).append(" n=").append(names.size()).append("] ");
            if (names.contains("alpha")) {
                alpha++;
            }
            if (names.contains("beta")) {
                beta++;
            }
        }
        System.out.println("PROBE " + label + " repos=" + repos.size() + " " + src);
        System.out.println("PROBE " + label + " alpha命中仓库数=" + alpha + " beta命中仓库数=" + beta);
        if (alpha > 1) {
            fail(label + "：alpha 在 " + alpha + " 个仓库里重复出现（重复注册）");
        }
        agent.close();
    }

    private static void write(Path p, String name, String desc) throws Exception {
        Files.writeString(p, """
                ---
                name: %s
                description: %s
                ---

                # %s

                正文。
                """.formatted(name, desc, name));
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
            walk.sorted((a, b) -> b.getNameCount() - a.getNameCount()).forEach(x -> {
                try {
                    Files.deleteIfExists(x);
                } catch (Exception ignored) {
                    // 清理失败不影响验证
                }
            });
        }
    }
}
