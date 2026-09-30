import io.agentscope.core.model.Model;
import io.agentscope.core.state.JsonFileAgentStateStore;
import io.agentscope.extensions.model.openai.OpenAIChatModel;
import io.agentscope.harness.agent.HarnessAgent;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.TreeSet;

/**
 * 子 agent 能力探针。
 *
 * <p>只回答一个问题：<b>按本项目 AgentService 的构建方式，主 agent 手上到底有没有
 * 「派生子 agent」的工具</b>。不调模型、不联网。
 *
 * <p>做法：
 * <ol>
 *   <li>照 AgentService.build() 的方式构建 HarnessAgent（同样不调 disableSubagents）；</li>
 *   <li>直接读 ReActAgent 的 Toolkit，列出注册进工具箱的工具名；</li>
 *   <li>再构建一个显式 disableSubagents() 的对照，看差集是什么。</li>
 * </ol>
 *
 * <pre>
 * mvn -q dependency:build-classpath "-Dmdep.outputFile=target/cp.txt"
 * javac -encoding UTF-8 -cp "$(cat target/cp.txt)" -d target/probe tools/SubagentProbe.java
 * java -cp "target/probe;$(cat target/cp.txt)" SubagentProbe
 * </pre>
 */
public class SubagentProbe {

    public static void main(String[] args) throws Exception {
        System.out.println("PROBE ===== 子 agent 能力探针 =====");

        Path root = Paths.get("target/subagent-probe");
        Path ws = root.resolve("workspace");
        Path state = root.resolve("state");
        Files.createDirectories(ws);
        Files.createDirectories(state);

        Model model = OpenAIChatModel.builder()
                .apiKey("sk-probe-not-a-real-key")
                .baseUrl("https://api.siliconflow.cn/v1")
                .modelName("deepseek-ai/DeepSeek-V4-Flash")
                .stream(true)
                .build();

        TreeSet<String> defaults = tools(HarnessAgent.builder()
                .name("probe-default")
                .sysPrompt("探针")
                .model(model)
                .workspace(ws)
                .stateStore(new JsonFileAgentStateStore(state))
                .build());

        TreeSet<String> disabled = tools(HarnessAgent.builder()
                .name("probe-disabled")
                .sysPrompt("探针")
                .model(model)
                .workspace(ws)
                .stateStore(new JsonFileAgentStateStore(state))
                .disableSubagents()
                .build());

        System.out.println("PROBE 默认构建 工具数=" + defaults.size() + " -> " + defaults);
        System.out.println("PROBE 禁用子agent 工具数=" + disabled.size() + " -> " + disabled);

        TreeSet<String> diff = new TreeSet<>(defaults);
        diff.removeAll(disabled);
        System.out.println("PROBE 因「子 agent」而多出来的工具 = " + diff);
        System.out.println("PROBE RESULT = " + (diff.isEmpty() ? "NO-SUBAGENT-TOOLS" : "HAS-SUBAGENT-TOOLS"));

        // 顺带看一眼工作空间里会不会被读到的 subagents 声明目录
        Path agentsDir = ws.resolve("agents");
        System.out.println("PROBE subagents 声明目录=" + agentsDir.toAbsolutePath()
                + " 存在=" + Files.isDirectory(agentsDir));
    }

    private static TreeSet<String> tools(HarnessAgent agent) {
        return new TreeSet<>(agent.getDelegate().getToolkit().getToolNames());
    }
}
