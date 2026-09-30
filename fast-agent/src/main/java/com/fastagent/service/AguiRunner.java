package com.fastagent.service;

import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.agui.adapter.AguiAdapterConfig;
import io.agentscope.core.agui.adapter.AguiAgentAdapter;
import io.agentscope.core.agui.event.AguiEvent;
import io.agentscope.core.agui.model.AguiMessage;
import io.agentscope.core.agui.model.RunAgentInput;
import reactor.core.publisher.Flux;

import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * AG-UI 协议适配：把 AgentScope 的 {@code AgentEvent} 流转成 AG-UI 标准事件。
 *
 * <p>用官方的 {@link AguiAgentAdapter} 而不是自己写映射表 —— 它对 {@code HarnessAgent}
 * 有专门分支（反射调 {@code streamEvents(List, RuntimeContext)}），并且把
 * 「工具调用 / 推理内容 / token 用量 / HITL interrupt / 子 agent」的语义都处理好了。
 * 本类只负责两件官方适配器不知道的事：
 *
 * <ol>
 *   <li><b>只下发本轮用户消息</b>。AG-UI 的 {@code messages} 会被
 *       {@code AguiMessageConverter.toMsgList()} 整体转成 {@code List<Msg>} 交给 agent；
 *       而本工程的历史由 AgentScope 的会话状态（{@code RuntimeContext} 的 userId/sessionId
 *       + {@code JsonFileAgentStateStore}）维护。若把前端带来的历史全量下发，历史会重复。</li>
 *   <li><b>带上 userId / sessionId</b>。AG-UI 协议本身没有「用户」概念，
 *       而 AgentScope 的工作空间隔离（{@code .jagentspace/<userId>/}）全靠 RuntimeContext，
 *       所以必须由调用方显式注入 —— 见 {@code AguiAgentAdapter.run(input, runtimeContext)}
 *       的重载（适配器会先复制调用方 context，再覆盖 AG-UI 的协议元数据）。</li>
 * </ol>
 *
 * <p>协议细节见 <a href="https://agentclientprotocol.com">AG-UI 规范</a>与
 * AgentScope 文档：{@code java.agentscope.io/v2/zh/integration/protocol/agui}。
 */
public final class AguiRunner {

    /**
     * 适配器配置。与现有行为保持一致：
     * <ul>
     *   <li>{@code enableReasoning} —— 推理内容走 {@code REASONING_MESSAGE_*} 事件
     *       （原 SSE 协议里是 {@code delta.reasoning}）；</li>
     *   <li>{@code emitTokenUsage} —— 发 {@code CUSTOM(name=token_usage)}，
     *       前端据此渲染答案下方那行「耗时 / token」小字；</li>
     *   <li>{@code emitToolCallArgs} —— 工具参数增量（{@code TOOL_CALL_ARGS}），
     *       前端工具卡片要展示调用参数；</li>
     *   <li>{@code emitStateEvents=false} —— 本工程没有要同步给前端的「共享状态」，
     *       会话内容另有 {@code /api/chat/snapshot} 兜底，避免发无意义事件；</li>
     *   <li>{@code runTimeout} —— 单轮最长执行时间，超时转成 {@code RUN_ERROR}。
     *       取 30 分钟：原 SSE 通道是不超时的，这里只做兜底，不改变日常行为。</li>
     * </ul>
     */
    private static final AguiAdapterConfig CONFIG = AguiAdapterConfig.builder()
            .enableReasoning(true)
            .emitTokenUsage(true)
            .emitToolCallArgs(true)
            .emitStateEvents(false)
            .runTimeout(Duration.ofMinutes(30))
            .build();

    private AguiRunner() {
    }

    /**
     * 发起一轮 AG-UI run。
     *
     * @param agent     目标 agent（本工程为 {@code HarnessAgent}）
     * @param threadId  会话 id，会作为 AgentScope 的 sessionId
     * @param runId     本轮 id，由调用方生成（前端也会拿到，用于关联事件）
     * @param text      本轮用户输入
     * @param userId    登录用户，决定工作空间数据落在 {@code .jagentspace/<userId>/} 下
     * @param sessionId 会话 id，与 {@code threadId} 同值
     * @param extra     附加到 RuntimeContext 的键值（如 workspaceId / modelId），供自定义 converter 读取
     * @return AG-UI 事件流；订阅时才开始执行
     */
    public static Flux<AguiEvent> run(Agent agent, String threadId, String runId, String text,
                                      String userId, String sessionId, Map<String, Object> extra) {
        RunAgentInput input = RunAgentInput.builder()
                .threadId(threadId)
                .runId(runId)
                // 只放本轮这一条：历史由服务端会话状态维护，见类注释
                .messages(List.of(AguiMessage.userMessage("msg-" + runId, text)))
                .build();

        RuntimeContext.Builder ctx = RuntimeContext.builder()
                .userId(userId)
                .sessionId(sessionId);
        if (extra != null) {
            extra.forEach(ctx::put);
        }

        // adapter 是无状态的（只持有 agent 引用与几个转换器），每轮新建即可
        return new AguiAgentAdapter(agent, CONFIG).run(input, ctx.build());
    }
}
