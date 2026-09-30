package com.fastagent.web;

import com.fastagent.UserPaths;
import com.fastagent.agent.AssetService;
import com.fastagent.agent.ToolsFile;
import com.fastagent.auth.AuthSupport;
import com.fastagent.service.ChatService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 用户级资产接口：记忆、工具、技能、路径。
 *
 * <p>这些内容落在 {@code ~/.jagent/<userId>/} 下（按用户隔离），界面与手改文件等效。
 */
@RestController
@RequestMapping("/api/global")
public class GlobalController {

    private final AssetService assets;
    private final ChatService chatService;

    public GlobalController(AssetService assets, ChatService chatService) {
        this.assets = assets;
        this.chatService = chatService;
    }

    public record MemoryView(String path, String content) {
    }

    public record SkillsView(String dir, List<AssetService.SkillInfo> skills) {
    }

    /**
     * @param config 直接返回文件结构（{@link ToolsFile}），因为它带 {@code shellEnabled} ——
     *               AgentScope 的 {@code ToolsConfig} 里没有这个字段，转换一次就丢了
     */
    public record ToolsView(String path, ToolsFile config, List<AssetService.ToolInfo> tools) {
    }

    public record SaveMemoryRequest(String content) {
    }

    @GetMapping("/paths")
    public Map<String, String> paths(HttpServletRequest req) {
        return assets.paths(AuthSupport.requireUserId(req));
    }

    // ---------- 全局记忆 ----------

    @GetMapping("/memory")
    public MemoryView memory(HttpServletRequest req) {
        String userId = AuthSupport.requireUserId(req);
        return new MemoryView(assets.globalMemoryPath(userId).toString(),
                assets.readGlobalMemory(userId));
    }

    /** 保存后重建 agent —— 全局记忆是构建期注入的，不重建不会生效 */
    @PutMapping("/memory")
    public MemoryView saveMemory(@RequestBody SaveMemoryRequest req, HttpServletRequest http) {
        String userId = AuthSupport.requireUserId(http);
        assets.saveGlobalMemory(userId, req == null ? "" : req.content());
        chatService.invalidateAgents(userId);
        return memory(http);
    }

    // ---------- 工具 ----------

    @GetMapping("/tools")
    public ToolsView tools(HttpServletRequest req) {
        String userId = AuthSupport.requireUserId(req);
        ToolsFile f = assets.toolsFile(userId);
        return new ToolsView(assets.toolsConfigPath(userId).toString(),
                f == null ? new ToolsFile() : f, assets.tools(userId));
    }

    /**
     * 保存工具配置。
     *
     * <p>收的是文件结构而不是 AgentScope 的 {@code ToolsConfig}：后者没有 {@code shellEnabled}，
     * 经它中转一次开关就丢了。改完重建 agent —— <b>工具集是构建期装配的</b>。
     */
    @PutMapping("/tools")
    public ToolsView saveTools(@RequestBody ToolsFile file, HttpServletRequest req) {
        String userId = AuthSupport.requireUserId(req);
        assets.saveToolsFile(userId, file);
        chatService.invalidateAgents(userId);
        return tools(req);
    }

    // ---------- 技能 ----------

    @GetMapping("/skills")
    public SkillsView skills(HttpServletRequest req) {
        String userId = AuthSupport.requireUserId(req);
        return new SkillsView(UserPaths.of(userId).skillsDir().toString(), assets.skills(userId));
    }
}
