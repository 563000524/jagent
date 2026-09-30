package com.fastagent.config;

import com.fastagent.AppLog;
import com.fastagent.UserPaths;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.fasterxml.jackson.dataformat.yaml.YAMLGenerator;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * 应用设置读写，落盘于 {@code ~/.jagent/<userId>/config.yaml}。
 *
 * <p>模型相关配置不在这里，见 {@link com.fastagent.model.ModelsService}。
 *
 * <p><b>按用户隔离</b>：系统提示词、温度、深度思考开关都属于个人偏好，
 * 同一台机器上换个账号登录不应看到上一个人的设置。缓存按用户各存一份。
 */
@Service
public class ConfigService {

    private static final ObjectMapper YAML = new ObjectMapper(
            YAMLFactory.builder()
                    .disable(YAMLGenerator.Feature.WRITE_DOC_START_MARKER)
                    .build())
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    /** 全局一把锁保护所有用户：保存设置是低频操作 */
    private final ReentrantReadWriteLock lock = new ReentrantReadWriteLock();
    private final Map<String, AppConfig> cache = new ConcurrentHashMap<>();

    public AppConfig get(String userId) {
        AppConfig c = cache.get(userId);
        return c != null ? c : load(userId);
    }

    /** 首次运行写入默认配置；文件损坏时不阻塞启动，回落默认值 */
    private AppConfig load(String userId) {
        UserPaths paths = UserPaths.of(userId);
        paths.ensureLayout();
        Path file = paths.configFile();
        AppConfig cfg;
        if (!Files.exists(file)) {
            cfg = AppConfig.defaults();
            persist(userId, cfg);
            AppLog.info("配置文件不存在，已写入默认配置: %s", file);
        } else {
            try {
                cfg = YAML.readValue(file.toFile(), AppConfig.class);
                if (cfg == null) {
                    cfg = AppConfig.defaults();
                }
            } catch (IOException e) {
                AppLog.error(e, "配置解析失败，回落默认配置: %s", file);
                cfg = AppConfig.defaults();
            }
        }
        cfg.normalize();
        cache.put(userId, cfg);
        return cfg;
    }

    public AppConfig save(String userId, AppConfig incoming) {
        lock.writeLock().lock();
        try {
            AppConfig next = incoming == null ? AppConfig.defaults() : incoming.copy();
            next.normalize();
            persist(userId, next);
            cache.put(userId, next);
            AppLog.info("设置已保存: user=%s 温度=%s 上下文条数=%s 深度思考=%s",
                    userId, next.getTemperature(), next.getMaxHistory(), next.isEnableThinking());
            return next;
        } finally {
            lock.writeLock().unlock();
        }
    }

    private void persist(String userId, AppConfig cfg) {
        Path file = UserPaths.of(userId).configFile();
        try {
            Files.createDirectories(file.getParent());
            YAML.writeValue(file.toFile(), cfg);
        } catch (IOException e) {
            AppLog.error(e, "配置写入失败: %s", file);
        }
    }

    public Map<String, AppConfig.Preset> presets() {
        return AppConfig.presets();
    }
}
