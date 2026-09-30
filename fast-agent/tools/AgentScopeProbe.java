import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.model.Model;
import io.agentscope.core.state.JsonFileAgentStateStore;
import io.agentscope.extensions.model.openai.OpenAIChatModel;
import io.agentscope.harness.agent.HarnessAgent;
import io.agentscope.harness.agent.memory.compaction.CompactionConfig;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * AgentScope 接入可行性探针。
 *
 * <p>只验证「依赖能加载、对象能构建、目录能落盘」，<b>不实际调用大模型</b> ——
 * 避免消耗额度，也避免把网络问题混进构造期验证。
 *
 * <p>编译与运行（classpath 由 maven 导出）：
 * <pre>
 * mvn -q dependency:build-classpath "-Dmdep.outputFile=target/cp.txt"
 * javac -encoding UTF-8 -cp "$(cat target/cp.txt)" -d target/probe tools/AgentScopeProbe.java
 * java -cp "target/probe;$(cat target/cp.txt)" AgentScopeProbe
 * </pre>
 */
public class AgentScopeProbe {

    private static int failed;

    public static void main(String[] args) throws Exception {
        System.out.println("PROBE ===== AgentScope 接入探针 =====");

        // 1) 用自定义 baseUrl 构建 OpenAI 兼容模型（对接硅基流动）
        Model model = OpenAIChatModel.builder()
                .apiKey("sk-probe-not-a-real-key")
                .baseUrl("https://api.siliconflow.cn/v1")
                .modelName("deepseek-ai/DeepSeek-V4-Flash")
                .stream(true)
                .build();
        ok("OpenAIChatModel 构建成功: " + model.getClass().getName());

        // 2) 构建 HarnessAgent：workspace 与 stateStore 都落在工程目录下，
        //    不污染用户主目录（AgentScope 默认会写 ~/.agentscope）
        Path root = Paths.get("target/agentscope-probe");
        Path ws = root.resolve("workspace");
        Path state = root.resolve("state");
        Files.createDirectories(ws);
        Files.createDirectories(state);

        HarnessAgent agent = HarnessAgent.builder()
                .name("probe-agent")
                .sysPrompt("你是一个接入探针。")
                .model(model)
                .workspace(ws)
                .stateStore(new JsonFileAgentStateStore(state))
                .compaction(CompactionConfig.builder()
                        .triggerMessages(30)
                        .keepMessages(10)
                        .build())
                .build();
        ok("HarnessAgent 构建成功");

        // 3) RuntimeContext 的 (userId, sessionId) 是会话隔离与持久化寻址的关键
        RuntimeContext ctx = RuntimeContext.builder()
                .userId("cjh")
                .sessionId("s-probe-1")
                .build();
        ok("RuntimeContext: userId=" + ctx.getUserId() + " sessionId=" + ctx.getSessionId());

        // 4) workspace 目录落盘情况
        try (var s = Files.list(ws)) {
            ok("workspace 目录 path=" + ws.toAbsolutePath() + " 文件数=" + s.count());
        }

        // 5) 未产生对话时读状态应为空。
        //    注意：HarnessAgent 只暴露无参 getAgentState()，
        //    按 (userId, sessionId) 读取要走它内部的 ReActAgent
        try {
            var st = agent.getDelegate().getAgentState("cjh", "s-probe-1");
            if (st == null) {
                ok("getAgentState(userId, sessionId) 返回 null（尚无历史，符合预期）");
            } else {
                ok("getAgentState 可读, context.size=" + st.getContext().size());
            }
        } catch (Throwable t) {
            fail("getAgentState 异常: " + t);
        }

        // 6) 按 (userId, sessionId) 取工作区管理器 —— 读会话级记录的入口
        try {
            var wm = agent.workspaceFor("cjh", "s-probe-1");
            ok("workspaceFor 返回 " + (wm == null ? "null" : wm.getClass().getSimpleName()));
        } catch (Throwable t) {
            fail("workspaceFor 异常: " + t);
        }

        // 7) 中断接口（对应「停止生成」）—— 无 in-flight 调用时应安全返回
        try {
            agent.interrupt("cjh", "s-probe-1");
            ok("interrupt(userId, sessionId) 可用");
        } catch (Throwable t) {
            fail("interrupt 异常: " + t);
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
}
