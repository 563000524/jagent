package com.fastagent.web;

import com.fastagent.AppLog;
import com.fastagent.auth.AuthSupport;
import com.fastagent.service.ApiException;
import com.fastagent.util.Opener;
import com.fastagent.workspace.Workspace;
import com.fastagent.workspace.WorkspaceService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/** 工作空间接口：列表 / 创建 / 详情 / 移除 / 工作空间级记录。 */
@RestController
@RequestMapping("/api/workspaces")
public class WorkspaceController {

    private final WorkspaceService workspaces;

    public WorkspaceController(WorkspaceService workspaces) {
        this.workspaces = workspaces;
    }

    @GetMapping
    public List<WorkspaceView> list(HttpServletRequest req) {
        return workspaces.list(AuthSupport.requireUserId(req)).stream()
                .map(WorkspaceView::of)
                .toList();
    }

    /**
     * 创建工作空间。
     *
     * <p>{@code directory} 是用户在界面上指定的目录，agent 的数据落在它下面的
     * {@code .jagentspace/}。目录不存在会自动创建。
     */
    @PostMapping
    public WorkspaceView create(@RequestBody Map<String, String> body, HttpServletRequest req) {
        String userId = AuthSupport.requireUserId(req);
        Workspace w = workspaces.create(userId, body.get("name"), body.get("directory"),
                body.get("description"));
        return WorkspaceView.of(w);
    }

    @GetMapping("/{id}")
    public WorkspaceView get(@PathVariable String id, HttpServletRequest req) {
        return WorkspaceView.of(workspaces.require(AuthSupport.requireUserId(req), id));
    }

    /**
     * 移除工作空间。
     *
     * <p>默认只摘登记、不动磁盘；{@code purge=true} 时额外删掉用户目录下的
     * {@code .workspace}（用户自己的文件一律不碰）。
     */
    @DeleteMapping("/{id}")
    public Map<String, Object> delete(@PathVariable String id,
                                      @RequestParam(name = "purge", defaultValue = "false") boolean purge,
                                      HttpServletRequest req) {
        workspaces.delete(AuthSupport.requireUserId(req), id, purge);
        return Map.of("ok", true, "purged", purge);
    }

    /**
     * 打开某工作空间的数据目录（{@code <用户目录>/.workspace}）供用户查看。
     *
     * <p>路径由后端从登记表解析，界面不传路径。
     */
    @PostMapping("/{id}/open-dir")
    public Map<String, Object> openDir(@PathVariable String id, HttpServletRequest req) {
        workspaces.require(AuthSupport.requireUserId(req), id);
        Path dir = workspaces.pathOf(AuthSupport.requireUserId(req), id);
        if (!Files.isDirectory(dir)) {
            throw new ApiException(404, "目录不存在: " + dir);
        }
        Opener.openDirectory(dir.toString());
        return Map.of("dir", dir.toString());
    }

    /**
     * 工作空间级记录：长期记忆、项目约定、每日事实。
     *
     * <p>这些都是 AgentScope 在会话过程中自动写进工作空间目录的文件，
     * 直接读原文展示是最透明的做法（用户可自行编辑）。
     *
     * <p>注意作用域不同：{@code AGENTS.md} 是<b>空间级</b>的（全空间一份，在工作空间数据根下），
     * 而长期记忆与每日记录是<b>用户级</b>的（在数据根下的 {@code <userId>/} 里）。
     */
    @GetMapping("/{id}/records")
    public Map<String, Object> records(@PathVariable String id, HttpServletRequest req) {
        String userId = AuthSupport.requireUserId(req);
        workspaces.require(userId, id);
        Path dir = workspaces.pathOf(userId, id);
        // 目录名取净化后的 userId，与 UserPaths 的落盘规则保持一致
        Path userDir = dir.resolve(com.fastagent.UserPaths.of(userId).userId());

        Map<String, Object> m = new LinkedHashMap<>();
        m.put("root", dir.toAbsolutePath().toString());
        m.put("memory", readIfExists(userDir.resolve(WorkspaceService.MEMORY_MD)));
        m.put("agents", readIfExists(dir.resolve(WorkspaceService.AGENTS_MD)));
        m.put("daily", listDaily(userDir.resolve("memory")));
        return m;
    }

    private static String readIfExists(Path f) {
        if (!Files.isRegularFile(f)) {
            return "";
        }
        try {
            return Files.readString(f, StandardCharsets.UTF_8);
        } catch (IOException e) {
            AppLog.warn("读取失败: %s", f);
            return "";
        }
    }

    /** 每日事实文件列表（memory/YYYY-MM-DD.md），按文件名倒序 */
    private static List<Map<String, Object>> listDaily(Path memoryDir) {
        List<Map<String, Object>> out = new ArrayList<>();
        if (!Files.isDirectory(memoryDir)) {
            return out;
        }
        try (Stream<Path> files = Files.list(memoryDir)) {
            files.filter(p -> p.getFileName().toString().endsWith(".md"))
                    .sorted((a, b) -> b.getFileName().toString().compareTo(a.getFileName().toString()))
                    .forEach(p -> {
                        Map<String, Object> item = new LinkedHashMap<>();
                        item.put("date", p.getFileName().toString().replace(".md", ""));
                        item.put("content", readIfExists(p));
                        out.add(item);
                    });
        } catch (IOException e) {
            AppLog.warn("列出每日记录失败: %s", memoryDir);
        }
        return out;
    }
}
