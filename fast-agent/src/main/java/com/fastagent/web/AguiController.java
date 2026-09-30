package com.fastagent.web;

import com.fastagent.AppLog;
import com.fastagent.auth.AuthSupport;
import com.fastagent.service.ChatService;
import io.agentscope.core.agui.encoder.AguiEventEncoder;
import io.agentscope.core.agui.event.AguiEvent;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * AG-UI 协议的对话入口。
 *
 * <p>与 {@link ChatController#stream} 并存：那是早期的自定义事件协议
 * （{@code start} / {@code delta} / {@code done} / {@code error}），这里是
 * <a href="https://docs.ag-ui.com">AG-UI</a> 标准事件流
 * （{@code RUN_*} / {@code TEXT_MESSAGE_*} / {@code TOOL_CALL_*} / {@code REASONING_*} …）。
 * 两者共用 {@link ChatService} 的会话准备、模型解析、状态快照与停止生成。
 *
 * <p><b>请求体</b>按 AG-UI 的 {@code RunAgentInput} 形态接收，并额外容忍两个便捷字段
 * （便于自研前端少绕一层）：
 * <pre>
 * {
 *   "threadId": "会话 id",
 *   "runId":    "本轮 id（不传则由服务端生成）",
 *   "messages": [{ "role": "user", "content": "..." }],   // 取最后一条 user 消息
 *   "text":     "...",                                    // 便捷字段，优先于 messages
 *   "forwardedProps": { "workspaceId": "...", "modelId": "..." }
 * }
 * </pre>
 * 用 {@code Map} 而不是直接反序列化成 {@code RunAgentInput}：后者的构造器未必带
 * Jackson 注解，绑定失败会让整个端点不可用；这里手工取值，容错面更大。
 *
 * <p><b>响应</b>是 {@code text/event-stream}，每帧由官方 {@link AguiEventEncoder} 编码，
 * 形如 {@code data: {"type":"TEXT_MESSAGE_CONTENT",...}\n\n}。
 */
@RestController
@RequestMapping("/api/agui")
public class AguiController {

    private final ChatService chatService;
    private final AguiEventEncoder encoder = new AguiEventEncoder();

    public AguiController(ChatService chatService) {
        this.chatService = chatService;
    }

    /**
     * 发起一轮 AG-UI run，事件以 SSE 流式返回。
     *
     * <p>校验失败（消息为空、未配 Key、工作空间不存在或不属于当前用户）时
     * {@code startAguiRun} 会<b>同步</b>抛异常 —— 此时 SSE 尚未开始、响应也未提交，
     * 由 {@link GlobalExceptionHandler} 返回 400 + JSON。
     */
    @PostMapping(value = "/run", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter run(@RequestBody Map<String, Object> body, HttpServletRequest req) {
        String userId = AuthSupport.requireUserId(req);

        String threadId = str(body.get("threadId"));
        String runId = str(body.get("runId"));
        if (runId == null || runId.isBlank()) {
            runId = UUID.randomUUID().toString();
        }
        Map<String, Object> props = asMap(body.get("forwardedProps"));
        String workspaceId = firstNonNull(str(body.get("workspaceId")), str(props.get("workspaceId")));
        String modelId = firstNonNull(str(body.get("modelId")), str(props.get("modelId")));
        // 自研前端直接给 text；标准 AG-UI 客户端给 messages，取最后一条 user 消息
        String text = str(body.get("text"));
        if (text == null || text.isBlank()) {
            text = lastUserText(body.get("messages"));
        }

        // 0L = 不超时。Spring 默认 30s 会强制断开长回答。
        SseEmitter emitter = new SseEmitter(0L);

        // 校验与准备在这里同步完成，失败会直接抛给 GlobalExceptionHandler
        Flux<AguiEvent> events = chatService.startAguiRun(workspaceId, userId, threadId,
                text, modelId, runId);
        String finalRunId = runId;
        String finalThreadId = threadId;

        Disposable disposable = events.subscribe(
                ev -> send(emitter, ev),
                err -> {
                    // 正常异常路径已被适配器转成 RUN_ERROR 事件；走到这里说明是流本身的故障
                    AppLog.error(err, "AG-UI 事件流异常: thread=%s run=%s", finalThreadId, finalRunId);
                    sendErrorFrame(emitter, finalThreadId, finalRunId, err);
                    emitter.complete();
                },
                emitter::complete);

        // 前端断开（关窗口 / 切换会话 / 点停止）时释放订阅，让 agent 停止继续推理
        emitter.onCompletion(disposable::dispose);
        emitter.onError(e -> {
            AppLog.warn("AG-UI SSE 连接异常: %s", e.getMessage());
            disposable.dispose();
        });
        emitter.onTimeout(emitter::complete);
        return emitter;
    }

    // ---------- 内部 ----------

    /**
     * 写一帧 SSE。
     *
     * <p>用 {@code encodeToJson} 拿纯 JSON，交给 {@code SseEmitter} 补
     * {@code data:} 前缀 —— 与 {@code encoder.encode()} 的原始帧格式等价，
     * 且避免重复包一层 {@code data:}。指定的 {@code TEXT_PLAIN} 必须保留：
     * 默认的 JSON 序列化会把字符串再套一层引号。
     */
    private void send(SseEmitter emitter, AguiEvent ev) {
        try {
            emitter.send(SseEmitter.event().data(encoder.encodeToJson(ev), MediaType.TEXT_PLAIN));
        } catch (Exception e) {
            // 连接已断开或已完成：忽略即可，不影响生成流程
            AppLog.warn("AG-UI 推送失败（事件=%s）: %s", ev.getType(), e.getMessage());
        }
    }

    /** 流本身异常时补一帧 {@code RUN_ERROR}，让前端能收尾 */
    private void sendErrorFrame(SseEmitter emitter, String threadId, String runId, Throwable err) {
        String message = err.getMessage() == null ? err.getClass().getSimpleName() : err.getMessage();
        try {
            emitter.send(SseEmitter.event().data(
                    encoder.encodeToJson(new AguiEvent.RunError(threadId, runId, message, "internal_error")),
                    MediaType.TEXT_PLAIN));
        } catch (Exception ignored) {
            // 连接已断开，无需处理
        }
    }

    /** 从 AG-UI 的 messages 里取最后一条 user 消息的文本 */
    private static String lastUserText(Object messages) {
        if (!(messages instanceof List<?> list)) {
            return null;
        }
        for (int i = list.size() - 1; i >= 0; i--) {
            if (list.get(i) instanceof Map<?, ?> m && "user".equals(str(m.get("role")))) {
                String t = textOf(m.get("content"));
                if (t != null && !t.isBlank()) {
                    return t;
                }
            }
        }
        return null;
    }

    /** 内容可能是纯字符串、{@code {type:text,text:...}} 或内容块数组 */
    private static String textOf(Object content) {
        if (content instanceof String s) {
            return s;
        }
        if (content instanceof Map<?, ?> m) {
            String t = str(m.get("text"));
            return t != null ? t : str(m.get("content"));
        }
        if (content instanceof List<?> blocks) {
            StringBuilder sb = new StringBuilder();
            for (Object b : blocks) {
                String t = textOf(b);
                if (t != null) {
                    sb.append(t);
                }
            }
            return sb.isEmpty() ? null : sb.toString();
        }
        return null;
    }

    private static String str(Object o) {
        return o == null ? null : String.valueOf(o);
    }

    private static String firstNonNull(String a, String b) {
        return a != null && !a.isBlank() ? a : b;
    }

    private static Map<String, Object> asMap(Object o) {
        return o instanceof Map<?, ?> m ? castMap(m) : Map.of();
    }

    private static Map<String, Object> castMap(Map<?, ?> m) {
        Map<String, Object> out = new java.util.LinkedHashMap<>();
        m.forEach((k, v) -> out.put(String.valueOf(k), v));
        return out;
    }
}
