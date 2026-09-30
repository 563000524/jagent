package com.fastagent.artifact;

import com.fastagent.AppLog;
import com.fastagent.UserPaths;
import com.fastagent.util.JsonFiles;
import com.fastagent.workspace.WorkspaceService;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.harness.agent.artifact.ArtifactDeliveryRequest;
import io.agentscope.harness.agent.artifact.ArtifactDeliveryResult;
import io.agentscope.harness.agent.artifact.ArtifactDeliveryTarget;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

/**
 * agent 交付给用户的文件（下载卡片的实体）。
 *
 * <p>本类实现 AgentScope harness 的 {@link ArtifactDeliveryTarget}：
 * 挂到 {@code HarnessAgent.Builder.artifactDeliveryTarget(...)} 上之后，harness 会注册一个名为
 * {@code deliver_artifact} 的内置工具，模型产出成品后调用它，harness 先在沙箱文件系统里把文件
 * 读成字节，再交到这里 —— 所以我们不需要拦截工具调用、也不需要扫盘找新文件。
 *
 * <p>落盘结构（{@code ~/.jagent/<userId>/downloads/}）：
 * <pre>
 * &lt;id&gt;/meta.json     归属与展示用元数据（见 {@link ArtifactMeta}）
 * &lt;id&gt;/&lt;文件名&gt;      文件副本
 * </pre>
 *
 * <p><b>为什么放用户数据根而不是系统临时目录</b>：{@code %TEMP%} 会被系统清理程序随时清空，
 * 且多用户共用一个根、权限按会话隔离；放这里还能复用 {@code UserPaths} 的按用户隔离，
 * 与「配置属于用户」的既有口径一致。
 *
 * <p><b>为什么存副本而不是引用原文件</b>：原文件可能被后续 {@code edit_file} 改掉、
 * 或被用户删掉，引用会让卡片失效；副本也让下载接口不必去碰用户的项目目录。
 *
 * <p><b>注意</b>：{@code deliver()} 拿不到「当前工作空间」——它只收到 {@link RuntimeContext}，
 * 而自有 SSE 通道构造的 context 里没有 workspaceId。所以由 {@link #beginRun} 在每轮开始前
 * 显式登记，{@code deliver()} 再按 {@code (userId, sessionId)} 查回来。
 */
@Service
public class ArtifactService implements ArtifactDeliveryTarget {

    /** 单文件上限。整份内容会先落到堆内存再写盘（harness 就是这么交过来的），必须设上限 */
    private static final long MAX_BYTES = 50L * 1024 * 1024;

    /** 未再被引用的文件保留时长 */
    private static final long TTL_MS = TimeUnit.DAYS.toMillis(7);
    /** 清理检查的最小间隔，避免每轮对话都扫一遍目录 */
    private static final long PRUNE_INTERVAL_MS = TimeUnit.HOURS.toMillis(1);

    private static final String META = "meta.json";

    private final FileRefStore fileRefs;
    private final WorkspaceService workspaces;

    /** key = userId/sessionId -> 本轮所在的工作空间 */
    private final Map<String, String> runs = new ConcurrentHashMap<>();
    /** key = userId/sessionId/toolCallId -> 正在拼的参数（工具参数是逐字推来的） */
    private final Map<String, Pending> pending = new ConcurrentHashMap<>();
    private volatile long lastPrune;

    public ArtifactService(FileRefStore fileRefs, WorkspaceService workspaces) {
        this.fileRefs = fileRefs;
        this.workspaces = workspaces;
    }

    /** 一次工具调用的中间态：工具名 + 累积的参数 JSON */
    private static final class Pending {
        final String toolName;
        final StringBuilder args = new StringBuilder();

        Pending(String toolName) {
            this.toolName = toolName;
        }
    }

    // ---------- 工具事件（卡片上「读取/写入/编辑」那一类的来源） ----------

    /**
     * 工具开始：只记名字，参数随后逐段到。
     *
     * <p>为什么从事件流里取而不是包一层工具：文件工具是 harness 内置的，我们拿不到它的调用点；
     * 而 AG-UI 的 {@code TOOL_CALL_ARGS} 会把完整参数推过来，解析 {@code path} 即可。
     */
    public void toolCallStarted(String userId, String sessionId, String toolCallId, String toolName) {
        if (userId == null || sessionId == null || toolCallId == null || toolName == null) {
            return;
        }
        if (!"read_file".equals(toolName) && !"write_file".equals(toolName) && !"edit_file".equals(toolName)) {
            return;
        }
        pending.put(toolKey(userId, sessionId, toolCallId), new Pending(toolName));
    }

    /** 工具参数增量：逐字累加，等 END 时一次解析（增量本身不是合法 JSON） */
    public void toolCallArgs(String userId, String sessionId, String toolCallId, String delta) {
        Pending p = pending.get(toolKey(userId, sessionId, toolCallId));
        if (p != null && delta != null) {
            p.args.append(delta);
        }
    }

    /**
     * 工具执行完：解析出 path，记一笔文件引用。
     *
     * <p><b>必须挂在结果事件上，不能挂在 {@code TOOL_CALL_END} 上</b>：END 只表示
     * 「参数推完了」，此刻工具还没执行 —— 对 {@code write_file} 来说文件还不存在，
     * 按那时去解析路径会一个都命中不了。
     */
    public void toolCallCompleted(String userId, String workspaceId, String sessionId, String toolCallId) {
        if (userId == null || sessionId == null || toolCallId == null) {
            return;
        }
        Pending p = pending.remove(toolKey(userId, sessionId, toolCallId));
        if (p == null) {
            return;
        }
        String path = pathOf(p.args.toString());
        if (path == null) {
            AppLog.warn("工具参数里没有 path，跳过文件引用: session=%s tool=%s args=%s",
                    sessionId, p.toolName, trimTo(p.args.toString(), 200));
            return;
        }
        String action = switch (p.toolName) {
            case "write_file" -> "write";
            case "edit_file" -> "edit";
            default -> "read";
        };
        try {
            fileRefs.record(userId, workspaceId, sessionId,
                    workspaces.pathOf(userId, workspaceId), path, action);
        } catch (Exception e) {
            // 工作空间可能刚被删掉（pathOf 会抛 404）；卡片是附加信息，不该影响生成
            AppLog.warn("记录文件引用失败: session=%s path=%s — %s", sessionId, path, e.getMessage());
        }
    }

    /** 从工具参数 JSON 里取路径；三种文件工具的字段都叫 path，另兼容两种写法 */
    private static String pathOf(String json) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            com.fasterxml.jackson.databind.JsonNode node =
                    JsonFiles.reader().readTree(json);
            for (String key : new String[]{"path", "filePath", "file_path"}) {
                com.fasterxml.jackson.databind.JsonNode v = node.get(key);
                if (v != null && !v.isNull() && !v.asText().isBlank()) {
                    return v.asText();
                }
            }
        } catch (Exception e) {
            AppLog.warn("工具参数不是合法 JSON（跳过文件引用）: %s", trimTo(json, 200));
        }
        return null;
    }

    private static String toolKey(String userId, String sessionId, String toolCallId) {
        return userId + "/" + sessionId + "/" + toolCallId;
    }

    // ---------- 运行期登记 ----------

    /**
     * 每轮对话开始前登记「这个会话属于哪个工作空间」。
     *
     * <p>两条通道（自有 SSE / AG-UI）都要调；不登记的话交付出来的文件没有 workspaceId
     * （不影响下载，只影响排查时看不清来源）。
     */
    public void beginRun(String userId, String workspaceId, String sessionId) {
        if (userId == null || sessionId == null) {
            return;
        }
        runs.put(key(userId, sessionId), workspaceId == null ? "" : workspaceId);
        pruneIfDue(userId);
    }

    /** 一轮结束，摘掉登记与没走完的工具参数（例如生成被中断），避免无限增长 */
    public void endRun(String userId, String sessionId) {
        if (userId == null || sessionId == null) {
            return;
        }
        runs.remove(key(userId, sessionId));
        String prefix = userId + "/" + sessionId + "/";
        pending.keySet().removeIf((k) -> k.startsWith(prefix));
    }

    // ---------- 交付 ----------

    /**
     * 模型调用 {@code deliver_artifact} 后由 harness 回调到这里（交付**工作空间里已有的文件**）。
     *
     * <p>返回值会被 harness 包成工具结果给模型看，所以失败原因要写清楚、可读，
     * 让模型知道该怎么办（例如超限、内容为空）。
     */
    @Override
    public ArtifactDeliveryResult deliver(RuntimeContext ctx, ArtifactDeliveryRequest req) {
        if (req == null) {
            return ArtifactDeliveryResult.fail("交付请求为空");
        }
        String userId = ctx == null ? null : ctx.getUserId();
        String sessionId = ctx == null ? null : ctx.getSessionId();
        if (userId == null || userId.isBlank()) {
            return ArtifactDeliveryResult.fail("无法确定交付用户，本次未交付");
        }

        byte[] content = req.content();
        String name = safeName(firstNonBlank(req.fileName(), baseName(req.filePath())));
        String problem = check(content, name);
        if (problem != null) {
            return ArtifactDeliveryResult.fail(problem);
        }
        try {
            ArtifactMeta meta = save(userId, sessionId, name, content, req.description(), req.filePath());
            return ArtifactDeliveryResult.success("已交付：" + meta.getName());
        } catch (Exception e) {
            AppLog.error(e, "交付文件失败: user=%s session=%s %s", userId, sessionId, name);
            return ArtifactDeliveryResult.fail("交付失败：" + name + "（" + e.getMessage() + "）");
        }
    }

    /**
     * 我们自己的 {@code deliver_file} 工具的实现：模型**直接给内容**，不落工作空间的盘。
     *
     * <p>这是「成品放哪儿」的正解 —— 见 {@link ArtifactTool} 的注释：harness 自带的
     * {@code deliver_artifact} 只能交付工作空间里的文件，逼着模型先把成品写进用户的项目目录，
     * 用户看到自己的仓库里凭空多出一堆产物。走这条路，成品只存在于用户自己的下载目录里。
     *
     * @return 给模型看的一句话（会作为工具结果回灌）
     */
    public String saveContent(String userId, String sessionId, String fileName,
                              String content, String description) {
        if (userId == null || userId.isBlank()) {
            return "交付失败：无法确定用户";
        }
        String name = safeName(fileName);
        byte[] bytes = content == null ? new byte[0] : content.getBytes(StandardCharsets.UTF_8);
        String problem = check(bytes, name);
        if (problem != null) {
            return problem;
        }
        try {
            ArtifactMeta meta = save(userId, sessionId, name, bytes, description, null);
            return "已保存到用户的下载目录：" + meta.getName() + "（" + meta.getSize() + " 字节）";
        } catch (Exception e) {
            AppLog.error(e, "保存交付物失败: user=%s session=%s %s", userId, sessionId, name);
            return "保存失败：" + name + "（" + e.getMessage() + "）";
        }
    }

    /** 交付物实体落盘：一个文件一个目录，meta.json 记归属 */
    private ArtifactMeta save(String userId, String sessionId, String name, byte[] content,
                              String description, String sourcePath) throws IOException {
        UserPaths paths = UserPaths.of(userId);
        Files.createDirectories(paths.downloadsDir());

        // 同一会话里同名文件视为「同一个交付物的新版本」——重跑任务时覆盖它，
        // 而不是留下两张同名卡片。
        ArtifactMeta old = findByName(userId, sessionId, name);
        String id = old != null ? old.getId() : newId();
        Path dir = paths.downloadsDir().resolve(id);
        Files.createDirectories(dir);

        Files.write(dir.resolve(name), content);

        ArtifactMeta meta = old != null ? old : new ArtifactMeta();
        meta.setId(id);
        meta.setName(name);
        meta.setSize(content.length);
        meta.setUserId(userId);
        meta.setSessionId(sessionId);
        meta.setWorkspaceId(workspaceOf(userId, sessionId));
        meta.setSourcePath(sourcePath);
        meta.setDescription(trimTo(description, 300));
        meta.setCreatedAt(System.currentTimeMillis());
        JsonFiles.writer().writeValue(dir.resolve(META).toFile(), meta);

        AppLog.info("交付文件: user=%s session=%s %s（%d 字节）来源=%s %s",
                userId, sessionId, name, content.length,
                sourcePath == null ? "（模型直接给内容）" : sourcePath,
                old != null ? "（覆盖同名）" : "");
        return meta;
    }

    /** 落盘前的检查，返回可读的问题描述；没问题返回 null */
    private static String check(byte[] content, String name) {
        if (content == null) {
            return "读不到文件内容，本次未交付：" + name;
        }
        if (content.length > MAX_BYTES) {
            return "文件超过 " + (MAX_BYTES / 1024 / 1024) + "MB 上限，本次未交付：" + name
                    + "。请改为把文件写在工作空间里，再用 deliver_artifact 交付。";
        }
        return null;
    }

    // ---------- 查询（供 Controller 用） ----------

    /** 某会话交付过的文件，按交付时间升序（前端据此挂到对应回答下面） */
    public List<ArtifactMeta> list(String userId, String sessionId) {
        Path root = UserPaths.of(userId).downloadsDir();
        if (!Files.isDirectory(root) || sessionId == null) {
            return List.of();
        }
        List<ArtifactMeta> out = new ArrayList<>();
        try (Stream<Path> dirs = Files.list(root)) {
            for (Path dir : dirs.toList()) {
                ArtifactMeta meta = readMeta(userId, dir);
                if (meta != null && sessionId.equals(meta.getSessionId())) {
                    out.add(meta);
                }
            }
        } catch (IOException e) {
            AppLog.warn("读取交付文件列表失败: %s", e.getMessage());
            return List.of();
        }
        out.sort(Comparator.comparingLong(ArtifactMeta::getCreatedAt));
        return out;
    }

    /** 按 id 取元数据，<b>并校验归属</b>；不属于该用户或不存在都返回 null（由调用方统一报 404） */
    public ArtifactMeta find(String userId, String id) {
        if (!isId(id)) {
            return null;
        }
        return readMeta(userId, UserPaths.of(userId).downloadsDir().resolve(id));
    }

    /** 一个交付文件的实体路径 */
    public Path fileOf(ArtifactMeta meta) {
        return UserPaths.of(meta.getUserId()).downloadsDir()
                .resolve(meta.getId()).resolve(meta.getName());
    }

    // ---------- 内部 ----------

    private ArtifactMeta findByName(String userId, String sessionId, String name) {
        for (ArtifactMeta m : list(userId, sessionId)) {
            if (name.equals(m.getName())) {
                return m;
            }
        }
        return null;
    }

    /** 读一个目录下的 meta.json；目录名非法、文件缺失或解析失败都当作「不是我们的数据」 */
    private ArtifactMeta readMeta(String userId, Path dir) {
        String id = dir.getFileName().toString();
        if (!isId(id) || !Files.isRegularFile(dir.resolve(META))) {
            return null;
        }
        try {
            ArtifactMeta m = JsonFiles.reader().readValue(dir.resolve(META).toFile(), ArtifactMeta.class);
            // 归属校验：目录名是 id，光有 id 不代表有权限
            if (m == null || !userId.equals(m.getUserId()) || !id.equals(m.getId())) {
                return null;
            }
            String name = safeName(m.getName());
            if (!name.equals(m.getName())) {
                // meta 被手改过（文件名里带了路径分隔符之类），以净化后的为准
                m.setName(name);
            }
            if (!Files.isRegularFile(fileOf(m))) {
                return null;
            }
            return m;
        } catch (Exception e) {
            AppLog.warn("交付文件元数据无法解析（已跳过）: %s — %s", dir, e.getMessage());
            return null;
        }
    }

    /**
     * 清理过期的交付文件。
     *
     * <p>只删「有我们自己写的 meta.json 且已过期」的目录 —— 这个目录是应用自己管的缓存区，
     * 但万一用户往里放了别的东西，也不会被误删。
     */
    private void pruneIfDue(String userId) {
        long now = System.currentTimeMillis();
        if (now - lastPrune < PRUNE_INTERVAL_MS) {
            return;
        }
        lastPrune = now;

        Path root = UserPaths.of(userId).downloadsDir();
        if (!Files.isDirectory(root)) {
            return;
        }
        int removed = 0;
        try (Stream<Path> dirs = Files.list(root)) {
            for (Path dir : dirs.toList()) {
                ArtifactMeta meta = readOwnMeta(dir);
                if (meta == null || now - meta.getCreatedAt() < TTL_MS) {
                    continue;
                }
                if (deleteDir(dir)) {
                    removed++;
                }
            }
        } catch (IOException e) {
            AppLog.warn("清理过期交付文件失败: %s", e.getMessage());
            return;
        }
        if (removed > 0) {
            AppLog.info("已清理 %d 个过期交付文件（保留 %d 天）: %s", removed, TTL_MS / TimeUnit.DAYS.toMillis(1), root);
        }
    }

    /** 清理专用：只认结构，不做归属过滤（否则别的用户的残留永远清不掉） */
    private ArtifactMeta readOwnMeta(Path dir) {
        if (!isId(dir.getFileName().toString()) || !Files.isRegularFile(dir.resolve(META))) {
            return null;
        }
        try {
            return JsonFiles.reader().readValue(dir.resolve(META).toFile(), ArtifactMeta.class);
        } catch (Exception e) {
            return null;
        }
    }

    private static boolean deleteDir(Path dir) {
        try (Stream<Path> walk = Files.walk(dir)) {
            for (Path p : walk.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(p);
            }
            return true;
        } catch (IOException e) {
            AppLog.warn("删除过期交付文件失败: %s — %s", dir, e.getMessage());
            return false;
        }
    }

    private String workspaceOf(String userId, String sessionId) {
        String ws = runs.get(key(userId, sessionId));
        return ws == null ? "" : ws;
    }

    private static String key(String userId, String sessionId) {
        return userId + "/" + sessionId;
    }

    private static String newId() {
        return UUID.randomUUID().toString().replace("-", "");
    }

    /** id 只允许十六进制，避免 {@code ../} 之类把路径带出下载目录 */
    private static boolean isId(String id) {
        return id != null && id.matches("[0-9a-f]{8,64}");
    }

    private static String baseName(String path) {
        if (path == null) {
            return "";
        }
        String p = path.replace('\\', '/');
        int i = p.lastIndexOf('/');
        return i >= 0 ? p.substring(i + 1) : p;
    }

    /**
     * 净化文件名：去掉路径成分与 Windows 不允许的字符。
     *
     * <p>harness 侧已经拦了路径分隔符，但那是它的约定，这里不能依赖 —— 这个名字会直接
     * 拼进我们落盘的路径，净化是最后一道闸。
     */
    private static String safeName(String raw) {
        String n = baseName(raw).trim();
        n = n.replaceAll("[\\\\/:*?\"<>|\\p{Cntrl}]", "_");
        if (n.isBlank() || ".".equals(n) || "..".equals(n)) {
            n = "file";
        }
        if (n.length() > 120) {
            int dot = n.lastIndexOf('.');
            String ext = dot > 0 && n.length() - dot <= 12 ? n.substring(dot) : "";
            n = n.substring(0, 120 - ext.length()) + ext;
        }
        return n;
    }

    private static String firstNonBlank(String a, String b) {
        return a != null && !a.isBlank() ? a : b;
    }

    private static String trimTo(String s, int max) {
        if (s == null) {
            return null;
        }
        String t = s.trim();
        return t.length() <= max ? t : t.substring(0, max);
    }
}
