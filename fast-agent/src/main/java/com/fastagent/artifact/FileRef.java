package com.fastagent.artifact;

/**
 * 一轮对话里「被 agent 碰过的文件」的一条记录。
 *
 * <p>与 {@link ArtifactMeta} 的区别：那个是模型**主动交付**的成品（我们有它的副本），
 * 这个是工具调用**顺带涉及**的文件（读/写/改），只有原始路径 —— 卡片上用来回答
 * 「这轮它到底动了哪些文件」，点开能预览。
 *
 * <p>两者在前端是同一种卡片，靠 {@code action} 区分：{@code delivered} / {@code read} /
 * {@code write} / {@code edit}。
 */
public class FileRef {

    /** 卡片 id（URL 里作为路径参数）；与交付文件的 id 同一套校验规则 */
    private String id;
    private String userId;
    private String sessionId;
    private String workspaceId;
    /** 解析后的**绝对路径**（点击预览/另存为用的就是它） */
    private String path;
    /** 模型给的原始路径，用来与交付文件去重、以及排查解析是否跑偏 */
    private String rawPath;
    private String name;
    private long size;
    /** read / write / edit */
    private String action;
    /** 首次涉及的时刻 */
    private long createdAt;
    /**
     * 最近一次涉及的时刻。
     *
     * <p>前端按它把卡片挂到对应那条回答下面。用「最近」而不是「首次」：
     * 同一个文件后续轮次又被改过，卡片应该出现在那轮，而不是最初读到它的那轮。
     */
    private long updatedAt;

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getUserId() {
        return userId;
    }

    public void setUserId(String userId) {
        this.userId = userId;
    }

    public String getSessionId() {
        return sessionId;
    }

    public void setSessionId(String sessionId) {
        this.sessionId = sessionId;
    }

    public String getWorkspaceId() {
        return workspaceId;
    }

    public void setWorkspaceId(String workspaceId) {
        this.workspaceId = workspaceId;
    }

    public String getPath() {
        return path;
    }

    public void setPath(String path) {
        this.path = path;
    }

    public String getRawPath() {
        return rawPath;
    }

    public void setRawPath(String rawPath) {
        this.rawPath = rawPath;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public long getSize() {
        return size;
    }

    public void setSize(long size) {
        this.size = size;
    }

    public String getAction() {
        return action;
    }

    public void setAction(String action) {
        this.action = action;
    }

    public long getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(long createdAt) {
        this.createdAt = createdAt;
    }

    public long getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(long updatedAt) {
        this.updatedAt = updatedAt;
    }
}
