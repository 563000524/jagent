package com.fastagent.workspace;

import java.time.OffsetDateTime;

/**
 * 会话索引条目。
 *
 * <p><b>只存元数据</b>：对话内容由 AgentScope 按 {@code (userId, sessionId)} 持久化，
 * 这里仅维护「工作空间下有哪些会话、标题是什么、最后活跃时间」，
 * 用于渲染左侧会话列表。
 */
public class SessionInfo {

    private String sessionId;
    private String userId;
    private String title;
    /**
     * 该会话最近一次使用的模型 id。
     *
     * <p>为空表示「尚未固定，用全局默认模型」—— 老会话文件里没有这个字段，
     * 读出来就是 null，行为与以前一致（{@code NON_NULL} 不会把它写进 JSON）。
     */
    private String modelId;
    private OffsetDateTime createdAt;
    private OffsetDateTime updatedAt;

    public SessionInfo() {
    }

    public SessionInfo(String sessionId, String userId, String title) {
        this.sessionId = sessionId;
        this.userId = userId;
        this.title = title;
        OffsetDateTime now = OffsetDateTime.now();
        this.createdAt = now;
        this.updatedAt = now;
    }

    public String getSessionId() {
        return sessionId;
    }

    public void setSessionId(String sessionId) {
        this.sessionId = sessionId;
    }

    public String getUserId() {
        return userId;
    }

    public void setUserId(String userId) {
        this.userId = userId;
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public String getModelId() {
        return modelId;
    }

    public void setModelId(String modelId) {
        this.modelId = modelId;
    }

    public OffsetDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(OffsetDateTime createdAt) {
        this.createdAt = createdAt;
    }

    public OffsetDateTime getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(OffsetDateTime updatedAt) {
        this.updatedAt = updatedAt;
    }
}
