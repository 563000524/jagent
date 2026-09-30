package com.fastagent.service;

import com.fastagent.AppLog;
import com.fastagent.agent.AgentService;
import com.fastagent.artifact.ArtifactService;
import com.fastagent.model.ModelEntry;
import com.fastagent.model.ModelsService;
import com.fastagent.workspace.SessionInfo;
import com.fastagent.workspace.SessionService;
import com.fastagent.workspace.WorkspaceService;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.agui.event.AguiEvent;
import io.agentscope.core.event.ModelCallEndEvent;
import io.agentscope.core.event.TextBlockDeltaEvent;
import io.agentscope.core.event.ThinkingBlockDeltaEvent;
import io.agentscope.core.event.ToolCallStartEvent;
import io.agentscope.core.message.UserMessage;
import io.agentscope.core.model.ChatUsage;
import io.agentscope.harness.agent.HarnessAgent;
import jakarta.annotation.PreDestroy;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 对话编排：会话准备 → HarnessAgent 推理 → 增量回调 → 状态快照。
 *
 * <p>推理完全交给 AgentScope 的 HarnessAgent：
 * <ul>
 *   <li>会话隔离与持久化由 {@code RuntimeContext(userId, sessionId)} 驱动；</li>
 *   <li>工作空间级记忆（AGENTS.md / MEMORY.md / memory）由 harness 自己维护；</li>
 *   <li>停止生成走 {@code agent.interrupt(userId, sessionId)}。</li>
 * </ul>
 *
 * <p>本类只做三件事：校验入参、把 {@code streamEvents} 的事件翻成
 * {@link ChatListener} 回调、维护给前端兜底用的状态快照。
 */
@Service
public class ChatService {

    private final ModelsService modelsService;
    private final AgentService agents;
    private final SessionService sessions;
    private final WorkspaceService workspaces;
    private final ArtifactService artifacts;

    /** 流式任务用虚拟线程：AgentScope 的 Flux 在这里被同步消费 */
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();

    /** key = workspaceId/sessionId -> 状态快照 */
    private final Map<String, StreamState> states = new ConcurrentHashMap<>();
    /** 被用户主动停止的会话 key，用于把「中断」与「失败」区分开 */
    private final Set<String> stopping = ConcurrentHashMap.newKeySet();

    public ChatService(ModelsService modelsService,
                       AgentService agents,
                       SessionService sessions,
                       WorkspaceService workspaces,
                       ArtifactService artifacts) {
        this.modelsService = modelsService;
        this.agents = agents;
        this.sessions = sessions;
        this.workspaces = workspaces;
        this.artifacts = artifacts;
        // 模型配置是按用户存的，启动时还不知道谁来登录，因此这里不打具体模型
        AppLog.info("ChatService 就绪");
    }

    /** 模型 / 技能 / 工具 / 记忆变更后调用：丢弃该用户的 agent，下次对话按新配置重建 */
    public void invalidateAgents(String userId) {
        agents.invalidateUser(userId);
    }

    // ---------- 流式对话 ----------

    /**
     * 受理流式对话。
     *
     * <p>校验不通过时<b>同步</b>抛 {@link ApiException}，由 GlobalExceptionHandler
     * 返回 400 + JSON —— 前端能 catch 到可读原因，而不是拿到一个看似成功的空流。
     *
     * @param modelId  本轮指定的模型；为空则用该会话上次用的，再没有就用全局默认
     * @return 实际使用的 sessionId（传空串时会新建会话）
     */
    public String startStream(String workspaceId, String userId, String sessionId,
                             String text, String modelId, ChatListener listener) {
        Prepared p = prepare(workspaceId, userId, sessionId, text, modelId);
        String realId = p.sessionId();
        String effective = p.modelId();
        AppLog.info("受理对话: workspace=%s user=%s session=%s model=%s text长度=%d",
                workspaceId, userId, realId, effective, text.length());

        // 同一会话若在生成中，先中断
        stopIfRunning(workspaceId, userId, realId);

        resetState(key(userId, workspaceId, realId), realId);
        // 登记「本会话属于哪个工作空间」：交付文件时只有 (userId, sessionId)，靠这条查回来
        artifacts.beginRun(userId, workspaceId, realId);

        ChatListener l = listener == null ? ChatListener.NOOP : listener;
        executor.submit(() -> generate(workspaceId, userId, realId, text, effective, l));
        return realId;
    }

    // ---------- AG-UI 通道 ----------

    /**
     * AG-UI 协议的对话入口，对应 {@code POST /api/agui/run}。
     *
     * <p>与 {@link #startStream} 的区别只在传输层：会话准备、模型解析、状态快照、
     * 停止生成全部复用；事件由 {@link AguiRunner} 按 AG-UI 规范产出，
     * 由 {@code AguiController} 编码成 SSE。
     *
     * <p>返回的 {@link Flux} <b>冷启动</b>：订阅时才真正开始推理，所以校验异常
     * （消息为空、没配模型、工作空间不属于该用户）会在本方法内同步抛出，
     * 由 {@link com.fastagent.web.GlobalExceptionHandler} 返回 400 + JSON。
     *
     * @param threadId AG-UI 的会话 id，直接作为 AgentScope 的 sessionId
     * @param runId    本轮 id，由前端生成
     */
    public Flux<AguiEvent> startAguiRun(String workspaceId, String userId, String threadId,
                                        String text, String modelId, String runId) {
        Prepared p = prepare(workspaceId, userId, threadId, text, modelId);
        String realId = p.sessionId();
        AppLog.info("受理 AG-UI run: workspace=%s user=%s session=%s run=%s model=%s text长度=%d",
                workspaceId, userId, realId, runId, p.modelId(), text.length());

        // 同一会话若在生成中，先中断
        stopIfRunning(workspaceId, userId, realId);

        String key = key(userId, workspaceId, realId);
        resetState(key, realId);
        // 同 startStream：交付文件时靠这条登记查回工作空间
        artifacts.beginRun(userId, workspaceId, realId);

        HarnessAgent agent = agents.agentFor(userId, workspaceId, p.modelId());
        long start = System.currentTimeMillis();
        Map<String, Object> extra = Map.of(
                "workspaceId", workspaceId,
                "modelId", p.modelId() == null ? "" : p.modelId());

        return AguiRunner.run(agent, realId, runId, text, userId, realId, extra)
                // 旁路维护状态快照：AG-UI 是发给前端的主通道，快照是轮询兜底通道
                .doOnNext(ev -> track(userId, key, realId, ev, start))
                .doOnError(e -> AppLog.error(e, "AG-UI run 异常: workspace=%s session=%s",
                        workspaceId, realId));
    }

    /**
     * 旁路消费 AG-UI 事件，维护 {@link StreamState}。
     *
     * <p>让原有的 {@code /api/chat/snapshot} 兜底链路（事件通道失效时前端轮询补齐）
     * 对 AG-UI 通道同样有效。这里<b>只读事件、不改事件</b>，异常也必须吞掉 ——
     * 否则前端界面的一个小问题会把整轮回答废掉。
     */
    private void track(String userId, String key, String sessionId, AguiEvent ev, long start) {
        try {
            switch (ev.getType()) {
                case RUN_STARTED -> updateState(key, sessionId, s -> {
                    s.setRunning(true);
                    s.setError("");
                });
                case TEXT_MESSAGE_CONTENT -> {
                    String d = ((AguiEvent.TextMessageContent) ev).delta();
                    if (d != null && !d.isEmpty()) {
                        updateState(key, sessionId, s -> s.setContent(s.getContent() + d));
                    }
                }
                case REASONING_MESSAGE_CONTENT -> {
                    String d = ((AguiEvent.ReasoningMessageContent) ev).delta();
                    if (d != null && !d.isEmpty()) {
                        updateState(key, sessionId, s -> s.setReasoning(s.getReasoning() + d));
                    }
                }
                case TOOL_CALL_START -> {
                    AguiEvent.ToolCallStart tcStart = (AguiEvent.ToolCallStart) ev;
                    AppLog.info("工具调用: session=%s tool=%s", sessionId, tcStart.toolCallName());
                    // 文件类工具的参数随后到，攒起来解析出 path —— 卡片上「读取/写入/编辑」那类靠它
                    artifacts.toolCallStarted(userId, sessionId, tcStart.toolCallId(), tcStart.toolCallName());
                }
                case TOOL_CALL_ARGS -> {
                    AguiEvent.ToolCallArgs args = (AguiEvent.ToolCallArgs) ev;
                    artifacts.toolCallArgs(userId, sessionId, args.toolCallId(), args.delta());
                }
                case TOOL_CALL_RESULT -> {
                    // 记文件引用要在结果事件上：TOOL_CALL_END 时工具还没执行，
                    // write_file 的目标文件还不存在，那时解析路径一个都命中不了
                    AguiEvent.ToolCallResult result = (AguiEvent.ToolCallResult) ev;
                    artifacts.toolCallCompleted(userId, workspaceIdOf(key), sessionId, result.toolCallId());
                }
                case CUSTOM -> trackUsage(key, sessionId, (AguiEvent.Custom) ev);
                case RUN_ERROR -> {
                    AguiEvent.RunError err = (AguiEvent.RunError) ev;
                    String msg = err.message() == null ? "调用失败" : err.message();
                    long elapsed = System.currentTimeMillis() - start;
                    stopping.remove(key);
                    updateState(key, sessionId, s -> {
                        s.setRunning(false);
                        s.setError(msg);
                        s.setSeq(s.getSeq() + 1);
                        s.setDurationMs(elapsed);
                    });
                    AppLog.error("AG-UI run 失败: session=%s code=%s message=%s",
                            sessionId, err.code(), msg);
                    artifacts.endRun(userId, sessionId);
                }
                case RUN_FINISHED -> {
                    // Set.remove 返回 boolean：true 表示这个 session 确实被标记过「用户停止」
                    boolean stopped = stopping.remove(key);
                    long elapsed = System.currentTimeMillis() - start;
                    updateState(key, sessionId, s -> {
                        s.setRunning(false);
                        s.setSeq(s.getSeq() + 1);
                        s.setDurationMs(elapsed);
                    });
                    artifacts.endRun(userId, sessionId);
                    // key = userId/workspaceId/sessionId，中段是 workspaceId
                    AppLog.info("%s: workspace=%s session=%s 耗时=%dms",
                            stopped ? "AG-UI 生成已被用户停止" : "AG-UI 生成成功",
                            workspaceIdOf(key), sessionId, elapsed);
                }
                default -> {
                    // 其余事件（文本分片起止、工具参数/结果、步骤、推理起止等）只给前端渲染，
                    // 不影响状态快照
                }
            }
        } catch (Exception e) {
            AppLog.warn("AG-UI 事件旁路处理异常（已忽略）: %s", e);
        }
    }

    /**
     * 解析 {@code CUSTOM(name=token_usage)} 事件，取「本次 run 累计」的用量。
     *
     * <p>官方适配器给的 value 是 {@code Map{delta: Map, cumulative: Map, replyId, modelCall}}，
     * 里面的键形如 {@code inputTokens / outputTokens / totalTokens}。
     * 结构随版本可能变，故全部走容错解析，取不到就保持原值。
     */
    private void trackUsage(String key, String sessionId, AguiEvent.Custom custom) {
        if (!"token_usage".equals(custom.name()) || !(custom.value() instanceof Map<?, ?> value)) {
            return;
        }
        if (!(value.get("cumulative") instanceof Map<?, ?> cumulative)) {
            return;
        }
        int in = intOf(cumulative.get("inputTokens"));
        int out = intOf(cumulative.get("outputTokens"));
        int total = intOf(cumulative.get("totalTokens"));
        updateState(key, sessionId, s -> {
            s.setInputTokens(in);
            s.setOutputTokens(out);
            s.setTotalTokens(total);
        });
    }

    private static int intOf(Object o) {
        return o instanceof Number n ? n.intValue() : 0;
    }

    // ---------- 两个通道共用的前置步骤 ----------

    /** 会话准备的结果：真实 sessionId 与本轮生效的模型 */
    private record Prepared(String sessionId, String modelId) {
    }

    /**
     * 校验入参、准备会话、解析本轮模型。自有 SSE 与 AG-UI 两条通道共用。
     *
     * <p>校验不通过时同步抛 {@link ApiException}，由 GlobalExceptionHandler
     * 返回 400 + JSON —— 前端能 catch 到可读原因，而不是拿到一个看似成功的空流。
     */
    private Prepared prepare(String workspaceId, String userId, String sessionId,
                             String text, String modelId) {
        if (text == null || text.isBlank()) {
            throw new ApiException(400, "消息内容为空");
        }
        if (!modelsService.hasUsableModel(userId)) {
            throw new ApiException(400, "尚未配置可用的模型，请先在「模型配置」里添加模型并填写 API Key");
        }
        if (workspaceId == null || workspaceId.isBlank()) {
            throw new ApiException(400, "缺少 workspaceId");
        }
        workspaces.require(userId, workspaceId);

        SessionInfo info = sessions.ensure(userId, workspaceId, sessionId, text);
        String realId = info.getSessionId();

        // 生效模型：本轮指定 > 该会话上次用的 > 全局默认。记回会话索引，
        // 这样重新进入这个会话时能恢复上次选的模型（新会话则取默认）。
        String effective = resolveModel(userId, info, modelId);
        if (effective != null && !effective.equals(info.getModelId())) {
            sessions.setModel(userId, workspaceId, realId, effective);
        }
        return new Prepared(realId, effective);
    }

    /** 开始新一轮前重置状态快照 */
    private void resetState(String key, String sessionId) {
        updateState(key, sessionId, s -> {
            s.setContent("");
            s.setReasoning("");
            s.setError("");
            s.setRunning(true);
            s.setSeq(s.getSeq() + 1);
            // 清掉上一轮的统计，否则新一轮生成期间会显示上一轮的耗时与 token
            s.setDurationMs(0L);
            s.setInputTokens(0);
            s.setOutputTokens(0);
            s.setTotalTokens(0);
        });
    }

    /**
     * 决定这轮对话用哪个模型。
     *
     * <p>指定或记录的那条被删掉、或没配 Key 时<b>静默回落</b>到默认模型 ——
     * 否则「删了一个模型，用到它的会话就全废了」。
     */
    private String resolveModel(String userId, SessionInfo info, String requested) {
        ModelEntry m = modelsService.byId(userId, requested);
        if (m != null && m.usable()) {
            return m.getId();
        }
        if (info != null) {
            ModelEntry saved = modelsService.byId(userId, info.getModelId());
            if (saved != null && saved.usable()) {
                return saved.getId();
            }
        }
        ModelEntry def = modelsService.active(userId);
        return def == null ? null : def.getId();
    }

    private void generate(String workspaceId, String userId, String sessionId,
                          String text, String modelId, ChatListener listener) {
        String key = key(userId, workspaceId, sessionId);
        long start = System.currentTimeMillis();
        StringBuilder content = new StringBuilder();
        StringBuilder reasoning = new StringBuilder();
        List<String> tools = new ArrayList<>();
        int[] deltas = {0};
        // 累计 token：in / out / total。一轮里可能发生多次模型调用，所以是累加而不是覆盖
        int[] tokens = {0, 0, 0};

        try {
            notifyStart(listener, sessionId);
            HarnessAgent agent = agents.agentFor(userId, workspaceId, modelId);
            RuntimeContext ctx = RuntimeContext.builder()
                    .userId(userId)
                    .sessionId(sessionId)
                    .build();

            // Flux -> 同步迭代：虚拟线程里顺序消费，逻辑直观且便于中断后收尾
            agent.streamEvents(new UserMessage(text), ctx)
                    .toIterable()
                    .forEach(event -> {
                        switch (event.getType()) {
                            case TEXT_BLOCK_DELTA -> {
                                String d = ((TextBlockDeltaEvent) event).getDelta();
                                if (d == null || d.isEmpty()) {
                                    return;
                                }
                                deltas[0]++;
                                content.append(d);
                                updateState(key, sessionId, s -> s.setContent(s.getContent() + d));
                                notifyDelta(listener, sessionId, d, "");
                            }
                            case THINKING_BLOCK_DELTA -> {
                                String d = ((ThinkingBlockDeltaEvent) event).getDelta();
                                if (d == null || d.isEmpty()) {
                                    return;
                                }
                                reasoning.append(d);
                                updateState(key, sessionId, s -> s.setReasoning(s.getReasoning() + d));
                                notifyDelta(listener, sessionId, "", d);
                            }
                            case TOOL_CALL_START -> {
                                String name = ((ToolCallStartEvent) event).getToolCallName();
                                if (name != null && !tools.contains(name)) {
                                    tools.add(name);
                                }
                                AppLog.info("工具调用: session=%s tool=%s", sessionId, name);
                            }
                            case MODEL_CALL_END -> {
                                // 用量随每次模型调用结束产生，一轮多次调用要累加
                                ChatUsage u = ((ModelCallEndEvent) event).getUsage();
                                if (u != null) {
                                    tokens[0] += u.getInputTokens();
                                    tokens[1] += u.getOutputTokens();
                                    tokens[2] += u.getTotalTokens();
                                    AppLog.info("模型调用用量: session=%s in=%d out=%d total=%d cached=%d 模型自报耗时=%.3f",
                                            sessionId, u.getInputTokens(), u.getOutputTokens(),
                                            u.getTotalTokens(), u.getCachedTokens(), u.getTime());
                                }
                            }
                            default -> {
                                // 其余事件（模型调用、块结束、任务等）暂不向前端暴露
                            }
                        }
                    });

            String finalContent = content.toString();
            String finalReasoning = reasoning.toString();
            // Set.remove 返回 boolean：true 表示这个 session 确实被标记过「用户停止」
            boolean stopped = stopping.remove(key);

            long elapsed = System.currentTimeMillis() - start;
            ChatStats stats = new ChatStats(elapsed, tokens[0], tokens[1], tokens[2]);
            updateState(key, sessionId, s -> {
                s.setRunning(false);
                s.setError("");
                s.setSeq(s.getSeq() + 1);
                // 一并存进快照：轮询兜底路径（事件通道失效时）也要能显示耗时与 token
                s.setDurationMs(elapsed);
                s.setInputTokens(tokens[0]);
                s.setOutputTokens(tokens[1]);
                s.setTotalTokens(tokens[2]);
            });
            if (stopped) {
                AppLog.info("生成已被用户停止: workspace=%s session=%s 正文=%d字 耗时=%dms",
                        workspaceId, sessionId, finalContent.length(), elapsed);
            } else {
                AppLog.info("生成成功: workspace=%s session=%s 增量=%d 正文=%d字 思考=%d字 工具=%s token=%d/%d/%d 耗时=%dms",
                        workspaceId, sessionId, deltas[0], finalContent.length(), finalReasoning.length(),
                        tools, tokens[0], tokens[1], tokens[2], elapsed);
            }
            notifyDone(listener, sessionId, finalContent, finalReasoning, stats);
        } catch (Exception e) {
            // Set.remove 返回 boolean：true 表示这个 session 确实被标记过「用户停止」
            boolean stopped = stopping.remove(key);
            updateState(key, sessionId, s -> {
                s.setRunning(false);
                s.setSeq(s.getSeq() + 1);
            });
            if (stopped) {
                String partial = content.toString();
                long elapsed = System.currentTimeMillis() - start;
                AppLog.info("生成已停止（流被中断）: workspace=%s session=%s 正文=%d字 耗时=%dms",
                        workspaceId, sessionId, partial.length(), elapsed);
                notifyDone(listener, sessionId, partial, reasoning.toString(),
                        new ChatStats(elapsed, tokens[0], tokens[1], tokens[2]));
                return;
            }
            String message = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            updateState(key, sessionId, s -> s.setError(message));
            AppLog.error(e, "生成失败: workspace=%s session=%s 增量=%d 耗时=%dms",
                    workspaceId, sessionId, deltas[0], System.currentTimeMillis() - start);
            notifyError(listener, sessionId, message);
        } finally {
            // 本轮结束，摘掉交付用的工作空间登记（交付本身已在此之前完成）
            artifacts.endRun(userId, sessionId);
        }
    }

    /** 停止某会话的生成（对应 AgentScope 的 per-session interrupt） */
    public void stop(String workspaceId, String userId, String sessionId) {
        if (workspaceId == null || sessionId == null || sessionId.isBlank()) {
            return;
        }
        AppLog.info("请求停止生成: workspace=%s session=%s", workspaceId, sessionId);

        // 实例的缓存键里带模型，所以要先从会话索引问出它用的是哪个模型。
        // 也只在实例确实存在时才打「用户停止」标记 —— 否则标记会残留下来，
        // 让该会话下一次真正的失败被误判成「用户主动停止」。
        SessionInfo info = sessions.require(userId, workspaceId, sessionId);
        HarnessAgent agent = agents.find(userId, workspaceId, info == null ? null : info.getModelId());
        if (agent == null) {
            AppLog.info("停止生成: 该会话没有活跃的 agent 实例，跳过中断");
            return;
        }

        String key = key(userId, workspaceId, sessionId);
        stopping.add(key);
        try {
            agent.interrupt(userId, sessionId);
        } catch (Exception e) {
            stopping.remove(key);
            AppLog.warn("中断失败: %s", e.getMessage());
        }
    }

    private void stopIfRunning(String workspaceId, String userId, String sessionId) {
        StreamState s = states.get(key(userId, workspaceId, sessionId));
        if (s != null && s.isRunning()) {
            stop(workspaceId, userId, sessionId);
        }
    }

    public StreamState snapshot(String userId, String workspaceId, String sessionId) {
        StreamState s = states.get(key(userId, workspaceId, sessionId));
        return s == null ? new StreamState(sessionId) : copyOf(s);
    }

    // ---------- 内部工具 ----------

    private static String key(String userId, String workspaceId, String sessionId) {
        return userId + "/" + workspaceId + "/" + sessionId;
    }

    /** 从 {@link #key} 里取中段的 workspaceId（key 的格式是本类定的，这里只做一次解析） */
    private static String workspaceIdOf(String key) {
        int from = key.indexOf('/');
        int to = key.lastIndexOf('/');
        return from < 0 || to <= from ? "" : key.substring(from + 1, to);
    }

    private void updateState(String key, String sessionId, java.util.function.Consumer<StreamState> fn) {
        StreamState s = states.computeIfAbsent(key, k -> new StreamState(sessionId));
        synchronized (s) {
            fn.accept(s);
            s.setUpdatedAt(System.currentTimeMillis());
        }
    }

    private static StreamState copyOf(StreamState src) {
        StreamState c = new StreamState();
        synchronized (src) {
            c.setSessionId(src.getSessionId());
            c.setContent(src.getContent());
            c.setReasoning(src.getReasoning());
            c.setRunning(src.isRunning());
            c.setError(src.getError());
            c.setSeq(src.getSeq());
            c.setUpdatedAt(src.getUpdatedAt());
            c.setDurationMs(src.getDurationMs());
            c.setInputTokens(src.getInputTokens());
            c.setOutputTokens(src.getOutputTokens());
            c.setTotalTokens(src.getTotalTokens());
        }
        return c;
    }

    /**
     * 回调统一走下面这层包装：回调内部抛异常只记日志，绝不中断生成 ——
     * 否则界面上的一个空指针就会把整轮回答废掉。
     */
    private void notifyStart(ChatListener l, String sessionId) {
        try {
            l.onStart(sessionId);
        } catch (Exception e) {
            AppLog.warn("onStart 回调异常: %s", e);
        }
    }

    private void notifyDelta(ChatListener l, String sessionId, String delta, String reasoning) {
        try {
            l.onDelta(sessionId, delta, reasoning);
        } catch (Exception e) {
            AppLog.warn("onDelta 回调异常: %s", e);
        }
    }

    private void notifyDone(ChatListener l, String sessionId, String content, String reasoning,
                            ChatStats stats) {
        try {
            l.onDone(sessionId, content, reasoning, stats);
        } catch (Exception e) {
            AppLog.warn("onDone 回调异常: %s", e);
        }
    }

    private void notifyError(ChatListener l, String sessionId, String message) {
        try {
            l.onError(sessionId, message);
        } catch (Exception e) {
            AppLog.warn("onError 回调异常: %s", e);
        }
    }

    @PreDestroy
    public void shutdown() {
        AppLog.info("ChatService 关闭");
        executor.shutdownNow();
    }
}
