package com.fastagent.artifact;

/**
 * 一个已交付文件的元数据，落在 {@code ~/.jagent/<userId>/downloads/<id>/meta.json}。
 *
 * <p>用「一个文件一个目录」而不是一张大索引表：
 * <ul>
 *   <li>并发交付（多个会话同时跑）不会互相覆盖整表；</li>
 *   <li>某个目录坏了只影响它自己，列表接口跳过即可，不会整表读不出来；</li>
 *   <li>用户直接去文件管理器里看，文件名与所见一致，不需要查索引。</li>
 * </ul>
 *
 * <p>{@code userId} 落在这里<b>是为了鉴权</b>：下载/另存为接口拿到 id 后必须核对该文件属于
 * 当前登录用户，否则等于任何人都能按 id 取别人的文件。
 */
public class ArtifactMeta {

    /** 文件 id（目录名）。URL 中作为路径参数，只含 [0-9a-f] */
    private String id;
    /** 展示与下载用的文件名（已净化，不含路径分隔符） */
    private String name;
    private long size;
    /** 归属，用于鉴权 */
    private String userId;
    /** 产生它的会话，列表按此过滤 */
    private String sessionId;
    /** 产生它的工作空间（仅用于排查，不参与过滤） */
    private String workspaceId;
    /** agent 沙箱内的来源路径（相对路径，便于回答「这是哪儿来的」） */
    private String sourcePath;
    /** agent 自己写的说明（deliver_artifact 的 description 参数） */
    private String description;
    /** 交付时间（epoch 毫秒），前端据此把文件挂到对应那条回答下 */
    private long createdAt;

    public ArtifactMeta() {
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
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

    public String getSourcePath() {
        return sourcePath;
    }

    public void setSourcePath(String sourcePath) {
        this.sourcePath = sourcePath;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public long getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(long createdAt) {
        this.createdAt = createdAt;
    }
}
