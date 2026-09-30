package com.fastagent.workspace;

import com.fastagent.AppLog;
import com.fastagent.UserPaths;
import com.fastagent.service.ApiException;
import com.fastagent.util.JsonFiles;
import com.fasterxml.jackson.core.type.TypeReference;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.stream.Stream;

/**
 * 工作空间管理。
 *
 * <p>工作空间由用户<b>指定目录</b>创建，agent 的数据放在该目录下的
 * {@code .jagentspace/}（见 {@link Workspace}）。
 *
 * <p>本服务维护一份<b>登记表</b> {@code .fastagent/workspaces.json}：它回答
 * 「现在有哪些工作空间、各自建在哪个目录」。之所以要这份索引而不是每次去磁盘上找：
 * 工作空间的目录是用户随便挑的，扫盘无从下手；有索引才能列出、校验归属、按更新时间排序。
 *
 * <p><b>一致性策略</b>：删除只摘登记、不删用户目录（除非显式 {@code purgeData}，
 * 且也只删目录里的 {@code .workspace}，绝不碰用户自己的文件）。
 * 目录被用户手工移走时，登记仍在，列表里会标记「目录不存在」，便于用户自行处理。
 */
@Service
public class WorkspaceService {

    public static final String AGENTS_MD = "AGENTS.md";
    public static final String MEMORY_MD = "MEMORY.md";
    public static final String SESSIONS_FILE = "sessions.json";

    private final ReentrantReadWriteLock lock = new ReentrantReadWriteLock();

    // ---------- 路径 ----------

    /** 登记表文件 */
    public Path registryFile(String userId) {
        return UserPaths.of(userId).workspacesFile();
    }

    // ---------- 查询 ----------

    /** 列出某用户的工作空间（按更新时间倒序） */
    public List<Workspace> list(String userId) {
        List<Workspace> out = new ArrayList<>();
        for (Workspace w : readAll(userId)) {
            if (userId == null || userId.equals(w.getOwnerId())) {
                out.add(w);
            }
        }
        out.sort(Comparator.comparing(Workspace::getUpdatedAt,
                Comparator.nullsLast(Comparator.reverseOrder())));
        return out;
    }

    /** 取工作空间并校验归属 */
    public Workspace require(String userId, String workspaceId) {
        Workspace w = requireByIdOrNull(userId, workspaceId);
        if (w == null) {
            throw new ApiException(404, "工作空间不存在");
        }
        if (userId != null && !userId.equals(w.getOwnerId())) {
            throw new ApiException(403, "无权访问该工作空间");
        }
        return w;
    }

    /** 取数据目录；不存在则抛 404 */
    public Path pathOf(String userId, String workspaceId) {
        Workspace w = requireByIdOrNull(userId, workspaceId);
        if (w == null) {
            throw new ApiException(404, "工作空间不存在");
        }
        return w.dataDir();
    }

    private Workspace requireByIdOrNull(String userId, String workspaceId) {
        if (workspaceId == null || workspaceId.isBlank()) {
            return null;
        }
        return readAll(userId).stream()
                .filter(w -> workspaceId.equals(w.getId()))
                .findFirst()
                .orElse(null);
    }

    // ---------- 写 ----------

    /**
     * 在用户指定的目录下创建工作空间。
     *
     * @param directory 用户指定的目录（绝对路径）；不存在会自动创建
     */
    public Workspace create(String userId, String name, String directory, String description) {
        if (directory == null || directory.isBlank()) {
            throw new ApiException(400, "请填写工作空间目录");
        }
        Path dir;
        try {
            dir = Paths.get(directory.trim());
        } catch (Exception e) {
            throw new ApiException(400, "目录路径不合法: " + directory);
        }
        if (!dir.isAbsolute()) {
            throw new ApiException(400, "请填写绝对路径，例如 D:\\projects\\my-project");
        }
        dir = dir.normalize();

        // 同一个目录只允许登记一次（大小写在 Windows 上不敏感，Path.equals 已处理）
        for (Workspace w : readAll(userId)) {
            if (w.getDirectory() != null && dir.equals(Paths.get(w.getDirectory()).normalize())) {
                throw new ApiException(400, "该目录已经是一个工作空间：" + w.getName());
            }
        }

        try {
            Files.createDirectories(dir);
        } catch (IOException e) {
            throw new ApiException(400, "无法创建或访问该目录: " + e.getMessage());
        }
        if (!writable(dir)) {
            throw new ApiException(400, "该目录不可写，请换一个目录或以管理员身份运行");
        }

        String n = (name == null || name.isBlank()) ? "未命名工作空间" : name.trim();
        Workspace w = new Workspace(newId(), n, userId, dir.toString());
        w.setDescription(description == null ? "" : description.trim());

        Path data = w.dataDir();
        try {
            Files.createDirectories(data);
            // 这里不要建 memory/ —— 记忆是「用户级」的：harness 按 RuntimeContext.userId
            // 解析到 <data>/<userId>/memory/，写文件时自建目录。
            // 建在 <data> 下只会留一个永远为空的目录（踩过）。
            Files.createDirectories(data.resolve(".state"));
        } catch (IOException e) {
            throw new ApiException(500, "创建数据目录失败: " + e.getMessage());
        }
        initAgentsMd(w);

        List<Workspace> all = readAll(userId);
        all.add(w);
        writeAll(userId, all);
        AppLog.info("创建工作空间: id=%s name=%s owner=%s 目录=%s 数据=%s",
                w.getId(), n, userId, dir, data);
        return w;
    }

    /**
     * 摘掉登记。
     *
     * <p>默认<b>不动磁盘</b>（用户目录里可能还有别的东西，误删代价太大）；
     * {@code purgeData=true} 时也只删该目录下的 {@code .workspace}，用户自己的文件一律不碰。
     */
    public void delete(String userId, String workspaceId, boolean purgeData) {
        Workspace w = require(userId, workspaceId);
        List<Workspace> all = readAll(userId);
        all.removeIf(x -> workspaceId.equals(x.getId()));
        writeAll(userId, all);
        AppLog.info("移除工作空间登记: id=%s name=%s 目录=%s", workspaceId, w.getName(), w.getDirectory());

        if (purgeData) {
            Path data = w.dataDir();
            // 只删 .workspace 这一层，绝不动用户目录本身
            deleteRecursively(data);
            AppLog.info("已删除工作空间数据目录: %s（用户目录保留）", data);
        }
    }

    /** 更新最后活跃时间（会话变动时调用） */
    public void touch(String userId, String workspaceId) {
        List<Workspace> all = readAll(userId);
        boolean changed = false;
        for (Workspace w : all) {
            if (workspaceId.equals(w.getId())) {
                w.setUpdatedAt(OffsetDateTime.now());
                changed = true;
            }
        }
        if (changed) {
            writeAll(userId, all);
        }
    }

    // ---------- 登记表读写 ----------

    private List<Workspace> readAll(String userId) {
        Path f = registryFile(userId);
        if (!Files.isRegularFile(f)) {
            return new ArrayList<>();
        }
        lock.readLock().lock();
        try {
            List<Workspace> list = JsonFiles.reader().readValue(f.toFile(), new TypeReference<>() {
            });
            List<Workspace> out = new ArrayList<>();
            if (list != null) {
                for (Workspace w : list) {
                    if (w != null && w.getId() != null && w.getDirectory() != null) {
                        out.add(w);
                    }
                }
            }
            return out;
        } catch (IOException e) {
            AppLog.error(e, "工作空间登记表解析失败（按空处理）: %s", f);
            return new ArrayList<>();
        } finally {
            lock.readLock().unlock();
        }
    }

    /**
     * 整表落盘。
     *
     * <p>先写临时文件再原子替换：这份文件是所有工作空间的索引，
     * 半截写入会直接「丢掉」用户的登记。
     */
    private void writeAll(String userId, List<Workspace> all) {
        Path f = registryFile(userId);
        lock.writeLock().lock();
        try {
            Files.createDirectories(f.getParent());
            Path tmp = f.resolveSibling(f.getFileName() + ".tmp");
            JsonFiles.writer().writerWithDefaultPrettyPrinter().writeValue(tmp.toFile(), all);
            try {
                Files.move(tmp, f, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (IOException atomicFailed) {
                Files.move(tmp, f, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            AppLog.error(e, "工作空间登记表写入失败: %s", f);
            throw new ApiException(500, "保存工作空间登记失败: " + e.getMessage());
        } finally {
            lock.writeLock().unlock();
        }
    }

    /** 首次创建时落一份 AGENTS.md —— AgentScope 据此确定 agent 人格与本地约定 */
    private void initAgentsMd(Workspace w) {
        Path f = w.dataDir().resolve(AGENTS_MD);
        if (Files.exists(f)) {
            return;
        }
        String desc = (w.getDescription() == null || w.getDescription().isBlank())
                ? "（尚未填写描述，可在工作空间里补充 AGENTS.md）"
                : w.getDescription();
        String text = """
                # %s

                %s

                ## 工作空间约定

                - 项目现场：`%s` —— 用**绝对路径**访问，读写都已开放给你。
                - **相对路径的实际落点**：`<项目现场>/<你的登录用户>/…`（不是当前目录，也不是项目现场根）。
                  想放哪儿就直接写绝对路径，不要靠 `..` 往外走。
                - **产出成品（代码 / 文档 / 报告 / 表格 / 图片…）用 `deliver_file` 把内容直接交给应用**，
                  不要写进项目目录 —— 项目现场是用户自己的仓库或文档目录，不该被产物污染。只有
                  「内容超过 2MB」或「成品本来就是工作空间里已有的文件」时，才写成项目现场的绝对路径
                  再用 `deliver_artifact` 交付。
                - 改动项目现场里**已有的**文件前先说清「要改哪些文件、为什么」，让用户确认后再动手；
                  `shellEnabled` 未开启时你既无法执行命令，也无法删除自己误建的文件，请如实告知用户
                  而不是重复尝试。
                - 与本项目相关的长期事实（决策、约定、术语、人名）写入 `MEMORY.md`。
                - 每日进展与临时结论写入 `memory/<日期>.md`，不要堆进 `MEMORY.md`。
                - 回答用中文，结论先行，结构清晰。
                """.formatted(w.getName(), desc, w.getDirectory());
        try {
            Files.writeString(f, text, StandardCharsets.UTF_8);
        } catch (IOException e) {
            AppLog.error(e, "写入 AGENTS.md 失败: %s", f);
        }
    }

    private static boolean writable(Path dir) {
        try {
            Path probe = dir.resolve(".fastagent-write-probe");
            Files.writeString(probe, "ok");
            Files.deleteIfExists(probe);
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    private static String newId() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, 12);
    }

    private static void deleteRecursively(Path dir) {
        if (dir == null || !Files.exists(dir)) {
            return;
        }
        try (Stream<Path> walk = Files.walk(dir)) {
            walk.sorted(Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException e) {
                    AppLog.warn("删除失败: %s", p);
                }
            });
        } catch (IOException e) {
            AppLog.error(e, "删除目录失败: %s", dir);
        }
    }
}
