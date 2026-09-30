package com.fastagent.artifact;

import com.fastagent.AppLog;
import com.fastagent.UserPaths;
import com.fastagent.util.JsonFiles;
import com.fasterxml.jackson.core.type.TypeReference;
import org.springframework.stereotype.Service;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * 「本轮 agent 碰过哪些文件」的记录（卡片上除交付物之外的那一类）。
 *
 * <p>数据来源是 AG-UI 的工具调用事件：{@code TOOL_CALL_START(name)} →
 * {@code TOOL_CALL_ARGS(delta)*} → {@code TOOL_CALL_END}。参数里的 {@code path}
 * 就是模型给的路径，由 {@link ArtifactService} 逐条喂进来。
 *
 * <p>索引按用户整份存 {@code ~/.jagent/<userId>/file-refs.json}
 * （{@code Map<id, FileRef>}）。不按会话拆文件：会话数没有上限，一个文件反而好维护；
 * 量级是「每轮几个」，全量读写完全够用。
 *
 * <p><b>为什么要解析真实路径</b>：模型给的路径可能是相对的，而相对路径的落点不唯一
 * （实测：{@code write_file} 的相对路径落在 {@code <项目现场>/<userId>/}，
 * 而交付工具按项目现场解析）。这里按已知规则逐个候选试存在性，取第一个命中的，
 * 并强制它落在工作空间里 —— 记进来的路径后面会被「预览/另存为」直接用，必须可信。
 */
@Service
public class FileRefStore {

    /** 记录的保留时长 */
    private static final long TTL_MS = TimeUnit.DAYS.toMillis(30);
    /** 单用户上限，超出后丢最旧的（防索引无限膨胀） */
    private static final int MAX_PER_USER = 2000;

    /** 整份索引的读写锁；条目很少，粗暴串行最简单也最不容易错 */
    private final Object lock = new Object();

    /**
     * 记一笔「这个文件被碰过」。
     *
     * @param wsRoot  工作空间的 {@code .jagentspace} 目录（解析相对路径的基准之一）
     * @param rawPath 模型给的路径（可能相对、可能绝对）
     * @return 记录下来的条目；路径解析不出来或不在工作空间内时返回 null（只记日志，不报错）
     */
    public FileRef record(String userId, String workspaceId, String sessionId, Path wsRoot,
                          String rawPath, String action) {
        String toolAction = action;
        if (userId == null || sessionId == null || wsRoot == null || rawPath == null
                || rawPath.isBlank() || toolAction == null) {
            return null;
        }
        Path resolved = resolve(wsRoot, userId, rawPath);
        if (resolved == null) {
            AppLog.info("文件引用未解析到真实文件（已忽略）: session=%s action=%s path=%s",
                    sessionId, toolAction, rawPath);
            return null;
        }
        String name = resolved.getFileName().toString();
        long size;
        try {
            size = Files.size(resolved);
        } catch (Exception e) {
            return null;
        }

        synchronized (lock) {
            Map<String, FileRef> all = load(userId);
            prune(all);
            // 同一个文件在同一会话里只留一条；动作取「更重」的那个（写 > 改 > 读）
            FileRef ref = all.values().stream()
                    .filter((r) -> sessionId.equals(r.getSessionId())
                            && resolved.toString().equals(r.getPath()))
                    .findFirst().orElse(null);
            long now = System.currentTimeMillis();
            if (ref == null) {
                ref = new FileRef();
                ref.setId(UUID.randomUUID().toString().replace("-", ""));
                ref.setUserId(userId);
                ref.setSessionId(sessionId);
                ref.setWorkspaceId(workspaceId);
                ref.setPath(resolved.toString());
                ref.setAction(toolAction);
                ref.setCreatedAt(now);
                all.put(ref.getId(), ref);
            } else if (weight(toolAction) > weight(ref.getAction())) {
                ref.setAction(toolAction);
            }
            ref.setRawPath(rawPath);
            ref.setName(name);
            ref.setSize(size);
            ref.setUpdatedAt(now);
            save(userId, all);
            return ref;
        }
    }

    /** 某会话涉及过的文件，按最近涉及时间升序（前端据此挂到对应回答下面） */
    public List<FileRef> list(String userId, String sessionId) {
        if (userId == null || sessionId == null) {
            return List.of();
        }
        List<FileRef> out = new ArrayList<>();
        synchronized (lock) {
            for (FileRef r : load(userId).values()) {
                if (sessionId.equals(r.getSessionId()) && Files.isRegularFile(safePath(r))) {
                    out.add(r);
                }
            }
        }
        out.sort(Comparator.comparingLong(FileRef::getUpdatedAt));
        return out;
    }

    /** 按 id 取，并校验归属；文件已被删掉的也返回 null */
    public FileRef find(String userId, String id) {
        if (userId == null || id == null || !id.matches("[0-9a-f]{8,64}")) {
            return null;
        }
        synchronized (lock) {
            FileRef r = load(userId).get(id);
            if (r == null || !userId.equals(r.getUserId()) || !Files.isRegularFile(safePath(r))) {
                return null;
            }
            return r;
        }
    }

    // ---------- 路径解析 ----------

    /**
     * 把模型给的路径解析成真实文件。
     *
     * <p>候选顺序来自实测（见 {@code tools/ArtifactProbe.java}）：绝对路径直接用；
     * 相对路径依次试「项目现场 / 项目现场下与自己同名的目录 / agent 数据目录」。
     * 命中后还要确认它确实在工作空间内 —— 模型理论上可能给出 {@code ..} 之类的路径。
     */
    private Path resolve(Path ws, String userId, String rawPath) {        Path project = ws.getParent();
        Path agentDir = ws.resolve(userId);
        List<Path> candidates = new ArrayList<>();
        Path raw = Path.of(rawPath.trim().replace('\\', '/'));
        if (raw.isAbsolute()) {
            candidates.add(raw);
        } else {
            candidates.add(project.resolve(raw));
            candidates.add(project.resolve(userId).resolve(raw));
            candidates.add(agentDir.resolve(raw));
        }
        for (Path c : candidates) {
            try {
                if (!Files.isRegularFile(c)) {
                    continue;
                }
                Path real = c.toRealPath();
                if (!real.startsWith(safeReal(project))) {
                    AppLog.warn("文件引用超出工作空间，已忽略: %s", real);
                    continue;
                }
                // .state 是自己的会话状态文件，不该出现在卡片上
                if (real.startsWith(safeReal(ws.resolve(".state")))) {
                    continue;
                }
                return real;
            } catch (Exception ignored) {
                // 候选不可用就试下一个
            }
        }
        return null;
    }

    private static Path safePath(FileRef r) {
        try {
            return Path.of(r.getPath());
        } catch (Exception e) {
            return Path.of(".");
        }
    }

    private static Path safeReal(Path p) {
        try {
            return p.toRealPath();
        } catch (Exception e) {
            return p.toAbsolutePath().normalize();
        }
    }

    /** 动作轻重：写 > 改 > 读。同一文件被读过后又改，卡片显示的是改 */
    private static int weight(String action) {
        return switch (action == null ? "" : action) {
            case "write" -> 3;
            case "edit" -> 2;
            case "read" -> 1;
            default -> 0;
        };
    }

    // ---------- 落盘 ----------

    private Path indexFile(String userId) {
        return UserPaths.of(userId).root().resolve("file-refs.json");
    }

    private Map<String, FileRef> load(String userId) {
        Path f = indexFile(userId);
        if (!Files.isRegularFile(f)) {
            return new LinkedHashMap<>();
        }
        try {
            Map<String, FileRef> m = JsonFiles.reader().readValue(f.toFile(),
                    new TypeReference<LinkedHashMap<String, FileRef>>() {
                    });
            return m == null ? new LinkedHashMap<>() : m;
        } catch (Exception e) {
            // 索引坏了不该让整个功能不可用：当作空表继续，下次写回时覆盖
            AppLog.warn("文件引用索引无法解析（按空表处理）: %s — %s", f, e.getMessage());
            return new LinkedHashMap<>();
        }
    }

    private void save(String userId, Map<String, FileRef> all) {
        Path f = indexFile(userId);
        Path tmp = f.resolveSibling(f.getFileName() + ".tmp");
        try {
            Files.createDirectories(f.getParent());
            JsonFiles.writer().writeValue(tmp.toFile(), all);
            Files.move(tmp, f, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        } catch (Exception e) {
            AppLog.error(e, "文件引用索引写入失败: %s", f);
            try {
                Files.deleteIfExists(tmp);
            } catch (Exception ignored) {
                // 清理失败无所谓，下次写入会覆盖
            }
        }
    }

    /** 丢掉过期的与文件已不存在的条目；顺手按上限截断 */
    private void prune(Map<String, FileRef> all) {
        long now = System.currentTimeMillis();
        all.values().removeIf((r) -> now - r.getUpdatedAt() > TTL_MS);
        if (all.size() <= MAX_PER_USER) {
            return;
        }
        all.values().stream()
                .sorted(Comparator.comparingLong(FileRef::getUpdatedAt))
                .limit(all.size() - MAX_PER_USER)
                .map(FileRef::getId)
                .toList()
                .forEach(all::remove);
    }
}
