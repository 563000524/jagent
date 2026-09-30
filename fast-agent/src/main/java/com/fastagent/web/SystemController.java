package com.fastagent.web;

import com.fastagent.AppLog;
import com.fastagent.AppPaths;
import com.fastagent.UserPaths;
import com.fastagent.auth.AuthSupport;
import com.fastagent.service.ApiException;
import com.fastagent.ui.DirectoryPicker;
import com.fastagent.util.Opener;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/** 系统级接口：健康检查、前端日志上报、打开日志/配置目录。 */
@RestController
@RequestMapping("/api/system")
public class SystemController {

    private static final String VERSION = "0.1.0";

    /** 前端启动后探活，同时把运行环境写进日志，便于定位「界面没反应」 */
    @GetMapping("/health")
    public Map<String, Object> health() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("status", "ok");
        m.put("version", VERSION);
        // health 是免鉴权的探活接口，拿不到登录用户，所以只报进程级路径；
        // 按用户的数据路径见 /api/global/paths（那个走鉴权）
        m.put("baseDir", AppPaths.baseDir().toString());
        m.put("logPath", AppPaths.logFile().toString());
        m.put("logDir", AppPaths.logDir().toString());
        m.put("exeDir", String.valueOf(AppPaths.exeDir()));
        return m;
    }

    @PostMapping("/log")
    public Map<String, Object> report(@RequestBody Map<String, String> body) {
        String level = body.getOrDefault("level", "info");
        String message = body.getOrDefault("message", "");
        switch (level.toLowerCase()) {
            case "error" -> AppLog.error("[前端] %s", message);
            case "warn" -> AppLog.warn("[前端] %s", message);
            default -> AppLog.info("[前端] %s", message);
        }
        return Map.of("ok", true);
    }

    @PostMapping("/open-log-dir")
    public Map<String, Object> openLogDir() {
        return open(String.valueOf(AppPaths.logDir()), "log");
    }

    /**
     * 弹出系统「选择文件夹」对话框，返回用户选中的绝对路径。
     *
     * <p>界面跑在 WebView 里，浏览器端拿不到可靠的本地绝对路径，所以「新建工作空间」的目录
     * 由 JavaFX 侧弹原生 {@code DirectoryChooser}。请求会阻塞到用户选完或取消
     * （用户主动操作，且同时只允许开一个框）。
     *
     * @param body 可选 {@code {"initialDir": "..."}} —— 有值时作为对话框的起始目录
     * @return {@code {"dir": "D:\\projects\\x", "cancelled": false}}；用户取消时 dir 是空串
     */
    @PostMapping("/pick-directory")
    public Map<String, Object> pickDirectory(@RequestBody(required = false) Map<String, String> body) {
        String initial = body == null ? null : body.get("initialDir");
        try {
            String dir = DirectoryPicker.pick("选择工作空间目录", initial);
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("dir", dir == null ? "" : dir);
            m.put("cancelled", dir == null);
            return m;
        } catch (IllegalStateException e) {
            // 非桌面模式（浏览器调试）或已有选择框打开：给可读提示，前端据此让用户手填
            throw new ApiException(400, e.getMessage());
        }
    }

    /**
     * 打开一个配置目录。
     *
     * <p>只接受白名单里的名字，不让界面传任意路径 —— 虽然同进程内信任级别相同，
     * 但少一个「参数就是路径」的口子总是好的。
     * 工作空间的数据目录是用户自己挑的，走 {@code /api/workspaces/{id}/open-dir}
     * （由后端从登记表解析路径，同样不接受界面传路径）。
     *
     * <p>配置目录是<b>用户级</b>的（{@code ~/.jagent/<userId>/}），所以要按当前登录用户解析。
     */
    @PostMapping("/open-dir")
    public Map<String, Object> openDir(@RequestBody Map<String, String> body, HttpServletRequest req) {
        String userId = AuthSupport.requireUserId(req);
        UserPaths p = UserPaths.of(userId);
        String name = body == null ? null : body.get("name");
        String dir = switch (name == null ? "" : name) {
            case "agent" -> String.valueOf(p.root());
            case "models" -> String.valueOf(p.modelsFile().getParent());
            case "memory" -> String.valueOf(p.memoryDir());
            case "tools" -> String.valueOf(p.toolDir());
            case "skills" -> String.valueOf(p.skillsDir());
            case "log" -> String.valueOf(AppPaths.logDir());
            default -> null;
        };
        if (dir == null) {
            throw new IllegalArgumentException("不支持的目录标识: " + name);
        }
        return open(dir, name);
    }

    private Map<String, Object> open(String dir, String label) {
        AppLog.info("打开目录(%s): %s", label, dir);
        Opener.openDirectory(dir);
        return Map.of("dir", dir);
    }
}
