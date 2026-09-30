package com.fastagent.service;

/**
 * 流式生成状态快照。
 *
 * <p>除 SSE 推送外，前端还会轮询该快照作为兜底：
 * 一旦事件通道异常，仍能拿到已生成的内容。
 */
public class StreamState {

    private String sessionId = "";
    private String content = "";
    private String reasoning = "";
    private boolean running;
    private String error = "";
    private int seq;
    private long updatedAt;

    /** 本轮耗时（毫秒）；未结束时为 0 */
    private long durationMs;
    /** 本轮累计 token（可能多次模型调用）；0 表示模型未回传 */
    private int inputTokens;
    private int outputTokens;
    private int totalTokens;

    public StreamState() {
    }

    public StreamState(String sessionId) {
        this.sessionId = sessionId;
    }

    public String getSessionId() {
        return sessionId;
    }

    public void setSessionId(String sessionId) {
        this.sessionId = sessionId;
    }

    public String getContent() {
        return content;
    }

    public void setContent(String content) {
        this.content = content;
    }

    public String getReasoning() {
        return reasoning;
    }

    public void setReasoning(String reasoning) {
        this.reasoning = reasoning;
    }

    public boolean isRunning() {
        return running;
    }

    public void setRunning(boolean running) {
        this.running = running;
    }

    public String getError() {
        return error;
    }

    public void setError(String error) {
        this.error = error;
    }

    public int getSeq() {
        return seq;
    }

    public void setSeq(int seq) {
        this.seq = seq;
    }

    public long getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(long updatedAt) {
        this.updatedAt = updatedAt;
    }

    public long getDurationMs() {
        return durationMs;
    }

    public void setDurationMs(long durationMs) {
        this.durationMs = durationMs;
    }

    public int getInputTokens() {
        return inputTokens;
    }

    public void setInputTokens(int inputTokens) {
        this.inputTokens = inputTokens;
    }

    public int getOutputTokens() {
        return outputTokens;
    }

    public void setOutputTokens(int outputTokens) {
        this.outputTokens = outputTokens;
    }

    public int getTotalTokens() {
        return totalTokens;
    }

    public void setTotalTokens(int totalTokens) {
        this.totalTokens = totalTokens;
    }
}
