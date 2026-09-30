package com.fastagent.web;

import com.fastagent.AppLog;
import com.fastagent.service.ChatListener;
import com.fastagent.service.ChatStats;
import org.springframework.http.MediaType;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 把 {@link ChatListener} 的流式回调转成 SSE 事件推给前端。
 *
 * <p>用适配器而不是让 {@code ChatService} 直接持有 {@code SseEmitter}，
 * 是为了让编排层保持与传输方式无关：将来若换成进程内调用（原生控件界面、
 * 单元测试、命令行），只需换一个 Listener 实现，业务代码一行不用动。
 *
 * <p>事件名与前端约定一致：{@code start} → {@code delta}* → {@code done} / {@code error}。
 */
public class SseChatListener implements ChatListener {

    private final SseEmitter emitter;

    public SseChatListener(SseEmitter emitter) {
        this.emitter = emitter;
    }

    @Override
    public void onStart(String sessionId) {
        send("start", payload("sessionId", sessionId));
    }

    @Override
    public void onDelta(String sessionId, String delta, String reasoning) {
        Map<String, Object> m = payload("sessionId", sessionId);
        m.put("delta", nullToEmpty(delta));
        m.put("reasoning", nullToEmpty(reasoning));
        send("delta", m);
    }

    @Override
    public void onDone(String sessionId, String content, String reasoning, ChatStats stats) {
        Map<String, Object> m = payload("sessionId", sessionId);
        m.put("content", nullToEmpty(content));
        m.put("reasoning", nullToEmpty(reasoning));
        // 耗时与 token 随 done 一起给前端，用于渲染回答下方那行小字
        ChatStats s = stats == null ? ChatStats.EMPTY : stats;
        m.put("durationMs", s.durationMs());
        m.put("inputTokens", s.inputTokens());
        m.put("outputTokens", s.outputTokens());
        m.put("totalTokens", s.totalTokens());
        send("done", m);
        complete();
    }

    @Override
    public void onError(String sessionId, String message) {
        Map<String, Object> m = payload("sessionId", sessionId);
        m.put("message", nullToEmpty(message));
        send("error", m);
        complete();
    }

    /** 写一帧 SSE；连接已断开时忽略，不影响生成流程 */
    private void send(String event, Map<String, Object> data) {
        try {
            emitter.send(SseEmitter.event().name(event).data(data, MediaType.APPLICATION_JSON));
        } catch (IOException | IllegalStateException e) {
            AppLog.warn("SSE 推送失败（事件=%s）: %s", event, e.getMessage());
        }
    }

    private void complete() {
        try {
            emitter.complete();
        } catch (Exception ignored) {
            // 已完成或客户端已断开，无需处理
        }
    }

    /** Map.of 不接受 null 值，统一转成空串 */
    private static Map<String, Object> payload(String key, String value) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put(key, nullToEmpty(value));
        return m;
    }

    private static String nullToEmpty(String s) {
        return s == null ? "" : s;
    }
}
