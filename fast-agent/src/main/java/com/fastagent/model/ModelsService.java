package com.fastagent.model;

import com.fastagent.AppLog;
import com.fastagent.UserPaths;
import com.fastagent.service.ApiException;
import com.fastagent.util.JsonFiles;
import com.fasterxml.jackson.core.type.TypeReference;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * 模型配置读写，落盘于 {@code ~/.jagent/<userId>/models.json}。
 *
 * <p>文件结构与 WorkBuddy 一致（顶层是数组），因此两边可以互换。
 * 本应用额外支持每条上的 {@code active} 标记来指定当前模型。
 *
 * <p><b>按用户隔离</b>：配置里含 API Key，多账号共用一台机器时互不可见。
 * 因此每个方法都要求显式传入 userId —— 不用 ThreadLocal，因为推理跑在虚拟线程上
 * （见 {@code ChatService.generate}），ThreadLocal 传不过去。
 * 缓存也随之为每个用户各存一份。
 */
@Service
public class ModelsService {

    /** 全局一把锁保护所有用户的读写：保存配置是低频操作，不值得为分用户并发再复杂化 */
    private final ReentrantReadWriteLock lock = new ReentrantReadWriteLock();
    private final Map<String, List<ModelEntry>> cache = new ConcurrentHashMap<>();

    // ---------- 读 ----------

    /** 全部模型（副本，避免外部改到缓存） */
    public List<ModelEntry> list(String userId) {
        return get(userId).stream().map(ModelEntry::copy).toList();
    }

    /**
     * 当前使用的模型。
     *
     * <p>取用顺序：显式标记 active 的可用项 → 第一个可用项 → null。
     * 这样手工删掉 active 标记、或只留一条模型时都不会「没模型可用」。
     */
    public ModelEntry active(String userId) {
        List<ModelEntry> all = get(userId);
        return all.stream().filter(m -> m.isActive() && m.usable()).findFirst()
                .or(() -> all.stream().filter(ModelEntry::usable).findFirst())
                .map(ModelEntry::copy)
                .orElse(null);
    }

    /**
     * 按 id 取一条；不存在返回 {@code null}。
     *
     * <p>调用方（会话指定了模型但那条被删掉）据此回落到默认模型，而不是让对话直接失败。
     */
    public ModelEntry byId(String userId, String id) {
        if (id == null || id.isBlank()) {
            return null;
        }
        String want = id.trim();
        return get(userId).stream()
                .filter(e -> want.equals(e.getId()))
                .findFirst()
                .map(ModelEntry::copy)
                .orElse(null);
    }

    public boolean hasUsableModel(String userId) {
        return active(userId) != null;
    }

    public Path path(String userId) {
        return UserPaths.of(userId).modelsFile();
    }

    /** 给日志用的一行描述 */
    public String describeActive(String userId) {
        ModelEntry m = active(userId);
        return m == null ? "（未配置可用模型）"
                : "%s [%s] url=%s key已配置=%s".formatted(m.getName(), m.getId(), m.getUrl(), m.hasApiKey());
    }

    /** id -> 展示名，供界面下拉使用 */
    public Map<String, String> options(String userId) {
        Map<String, String> m = new LinkedHashMap<>();
        for (ModelEntry e : get(userId)) {
            m.put(e.getId(), (e.getName() == null || e.getName().isBlank() ? e.getId() : e.getName())
                    + (e.hasApiKey() ? "" : "（未配 Key）"));
        }
        return m;
    }

    // ---------- 写 ----------

    /**
     * 整表保存。
     *
     * <p><b>数组顺序即优先级</b>：界面上的「上移 / 下移」走这里，落盘顺序与界面一致。
     * {@link #active} 在没有 active 标记（或标记那条不可用）时，就按这个顺序取第一个可用的。
     *
     * <p>Key 处理与界面展示对齐：传入为空或仍是脱敏串（含 {@code ****}）时，
     * 保留该 id 已存的真 Key —— 否则界面上回传 {@code sk-ab****yz} 会把真 Key 覆盖掉。
     */
    public List<ModelEntry> saveAll(String userId, List<ModelEntry> incoming) {
        lock.writeLock().lock();
        try {
            List<ModelEntry> old = get(userId);
            Map<String, String> oldKeys = new LinkedHashMap<>();
            for (ModelEntry e : old) {
                if (e.getId() != null && e.hasApiKey()) {
                    oldKeys.put(e.getId(), e.getApiKey());
                }
            }

            List<ModelEntry> next = new ArrayList<>();
            for (ModelEntry e : incoming == null ? List.<ModelEntry>of() : incoming) {
                if (e == null || e.getId() == null || e.getId().isBlank()) {
                    continue;
                }
                ModelEntry c = e.copy();
                String k = c.getApiKey();
                if (k == null || k.isBlank() || k.contains("****")) {
                    String keep = oldKeys.get(c.getId());
                    c.setApiKey(keep == null ? "" : keep);
                }
                c.normalize();
                next.add(c);
            }
            persist(userId, next);
            cache.put(userId, next);
            AppLog.info("模型配置已保存: user=%s 共 %d 条，当前=%s",
                    userId, next.size(), describeActive(userId));
            return list(userId);
        } finally {
            lock.writeLock().unlock();
        }
    }

    /**
     * 新增或更新一条。
     *
     * <p>{@code originalId} 是编辑场景下「本条原本的 id」。
     * {@code id} 同时是发给模型的模型名，界面上允许修改 —— 改完必须仍是同一条记录
     * （重命名），不能变成新增。传空/null 表示新增。
     *
     * @throws ApiException 模型名为空，或改名后与其他条冲突
     */
    public List<ModelEntry> upsert(String userId, String originalId, ModelEntry entry) {
        if (entry == null || entry.getId() == null || entry.getId().isBlank()) {
            throw new ApiException(400, "模型名不能为空");
        }
        List<ModelEntry> all = get(userId);
        String newId = entry.getId().trim();
        String fromId = originalId == null ? "" : originalId.trim();

        List<ModelEntry> next = new ArrayList<>();
        boolean replaced = false;
        boolean conflict = false;
        for (ModelEntry e : all) {
            // 编辑时按「原 id」定位，新增时按新 id 覆盖同名的
            boolean target = fromId.isEmpty() ? newId.equals(e.getId()) : fromId.equals(e.getId());
            if (target) {
                ModelEntry c = entry.copy().withActive(e.isActive());
                // 改名后 saveAll 的「按 id 保留旧 Key」会找不到原记录，这里显式继承
                if (!c.hasApiKey()) {
                    c.setApiKey(e.getApiKey());
                }
                c.normalize();
                next.add(c);
                replaced = true;
            } else {
                if (newId.equals(e.getId())) {
                    conflict = true;
                }
                next.add(e.copy());
            }
        }
        if (conflict) {
            throw new ApiException(400, "模型名「" + newId + "」已被其他模型占用，请换一个");
        }
        if (!replaced) {
            // 新增的第一条自动成为默认模型
            ModelEntry c = entry.copy().withActive(all.isEmpty());
            c.normalize();
            next.add(c);
        }
        return saveAll(userId, next);
    }

    public List<ModelEntry> delete(String userId, String id) {
        List<ModelEntry> next = new ArrayList<>();
        boolean removedActive = false;
        for (ModelEntry e : get(userId)) {
            if (id != null && id.equals(e.getId())) {
                removedActive = e.isActive();
                continue;
            }
            next.add(e.copy());
        }
        // 删掉的恰好是当前模型时，把第一条顶上来，避免「没有可用模型」
        if (removedActive && !next.isEmpty()) {
            next.get(0).setActive(true);
        }
        return saveAll(userId, next);
    }

    /**
     * 按给定 id 顺序重排（数组顺序即优先级）。
     *
     * <p>只认 id 顺序，其余字段保持原样 —— 排序是纯顺序操作，不该有机会碰到 API Key。
     * 未出现在 {@code ids} 里的条目按原有相对顺序补到末尾，避免界面漏传导致丢配置。
     */
    public List<ModelEntry> reorder(String userId, List<String> ids) {
        List<ModelEntry> all = get(userId);
        List<ModelEntry> next = new ArrayList<>();
        if (ids != null) {
            for (String id : ids) {
                if (id == null) {
                    continue;
                }
                all.stream()
                        .filter(e -> id.equals(e.getId()))
                        .findFirst()
                        .ifPresent(e -> next.add(e.copy()));
            }
        }
        for (ModelEntry e : all) {
            boolean already = next.stream().anyMatch(n -> n.getId() != null && n.getId().equals(e.getId()));
            if (!already) {
                next.add(e.copy());
            }
        }
        AppLog.info("模型顺序已调整: user=%s %s", userId, next.stream().map(ModelEntry::getId).toList());
        return saveAll(userId, next);
    }

    /** 切换当前模型 */
    public List<ModelEntry> activate(String userId, String id) {
        List<ModelEntry> next = new ArrayList<>();
        boolean found = false;
        for (ModelEntry e : get(userId)) {
            ModelEntry c = e.copy();
            c.setActive(id != null && id.equals(c.getId()));
            found |= c.isActive();
            next.add(c);
        }
        if (!found) {
            throw new IllegalArgumentException("模型不存在: " + id);
        }
        return saveAll(userId, next);
    }

    // ---------- 内部 ----------

    private List<ModelEntry> get(String userId) {
        List<ModelEntry> c = cache.get(userId);
        return c != null ? c : load(userId);
    }

    /** 文件不存在时写出一个默认条目；解析失败不阻塞启动 */
    private synchronized List<ModelEntry> load(String userId) {
        List<ModelEntry> cached = cache.get(userId);
        if (cached != null) {
            return cached;
        }
        UserPaths paths = UserPaths.of(userId);
        paths.ensureLayout();
        Path file = paths.modelsFile();
        List<ModelEntry> c;
        if (!Files.exists(file)) {
            c = new ArrayList<>();
            persist(userId, c);
            AppLog.info("模型配置不存在，已生成空配置: %s", file);
            cache.put(userId, c);
            return c;
        }
        try {
            List<ModelEntry> parsed = JsonFiles.reader()
                    .readValue(file.toFile(), new TypeReference<List<ModelEntry>>() {
                    });
            c = parsed == null ? new ArrayList<>() : new ArrayList<>(parsed);
        } catch (IOException e) {
            AppLog.error(e, "模型配置解析失败，按空配置处理: %s", file);
            c = new ArrayList<>();
        }
        c.removeIf(m -> m == null || m.getId() == null || m.getId().isBlank());
        c.forEach(ModelEntry::normalize);
        cache.put(userId, c);
        AppLog.info("模型配置已加载: user=%s %s", userId, describeActive(userId));
        return c;
    }

    private void persist(String userId, List<ModelEntry> list) {
        Path file = path(userId);
        try {
            Files.createDirectories(file.getParent());
            JsonFiles.writer().writerWithDefaultPrettyPrinter().writeValue(file.toFile(), list);
        } catch (IOException e) {
            AppLog.error(e, "模型配置写入失败: %s", file);
        }
    }
}
