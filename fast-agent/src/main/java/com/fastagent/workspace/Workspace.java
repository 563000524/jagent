package com.fastagent.workspace;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.OffsetDateTime;

/**
 * 一条工作空间登记。
 *
 * <p>工作空间由用户<b>指定一个目录</b>创建，agent 的数据都放在该目录下的
 * {@code .jagentspace/} 里，不污染用户自己的文件。
 *
 * <p>注意两类文件<b>作用域不同</b>：{@code AGENTS.md} 是空间级的（全空间一份），
 * 而记忆、会话、日志都按 {@code RuntimeContext.userId} 再分一层目录
 * —— harness 的 {@code WorkspaceManager.resolveRuntimeDataPath} 会把 userId 当命名空间拼进去。
 *
 * <pre>
 * &lt;用户指定的目录&gt;/
 * └── .jagentspace/
 *     ├── AGENTS.md               agent 人格与项目约定（空间级，创建时生成，可编辑）
 *     ├── sessions.json           会话索引（本服务维护：会话列表与标题）
 *     ├── .state/&lt;userId&gt;/&lt;sessionId&gt;/agent_state.json   对话状态（AgentScope）
 *     └── &lt;userId&gt;/               AgentScope 按用户隔离，也是 agent 的 cwd
 *         ├── MEMORY.md           长期记忆（agent 自动维护）
 *         ├── memory/&lt;日期&gt;.md     每日事实（agent 自动维护）
 *         ├── agents/ws-&lt;id&gt;/sessions/*.jsonl     会话原始日志
 *         └── default/&lt;wsId&gt;/&lt;sid&gt;/events/*.jsonl 事件分段日志
 * </pre>
 *
 * <p>本对象只作为登记表（{@code ~/.jagent/<userId>/workspaces.json}）里的元素持久化；
 * 记录与磁盘的一致性由 {@link WorkspaceService} 负责（删除只摘登记，不删用户目录）。
 */
public class Workspace {

    /** agent 数据目录名：建在用户指定的目录下 */
    public static final String DATA_DIR_NAME = ".jagentspace";

    private String id;
    private String name;
    private String description = "";
    /** 归属用户：列表按它过滤 */
    private String ownerId;
    /** 用户指定的目录（工作空间的落点），绝对路径 */
    private String directory;
    private OffsetDateTime createdAt;
    private OffsetDateTime updatedAt;

    public Workspace() {
    }

    public Workspace(String id, String name, String ownerId, String directory) {
        this.id = id;
        this.name = name;
        this.ownerId = ownerId;
        this.directory = directory;
        OffsetDateTime now = OffsetDateTime.now();
        this.createdAt = now;
        this.updatedAt = now;
    }

    /** agent 的数据目录：{@code <directory>/.jagentspace}。不参与 JSON 序列化 */
    public Path dataDir() {
        return Paths.get(directory).resolve(DATA_DIR_NAME);
    }

    /** 用户指定目录是否存在且可写（用于列表里标记「目录不可用」） */
    public boolean directoryExists() {
        return directory != null && java.nio.file.Files.isDirectory(Paths.get(directory));
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

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public String getOwnerId() {
        return ownerId;
    }

    public void setOwnerId(String ownerId) {
        this.ownerId = ownerId;
    }

    public String getDirectory() {
        return directory;
    }

    public void setDirectory(String directory) {
        this.directory = directory;
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
