package com.fastagent.agent;

import io.agentscope.harness.agent.tools.McpServerConfig;
import io.agentscope.harness.agent.tools.ToolsConfig;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * {@code tool/tools.json} 的文件结构。
 *
 * <p>不直接用 AgentScope 的 {@link ToolsConfig} 做落盘对象：它在空集合上有自己的
 * {@code @JsonInclude} 策略，会把 {@code allow: []} / {@code mcpServers: {}} 直接省略，
 * 存一次文件就「缩水」成只剩非空字段，和播种出来的样子对不上，用户手改时容易迷惑。
 *
 * <p>这里自己定结构，既保证键始终在文件里，也把文件格式与 AgentScope 的类解耦 ——
 * 将来它改字段名，我们的配置文件不受影响。另外 {@code shellEnabled} 是本应用
 * 自己的开关（AgentScope 没有这个概念，它只有「调不调 disableShellTool」）。
 */
public class ToolsFile {

    private List<String> allow = new ArrayList<>();
    private List<String> deny = new ArrayList<>();
    private Map<String, McpServerConfig> mcpServers = new LinkedHashMap<>();

    /**
     * 是否允许 agent 执行本机 shell 命令。
     *
     * <p><b>默认为否</b>：agent 能执行任意本机命令，对桌面应用来说这个默认值太激进。
     * 但确实有用（让 agent 自己删误建的文件、跑构建脚本、确认自己所在目录），
     * 所以在界面上留了开关。
     */
    private boolean shellEnabled = false;

    public ToolsConfig toToolsConfig() {
        ToolsConfig c = new ToolsConfig();
        c.setAllow(allow == null ? new ArrayList<>() : new ArrayList<>(allow));
        c.setDeny(deny == null ? new ArrayList<>() : new ArrayList<>(deny));
        c.setMcpServers(mcpServers == null ? new LinkedHashMap<>() : new LinkedHashMap<>(mcpServers));
        return c;
    }

    public List<String> getAllow() {
        return allow;
    }

    public void setAllow(List<String> allow) {
        this.allow = allow;
    }

    public List<String> getDeny() {
        return deny;
    }

    public void setDeny(List<String> deny) {
        this.deny = deny;
    }

    public Map<String, McpServerConfig> getMcpServers() {
        return mcpServers;
    }

    public void setMcpServers(Map<String, McpServerConfig> mcpServers) {
        this.mcpServers = mcpServers;
    }

    public boolean isShellEnabled() {
        return shellEnabled;
    }

    public void setShellEnabled(boolean shellEnabled) {
        this.shellEnabled = shellEnabled;
    }
}
