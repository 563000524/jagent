package com.fastagent.workspace;

import com.fastagent.AppLog;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

/**
 * 会话索引管理（工作空间级）。
 *
 * <p>只维护「某个工作空间下有哪些会话、标题、最后活跃时间」，
 * 对话正文由 AgentScope 按 {@code (userId, sessionId)} 自己存取。
 * 这样会话列表 UI 不必去解析 agent 的内部状态格式。
 *
 * <p>索引文件落在工作空间数据目录里（{@code <用户目录>/.jagentspace/sessions.json}）。
 * 由于工作空间的定位本身要先查<b>按用户存</b>的登记表，本类每个方法都要带 userId。
 */
@Service
public class SessionService {

    private static final ObjectMapper JSON = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
            .setSerializationInclusion(JsonInclude.Include.NON_NULL);

    private final WorkspaceService workspaces;

    public SessionService(WorkspaceService workspaces) {
        this.workspaces = workspaces;
    }

    public Path indexFile(String userId, String workspaceId) {
        // 数据目录跟着工作空间走（用户指定目录下的 .jagentspace），不在固定位置
        return workspaces.pathOf(userId, workspaceId).resolve(WorkspaceService.SESSIONS_FILE);
    }

    /** 会话列表，按最后活跃时间倒序 */
    public List<SessionInfo> list(String userId, String workspaceId) {
        List<SessionInfo> out = load(userId, workspaceId);
        out.sort(Comparator.comparing(SessionInfo::getUpdatedAt,
                Comparator.nullsLast(Comparator.reverseOrder())));
        return out;
    }

    public SessionInfo create(String userId, String workspaceId) {
        SessionInfo s = new SessionInfo(newSessionId(), userId, "新会话");
        List<SessionInfo> all = load(userId, workspaceId);
        all.add(s);
        save(userId, workspaceId, all);
        workspaces.touch(userId, workspaceId);
        return s;
    }

    /**
     * 确保会话存在；不存在则新建并按首条消息生成标题。
     *
     * @return 实际使用的会话
     */
    public SessionInfo ensure(String userId, String workspaceId, String sessionId, String firstText) {
        List<SessionInfo> all = load(userId, workspaceId);
        SessionInfo found = null;
        for (SessionInfo s : all) {
            if (s.getSessionId().equals(sessionId)) {
                found = s;
                break;
            }
        }
        if (found == null) {
            found = new SessionInfo(
                    (sessionId == null || sessionId.isBlank()) ? newSessionId() : sessionId,
                    userId,
                    titleOf(firstText));
            all.add(found);
        } else if (isDefaultTitle(found.getTitle()) && firstText != null && !firstText.isBlank()) {
            found.setTitle(titleOf(firstText));
        }
        found.setUpdatedAt(OffsetDateTime.now());
        save(userId, workspaceId, all);
        workspaces.touch(userId, workspaceId);
        return found;
    }

    /**
     * 记录该会话使用的模型，下次进入该会话时恢复。
     *
     * @param modelId 传空表示清除记录、回到该用户的默认模型
     * @return 更新后的会话；会话不存在时返回 {@code null}
     */
    public SessionInfo setModel(String userId, String workspaceId, String sessionId, String modelId) {
        List<SessionInfo> all = load(userId, workspaceId);
        SessionInfo found = null;
        for (SessionInfo s : all) {
            if (s.getSessionId().equals(sessionId)) {
                found = s;
                break;
            }
        }
        if (found == null) {
            return null;
        }
        found.setModelId(modelId == null || modelId.isBlank() ? null : modelId.trim());
        save(userId, workspaceId, all);
        AppLog.info("会话模型已记录: workspace=%s session=%s model=%s",
                workspaceId, sessionId, found.getModelId());
        return found;
    }

    public void delete(String userId, String workspaceId, String sessionId) {
        List<SessionInfo> all = load(userId, workspaceId);
        all.removeIf(s -> s.getSessionId().equals(sessionId));
        save(userId, workspaceId, all);
        AppLog.info("删除会话索引: workspace=%s session=%s", workspaceId, sessionId);
    }

    public SessionInfo require(String userId, String workspaceId, String sessionId) {
        return load(userId, workspaceId).stream()
                .filter(s -> s.getSessionId().equals(sessionId))
                .findFirst()
                .orElse(null);
    }

    public void rename(String userId, String workspaceId, String sessionId, String title) {
        List<SessionInfo> all = load(userId, workspaceId);
        all.stream()
                .filter(s -> s.getSessionId().equals(sessionId))
                .findFirst()
                .ifPresent(s -> {
                    s.setTitle(title);
                    s.setUpdatedAt(OffsetDateTime.now());
                });
        save(userId, workspaceId, all);
    }

    // ---------- 内部 ----------

    private List<SessionInfo> load(String userId, String workspaceId) {
        Path f = indexFile(userId, workspaceId);
        if (!Files.isRegularFile(f)) {
            return new ArrayList<>();
        }
        try {
            SessionInfo[] arr = JSON.readValue(f.toFile(), SessionInfo[].class);
            List<SessionInfo> out = new ArrayList<>();
            for (SessionInfo s : arr) {
                if (s != null && s.getSessionId() != null) {
                    out.add(s);
                }
            }
            return out;
        } catch (IOException e) {
            AppLog.error(e, "会话索引解析失败（按空处理）: %s", f);
            return new ArrayList<>();
        }
    }

    private void save(String userId, String workspaceId, List<SessionInfo> all) {
        Path f = indexFile(userId, workspaceId);
        try {
            Files.createDirectories(f.getParent());
            JSON.writerWithDefaultPrettyPrinter().writeValue(f.toFile(), all);
        } catch (IOException e) {
            AppLog.error(e, "会话索引落盘失败: %s", f);
        }
    }

    private static String newSessionId() {
        return "s" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
    }

    private static boolean isDefaultTitle(String t) {
        return t == null || t.isBlank() || "新会话".equals(t);
    }

    /** 用首条消息生成标题，压平换行并截断 */
    private static String titleOf(String text) {
        if (text == null || text.isBlank()) {
            return "新会话";
        }
        String flat = text.trim().replaceAll("\\s+", " ");
        int[] cp = flat.codePoints().toArray();
        if (cp.length <= 20) {
            return flat;
        }
        return new String(cp, 0, 20) + "…";
    }
}
