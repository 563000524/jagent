package com.fastagent.web;

import com.fastagent.AppPaths;
import com.fastagent.UserPaths;
import com.fastagent.config.AppConfig;

import java.util.Map;

/**
 * 设置视图：系统提示词、生成参数，以及各类数据文件的位置。
 *
 * <p>模型相关（供应商 / Key / 模型名）不在这里，见 {@code /api/models}。
 */
public class ConfigView {

    private String systemPrompt;
    private double temperature;
    private boolean enableThinking;
    private int maxHistory;
    private Map<String, AppConfig.Preset> presets;

    // ---- 路径信息（只读，界面用来展示与打开目录）----
    private String agentDir;
    private String configPath;
    private String modelsPath;
    private String memoryPath;
    private String toolsPath;
    private String skillsDir;
    private String workspacesPath;
    private String logPath;
    private String logDir;

    public static ConfigView of(String userId, AppConfig cfg, Map<String, AppConfig.Preset> presets) {
        ConfigView v = new ConfigView();
        v.systemPrompt = cfg.getSystemPrompt();
        v.temperature = cfg.getTemperature();
        v.enableThinking = cfg.isEnableThinking();
        v.maxHistory = cfg.getMaxHistory();
        v.presets = presets;

        // 用户级数据都在 ~/.jagent/<userId>/ 下；日志与用户无关，跟着程序目录走
        UserPaths p = UserPaths.of(userId);
        p.ensureLayout();
        v.agentDir = p.root().toString();
        v.configPath = p.configFile().toString();
        v.modelsPath = p.modelsFile().toString();
        v.memoryPath = p.globalMemoryFile().toString();
        v.toolsPath = p.toolsConfigFile().toString();
        v.skillsDir = p.skillsDir().toString();
        v.workspacesPath = p.workspacesFile().toString();
        v.logPath = AppPaths.logFile().toString();
        v.logDir = AppPaths.logDir().toString();
        return v;
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

    public Map<String, AppConfig.Preset> getPresets() {
        return presets;
    }

    public void setPresets(Map<String, AppConfig.Preset> presets) {
        this.presets = presets;
    }

    public String getAgentDir() {
        return agentDir;
    }

    public void setAgentDir(String agentDir) {
        this.agentDir = agentDir;
    }

    public String getConfigPath() {
        return configPath;
    }

    public void setConfigPath(String configPath) {
        this.configPath = configPath;
    }

    public String getModelsPath() {
        return modelsPath;
    }

    public void setModelsPath(String modelsPath) {
        this.modelsPath = modelsPath;
    }

    public String getMemoryPath() {
        return memoryPath;
    }

    public void setMemoryPath(String memoryPath) {
        this.memoryPath = memoryPath;
    }

    public String getToolsPath() {
        return toolsPath;
    }

    public void setToolsPath(String toolsPath) {
        this.toolsPath = toolsPath;
    }

    public String getSkillsDir() {
        return skillsDir;
    }

    public void setSkillsDir(String skillsDir) {
        this.skillsDir = skillsDir;
    }

    public String getWorkspacesPath() {
        return workspacesPath;
    }

    public void setWorkspacesPath(String workspacesPath) {
        this.workspacesPath = workspacesPath;
    }

    public String getLogPath() {
        return logPath;
    }

    public void setLogPath(String logPath) {
        this.logPath = logPath;
    }

    public String getLogDir() {
        return logDir;
    }

    public void setLogDir(String logDir) {
        this.logDir = logDir;
    }
}
