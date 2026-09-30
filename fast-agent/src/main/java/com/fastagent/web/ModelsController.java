package com.fastagent.web;

import com.fastagent.UserPaths;
import com.fastagent.auth.AuthSupport;
import com.fastagent.model.ModelEntry;
import com.fastagent.model.ModelsService;
import com.fastagent.service.ChatService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;

/**
 * 模型配置接口，读写 {@code ~/.jagent/<userId>/models.json}（按用户隔离）。
 *
 * <p>注意：模型 id 里通常带斜杠（如 {@code deepseek-ai/DeepSeek-V4-Flash}），
 * 放进路径会被 Tomcat 拦掉，因此 id 一律走查询参数或请求体。
 */
@RestController
@RequestMapping("/api/models")
public class ModelsController {

    private final ModelsService modelsService;
    private final ChatService chatService;

    public ModelsController(ModelsService modelsService, ChatService chatService) {
        this.modelsService = modelsService;
        this.chatService = chatService;
    }

    @GetMapping
    public ModelsView list(HttpServletRequest req) {
        return view(AuthSupport.requireUserId(req));
    }

    /** 整表替换（界面上的「保存」） */
    @PutMapping
    public ModelsView saveAll(@RequestBody List<ModelView> incoming, HttpServletRequest req) {
        String userId = AuthSupport.requireUserId(req);
        List<ModelEntry> entries = incoming == null ? List.of()
                : incoming.stream().map(ModelView::toEntry).toList();
        modelsService.saveAll(userId, entries);
        chatService.invalidateAgents(userId);
        return view(userId);
    }

    /**
     * 新增或更新一条。
     *
     * <p>{@code originalId} 只在「编辑并改了模型名」时需要：id 同时是发给模型的模型名，
     * 界面上可以改，但改完必须仍是同一条记录（重命名），不能变成新增。
     * 不传即新增；新增时同名会被覆盖。
     */
    @PostMapping
    public ModelsView upsert(@RequestBody ModelView incoming,
                             @RequestParam(value = "originalId", required = false) String originalId,
                             HttpServletRequest req) {
        String userId = AuthSupport.requireUserId(req);
        modelsService.upsert(userId, originalId, incoming.toEntry());
        chatService.invalidateAgents(userId);
        return view(userId);
    }

    /**
     * 调整顺序（数组顺序即优先级）。
     *
     * <p>只接收 id 数组，其余字段从现有配置取 —— 让排序成为一个不会碰到 Key 的纯操作。
     */
    @PostMapping("/reorder")
    public ModelsView reorder(@RequestBody List<String> ids, HttpServletRequest req) {
        String userId = AuthSupport.requireUserId(req);
        modelsService.reorder(userId, ids);
        chatService.invalidateAgents(userId);
        return view(userId);
    }

    @DeleteMapping
    public ModelsView delete(@RequestParam("id") String id, HttpServletRequest req) {
        String userId = AuthSupport.requireUserId(req);
        modelsService.delete(userId, id);
        chatService.invalidateAgents(userId);
        return view(userId);
    }

    /** 切换当前使用的模型 */
    @PostMapping("/activate")
    public ModelsView activate(@RequestParam("id") String id, HttpServletRequest req) {
        String userId = AuthSupport.requireUserId(req);
        modelsService.activate(userId, id);
        chatService.invalidateAgents(userId);
        return view(userId);
    }

    private ModelsView view(String userId) {
        List<ModelView> list = modelsService.list(userId).stream().map(ModelView::of).toList();
        ModelEntry active = modelsService.active(userId);
        return ModelsView.of(list, active == null ? null : active.getId(),
                UserPaths.of(userId).modelsFile().toString(),
                new LinkedHashMap<>(com.fastagent.config.AppConfig.presets()));
    }
}
