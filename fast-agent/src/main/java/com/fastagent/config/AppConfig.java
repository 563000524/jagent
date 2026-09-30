package com.fastagent.config;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 应用级设置，落盘于 {@code .fastagent/config.yaml}。
 *
 * <p>模型相关的字段（供应商 / URL / Key / 模型名）已经搬到 {@code models.json}，
 * 由 {@link com.fastagent.model.ModelsService} 统一管理，这里只留与模型无关的部分。
 */
public class AppConfig {

    public static final String PROVIDER_SILICONFLOW = "siliconflow";
    public static final String PROVIDER_DEEPSEEK = "deepseek";
    public static final String PROVIDER_GLM = "glm";
    public static final String PROVIDER_OPENAI = "openai";
    public static final String PROVIDER_OLLAMA = "ollama";

    private String systemPrompt;
    private double temperature = 0.7;
    private boolean enableThinking = true;
    private int maxHistory = 20;

    public static final String DEFAULT_SYSTEM_PROMPT =
            "你是一个专业、严谨的办公智能体助手，擅长公文写作、会议纪要整理、数据汇总分析与日常事务处理。"
                    + "回答需结构化、结论先行、简洁准确。";

    /** 新增模型时的供应商预设：填好 URL 与模型名，用户只需补 Key */
    public static Map<String, Preset> presets() {
        Map<String, Preset> m = new LinkedHashMap<>();
        m.put(PROVIDER_SILICONFLOW, new Preset("SiliconFlow",
                "https://api.siliconflow.cn/v1/chat/completions", "deepseek-ai/DeepSeek-V4-Flash"));
        m.put(PROVIDER_DEEPSEEK, new Preset("DeepSeek",
                "https://api.deepseek.com/chat/completions", "deepseek-v4-flash"));
        m.put(PROVIDER_GLM, new Preset("GLM Coding Plan",
                "https://open.bigmodel.cn/api/coding/paas/v4/chat/completions", "glm-5"));
        m.put(PROVIDER_OPENAI, new Preset("OpenAI",
                "https://api.openai.com/v1/chat/completions", "gpt-4o-mini"));
        m.put(PROVIDER_OLLAMA, new Preset("Ollama",
                "http://127.0.0.1:11434/v1/chat/completions", "qwen2.5:7b"));
        return m;
    }

    public static AppConfig defaults() {
        AppConfig c = new AppConfig();
        c.systemPrompt = DEFAULT_SYSTEM_PROMPT;
        c.temperature = 0.7;
        c.enableThinking = true;
        c.maxHistory = 20;
        return c;
    }

    /** 归一化：补齐默认值，避免手工改坏配置导致启动异常 */
    public void normalize() {
        if (maxHistory <= 0) {
            maxHistory = 20;
        }
        if (temperature < 0 || temperature > 2) {
            temperature = 0.7;
        }
        if (systemPrompt == null || systemPrompt.isBlank()) {
            systemPrompt = DEFAULT_SYSTEM_PROMPT;
        }
    }

    public AppConfig copy() {
        AppConfig c = new AppConfig();
        c.systemPrompt = systemPrompt;
        c.temperature = temperature;
        c.enableThinking = enableThinking;
        c.maxHistory = maxHistory;
        return c;
    }

    public String getSystemPrompt() {
        return systemPrompt;
    }

    public void setSystemPrompt(String systemPrompt) {
        this.systemPrompt = systemPrompt;
    }

    public double getTemperature() {
        return temperature;
    }

    public void setTemperature(double temperature) {
        this.temperature = temperature;
    }

    public boolean isEnableThinking() {
        return enableThinking;
    }

    public void setEnableThinking(boolean enableThinking) {
        this.enableThinking = enableThinking;
    }

    public int getMaxHistory() {
        return maxHistory;
    }

    public void setMaxHistory(int maxHistory) {
        this.maxHistory = maxHistory;
    }

    /** 供应商预设 */
    public record Preset(String vendor, String url, String model) {
    }
}
