package com.fastagent.agent;

import com.fastagent.AppLog;
import com.fastagent.AppPaths;
import com.fastagent.UserPaths;
import com.fastagent.util.JsonFiles;
import io.agentscope.core.model.Model;
import io.agentscope.core.skill.AgentSkill;
import io.agentscope.core.skill.repository.FileSystemSkillRepository;
import io.agentscope.core.state.JsonFileAgentStateStore;
import io.agentscope.extensions.model.openai.OpenAIChatModel;
import io.agentscope.harness.agent.HarnessAgent;
import io.agentscope.harness.agent.tools.ToolsConfig;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 用户级资产管理：{@code memory/memory.md}、{@code tool/tools.json}、{@code skills/}。
 *
 * <p>这三样都落在 {@code ~/.jagent/<userId>/} 下、可手改；本类只负责读写与呈现，
 * 不持有状态。<b>按用户隔离</b>：全局记忆与技能是个人资产，换个账号登录不该看见
 * 上一个人的东西，所以每个方法都显式收 userId。
 */
@Service
public class AssetService {

    /** 每个技能一条：名称、描述、所在目录 */
    public record SkillInfo(String name, String description, String dir) {
    }

    /** 每个工具一条：名称、来源（内置 / MCP 服务器名）、当前是否启用 */
    public record ToolInfo(String name, String source, boolean enabled) {
    }

    /** 内置工具名缓存：取一次要构建一个临时 agent，代价不低，且与用户无关，缓存住 */
    private volatile List<String> builtinTools;

    // ---------- 路径 ----------

    public String agentDir(String userId) {
        return UserPaths.of(userId).root().toString();
    }

    public Map<String, String> paths(String userId) {
        UserPaths p = UserPaths.of(userId);
        p.ensureLayout();
        Map<String, String> m = new LinkedHashMap<>();
        m.put("agentDir", p.root().toString());
        m.put("models", p.modelsFile().toString());
        m.put("config", p.configFile().toString());
        m.put("memory", p.globalMemoryFile().toString());
        m.put("tools", p.toolsConfigFile().toString());
        m.put("skills", p.skillsDir().toString());
        m.put("workspaces", p.workspacesFile().toString());
        // 日志与用户无关，跟着程序目录走
        m.put("log", AppPaths.logFile().toString());
        return m;
    }

    // ---------- 全局记忆 ----------

    public Path globalMemoryPath(String userId) {
        return UserPaths.of(userId).globalMemoryFile();
    }

    /** 读全局记忆；文件不存在或读失败返回空串 */
    public String readGlobalMemory(String userId) {
        UserPaths paths = UserPaths.of(userId);
        Path f = paths.globalMemoryFile();
        try {
            if (!Files.exists(f)) {
                paths.ensureLayout();
            }
            return Files.exists(f) ? Files.readString(f, StandardCharsets.UTF_8) : "";
        } catch (IOException e) {
            AppLog.error(e, "读取全局记忆失败: %s", f);
            return "";
        }
    }

    public void saveGlobalMemory(String userId, String content) {
        Path f = globalMemoryPath(userId);
        try {
            Files.createDirectories(f.getParent());
            Files.writeString(f, content == null ? "" : content, StandardCharsets.UTF_8);
            AppLog.info("全局记忆已保存: user=%s %s（%d 字）",
                    userId, f, content == null ? 0 : content.length());
        } catch (IOException e) {
            AppLog.error(e, "保存全局记忆失败: %s", f);
            throw new IllegalStateException("保存全局记忆失败: " + e.getMessage(), e);
        }
    }

    // ---------- 工具 ----------

    public Path toolsConfigPath(String userId) {
        return UserPaths.of(userId).toolsConfigFile();
    }

    /**
     * 读工具配置文件。
     *
     * <p>缺失时先补一次目录再试；仍缺失或解析失败都返回 {@code null}，
     * 而不是抛异常 —— 手工改坏 tools.json 不应该让整个应用起不来。
     * 调用方拿到 null 时按「默认工具集 + shell 关闭」处理。
     */
    public ToolsFile toolsFile(String userId) {
        UserPaths paths = UserPaths.of(userId);
        Path f = paths.toolsConfigFile();
        try {
            if (!Files.exists(f)) {
                paths.ensureLayout();
                if (!Files.exists(f)) {
                    return null;
                }
            }
            return JsonFiles.reader().readValue(f.toFile(), ToolsFile.class);
        } catch (IOException e) {
            AppLog.error(e, "工具配置解析失败，本次按默认工具构建: %s", f);
            return null;
        }
    }

    /** 转成 AgentScope 的配置对象；文件缺失时返回 null（表示不改动默认工具集） */
    public ToolsConfig toolsConfig(String userId) {
        ToolsFile f = toolsFile(userId);
        return f == null ? null : f.toToolsConfig();
    }

    /**
     * 保存工具配置。
     *
     * <p>直接收 {@link ToolsFile} 而不是 {@link ToolsConfig}：后者没有 {@code shellEnabled}，
     * 经它中转一次就会把开关丢掉。
     */
    public void saveToolsFile(String userId, ToolsFile file) {
        Path f = toolsConfigPath(userId);
        try {
            Files.createDirectories(f.getParent());
            JsonFiles.writer().writerWithDefaultPrettyPrinter()
                    .writeValue(f.toFile(), file == null ? new ToolsFile() : file);
            AppLog.info("工具配置已保存: user=%s %s", userId, f);
        } catch (IOException e) {
            AppLog.error(e, "保存工具配置失败: %s", f);
            throw new IllegalStateException("保存工具配置失败: " + e.getMessage(), e);
        }
    }

    /**
     * 当前生效的工具清单。
     *
     * <p>内置工具名来自一个临时 agent（只为了拿到名字，用完即关）；
     * 再按 allow / deny 规则算出启用状态。MCP 服务器只列服务器名，
     * 真正的工具名要连上之后才知道，这里不做连接。
     */
    public List<ToolInfo> tools(String userId) {
        ToolsConfig cfg = toolsConfig(userId);
        List<String> allow = cfg == null || cfg.getAllow() == null ? List.of() : cfg.getAllow();
        List<String> deny = cfg == null || cfg.getDeny() == null ? List.of() : cfg.getDeny();

        List<ToolInfo> out = new ArrayList<>();
        for (String name : builtinToolNames()) {
            boolean enabled = (allow.isEmpty() || allow.contains(name)) && !deny.contains(name);
            out.add(new ToolInfo(name, "内置", enabled));
        }
        if (cfg != null && cfg.getMcpServers() != null) {
            for (Map.Entry<String, ?> e : cfg.getMcpServers().entrySet()) {
                out.add(new ToolInfo(e.getKey(), "MCP", true));
            }
        }
        return out;
    }

    /**
     * 内置工具名：构建一个不带任何配置的临时 agent 拿一次，之后走缓存。
     *
     * <p>与用户无关，因此不按用户缓存。临时目录放在数据根下（{@code ~/.jagent/.tmp-toolprobe}），
     * 不污染任何一个用户目录。
     */
    public List<String> builtinToolNames() {
        List<String> cached = builtinTools;
        if (cached != null) {
            return cached;
        }
        synchronized (this) {
            if (builtinTools != null) {
                return builtinTools;
            }
            List<String> names = new ArrayList<>();
            Path tmp = AppPaths.baseDir().resolve(".tmp-toolprobe");
            try {
                Files.createDirectories(tmp.resolve("ws"));
                Files.createDirectories(tmp.resolve("state"));
                Model stub = OpenAIChatModel.builder()
                        .apiKey("probe")
                        .baseUrl("http://127.0.0.1:1/v1")
                        .modelName("probe")
                        .stream(true)
                        .build();
                try (HarnessAgent a = HarnessAgent.builder()
                        .name("tool-probe")
                        .sysPrompt("probe")
                        .model(stub)
                        .workspace(tmp.resolve("ws"))
                        .stateStore(new JsonFileAgentStateStore(tmp.resolve("state")))
                        .build()) {
                    names.addAll(a.getToolkit().getToolNames());
                }
                names.sort(String::compareTo);
            } catch (Exception e) {
                AppLog.warn("枚举内置工具失败，工具面板将只显示 MCP 项: %s", e.getMessage());
            }
            builtinTools = names;
            return names;
        }
    }

    // ---------- 技能 ----------

    /** 读技能目录；没有 SKILL.md 的子目录不算技能 */
    public List<SkillInfo> skills(String userId) {
        Path dir = UserPaths.of(userId).skillsDir();
        List<SkillInfo> out = new ArrayList<>();
        try {
            Files.createDirectories(dir);
        } catch (IOException e) {
            AppLog.warn("创建技能目录失败: %s", dir);
            return out;
        }
        try (FileSystemSkillRepository repo = new FileSystemSkillRepository(dir)) {
            for (AgentSkill s : repo.getAllSkills()) {
                String where = s.getOriginDir().map(Path::toString).orElse(dir.resolve(s.getName()).toString());
                out.add(new SkillInfo(s.getName(), s.getDescription(), where));
            }
        } catch (Exception e) {
            AppLog.error(e, "读取技能目录失败: %s", dir);
        }
        out.sort((a, b) -> a.name().compareToIgnoreCase(b.name()));
        return out;
    }
}
