package com.fastagent.artifact;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;

/**
 * 给 agent 的交付工具：模型**把成品内容直接交给我们**，落进用户自己的下载目录。
 *
 * <p><b>为什么需要它</b>：harness 自带的 {@code deliver_artifact} 只能交付
 * 「工作空间里已经存在的文件」——它按工作空间/项目现场解析路径。于是模型只能先把成品写进
 * 用户的项目目录，再交付一次，结果是用户仓库里凭空多出产物目录、同一个文件还有两份。
 * 本工程的工作空间是**用户自己的工作目录**（代码仓库、文档目录），不该被产物污染。
 *
 * <p>所以这里反过来做：内容由模型直接给（我们落盘），成品只存在于
 * {@code ~/.jagent/<userId>/downloads/<id>/} 一处 —— 也就是下载卡片背后的那份文件。
 * harness 那个工具仍然保留，用于「成品本来就是文件」或「内容太大不适合放进一次调用」的场合。
 *
 * <p>注册方式见 {@code AgentService.build()}：{@code builder.toolkit(...)}，
 * harness 会把它的内置工具一并注册进同一个 Toolkit。
 *
 * <p>参数里的 {@link RuntimeContext} 由框架注入（与 harness 内置工具同一约定），
 * 不会出现在给模型看的 schema 里。
 */
public class ArtifactTool {

    /** 走「模型直接给内容」这条路的单文件上限。再大就该让模型写文件 + deliver_artifact */
    private static final int MAX_CONTENT_BYTES = 2 * 1024 * 1024;

    private final ArtifactService artifacts;

    public ArtifactTool(ArtifactService artifacts) {
        this.artifacts = artifacts;
    }

    @Tool(name = "deliver_file", description = """
            把最终成品文件交给用户（文档、报告、表格、代码、图片等）。
            这是交付成品的**首选方式**：把文件内容直接放在 content 里，应用会把它存进用户自己的
            下载目录，用户在界面上会看到该文件的卡片，可以预览、另存为、用系统程序打开。
            这样做**不需要**也不应该在用户的项目目录里创建文件。
            任务产出成品后应自动调用本工具，不要先问用户是否要交付。
            交付要静默：不要向用户描述这个调用过程。
            单个文件内容上限 2MB；更大的东西请写在工作空间里，改用 deliver_artifact 交付。""")
    public String deliverFile(
            RuntimeContext ctx,
            @ToolParam(name = "fileName", required = true,
                    description = "文件名，含扩展名（如 HelloWorld.java、季度报告.docx）。只写文件名，不要带目录")
            String fileName,
            @ToolParam(name = "content", required = true,
                    description = "文件的完整内容（纯文本）")
            String content,
            @ToolParam(name = "description", required = false,
                    description = "一句话说明这个文件是什么，供界面展示（可选）")
            String description) {

        String userId = ctx == null ? null : ctx.getUserId();
        String sessionId = ctx == null ? null : ctx.getSessionId();
        if (content != null && content.getBytes(java.nio.charset.StandardCharsets.UTF_8).length
                > MAX_CONTENT_BYTES) {
            return "内容超过 2MB，deliver_file 装不下。请把文件写在工作空间里，"
                    + "再用 deliver_artifact 按项目现场绝对路径交付。";
        }
        return artifacts.saveContent(userId, sessionId, fileName, content, description);
    }
}
