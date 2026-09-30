package com.fastagent.service;

/**
 * 流式生成的事件回调。
 *
 * <p>把传输层从编排逻辑里摘出去：{@link ChatService} 只负责调模型、攒内容、落盘，
 * 至于增量怎么送到界面上，由实现方决定。当前实现是 web 层的 {@code SseChatListener}
 * （转成 SSE 推给 WebView 里的 Vue 前端）；单元测试或命令行场景可传打印实现。
 *
 * <p>所有方法都在<b>生成线程</b>上调用，不在 UI 线程，实现方需自行切回。
 */
public interface ChatListener {

    /** 空实现，用于不需要关心事件的场景 */
    ChatListener NOOP = new ChatListener() {
    };

    /** 开始生成（会话已就绪、用户消息已落盘） */
    default void onStart(String sessionId) {
    }

    /**
     * 收到一个增量分片。
     *
     * @param delta     正文增量，可能为空串（模型只推思考内容时）
     * @param reasoning 思考过程增量，可能为空串
     */
    default void onDelta(String sessionId, String delta, String reasoning) {
    }

    /**
     * 生成正常结束（含被用户主动停止）。
     *
     * @param stats 本轮耗时与 token 用量。传输层可以带给界面展示（回答下方那行小字），
     *              不需要的话忽略即可 —— 所以它不影响「换传输方式」的成本。
     */
    default void onDone(String sessionId, String content, String reasoning, ChatStats stats) {
    }

    /** 生成失败，{@code message} 为可直接展示给用户的中文提示 */
    default void onError(String sessionId, String message) {
    }
}
