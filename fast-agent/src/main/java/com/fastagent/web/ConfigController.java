package com.fastagent.web;

import com.fastagent.auth.AuthSupport;
import com.fastagent.config.AppConfig;
import com.fastagent.config.ConfigService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;

/**
 * 设置接口：系统提示词与生成参数。按用户隔离，落盘于
 * {@code ~/.jagent/<userId>/config.yaml}。
 *
 * <p>模型配置见 {@link ModelsController}（{@code /api/models}）。
 */
@RestController
@RequestMapping("/api/config")
public class ConfigController {

    private final ConfigService configService;

    public ConfigController(ConfigService configService) {
        this.configService = configService;
    }

    @GetMapping
    public ConfigView get(HttpServletRequest req) {
        String userId = AuthSupport.requireUserId(req);
        return view(userId, configService.get(userId));
    }

    @PostMapping
    public ConfigView save(@RequestBody ConfigView incoming, HttpServletRequest req) {
        String userId = AuthSupport.requireUserId(req);
        AppConfig next = configService.get(userId).copy();
        next.setSystemPrompt(incoming.getSystemPrompt());
        next.setTemperature(incoming.getTemperature());
        next.setEnableThinking(incoming.isEnableThinking());
        next.setMaxHistory(incoming.getMaxHistory());
        return view(userId, configService.save(userId, next));
    }

    private ConfigView view(String userId, AppConfig cfg) {
        return ConfigView.of(userId, cfg, new LinkedHashMap<>(configService.presets()));
    }
}
