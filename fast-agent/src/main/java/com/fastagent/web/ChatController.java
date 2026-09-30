package com.fastagent.web;

import com.fastagent.AppLog;
import com.fastagent.auth.AuthSupport;
import com.fastagent.service.ChatService;
import com.fastagent.service.StreamState;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.Map;

/**
 * 对话接口：SSE 流式、停止生成、状态快照。
 *
 * <p>推理由 AgentScope 的 HarnessAgent 完成，事件流经
 * {@link SseChatListener} 转成 SSE 推给前端。
 */
@RestController
@RequestMapping("/api/chat")
public class ChatController {

    private final ChatService chatService;

    public ChatController(ChatService chatService) {
        this.chatService = chatService;
    }

    /**
     * 流式对话。事件流：{@code start} → {@code delta}* → {@code done} / {@code error}。
     *
     * <p>校验不通过（消息为空、未配 Key、工作空间不存在）时 {@code startStream}
     * 会<b>同步</b>抛异常，此时 SSE 尚未开始、响应也未提交，由
     * {@link GlobalExceptionHandler} 返回 400 + JSON。</p>
     *
     * <p>请求体里的 {@code modelId} 可选：带上就以它为本轮模型，并记进会话索引
     * （下次进入该会话即恢复）；不带则用会话上次的，再没有就用默认模型。
     */
    @PostMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream(@RequestBody Map<String, String> body, HttpServletRequest req) {
        String userId = AuthSupport.requireUserId(req);

        // 0L = 不超时。Spring 默认 30s 会强制断开长回答。
        SseEmitter emitter = new SseEmitter(0L);
        emitter.onTimeout(emitter::complete);
        emitter.onError(e -> AppLog.warn("SSE 连接异常: %s", e.getMessage()));

        chatService.startStream(body.get("workspaceId"), userId, body.get("sessionId"),
                body.get("text"), body.get("modelId"), new SseChatListener(emitter));
        return emitter;
    }

    @PostMapping("/stop")
    public Map<String, Object> stop(@RequestBody Map<String, String> body, HttpServletRequest req) {
        String userId = AuthSupport.requireUserId(req);
        chatService.stop(body.get("workspaceId"), userId, body.get("sessionId"));
        return Map.of("ok", true);
    }

    /** 状态快照，前端轮询兜底用 */
    @GetMapping("/snapshot")
    public StreamState snapshot(@RequestParam String workspaceId, @RequestParam String sessionId,
                                HttpServletRequest req) {
        return chatService.snapshot(AuthSupport.requireUserId(req), workspaceId, sessionId);
    }
}
