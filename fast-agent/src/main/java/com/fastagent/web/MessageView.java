package com.fastagent.web;

import com.fastagent.AppLog;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.ThinkingBlock;
import io.agentscope.core.model.ChatUsage;

import java.util.List;

/**
 * 返回给前端的一条消息。
 *
 * <p>除「谁说的 + 说了什么」，还带上时间、用量与<b>思考过程</b>（界面在回答下方显示一行小字，
 * 思考过程单独折叠）。这些都取自 AgentScope 的 {@link Msg}，不另存一份。
 *
 * <p>各项都做了缺失兜底：老数据、或模型服务不回传用量时取空串 / 0，
 * 由界面决定不显示，而不是抛异常。
 *
 * @param reasoning    思考过程。来自消息里保留的 {@link ThinkingBlock} ——
 *                     {@code getTextContent()} 只拼文本块，推理内容必须单独取，
 *                     否则历史会话里回看就只剩答案、看不到思考。
 *                     非推理模型、或没开 {@code enable_thinking} 时为空串。
 * @param timestamp    时间串，来自 {@code Msg.getTimestamp()}
 * @param inputTokens  输入 token，0 表示模型没回传
 * @param outputTokens 输出 token
 * @param totalTokens  总 token
 * @param durationMs   模型自报耗时（毫秒）。{@code ChatUsage.time} 的单位是<b>秒</b>
 *                     （实测一次带 3 万输入 token 的调用约 3.5），这里换算成毫秒
 */
public record MessageView(String role, String content, String reasoning,
                          String timestamp,
                          int inputTokens, int outputTokens, int totalTokens,
                          long durationMs) {

    public static MessageView of(Msg m) {
        if (m == null) {
            return null;
        }
        String text = m.getTextContent();
        ChatUsage usage = null;
        try {
            usage = m.getUsage();
        } catch (Exception e) {
            // 用量只是展示项，拿不到不该影响消息本身
            AppLog.warn("读取消息用量失败: %s", e.getMessage());
        }
        return new MessageView(
                normalizeRole(m.getRole()),
                text == null ? "" : text,
                reasoningOf(m),
                m.getTimestamp() == null ? "" : m.getTimestamp(),
                usage == null ? 0 : usage.getInputTokens(),
                usage == null ? 0 : usage.getOutputTokens(),
                usage == null ? 0 : usage.getTotalTokens(),
                usage == null ? 0L : Math.round(usage.getTime() * 1000));
    }

    /**
     * 取出消息里的推理内容。
     *
     * <p>AgentScope 会把模型的 {@code reasoning_content} 作为 {@link ThinkingBlock} 留在
     * assistant 消息里（发给模型时会被跳过，但对我们是可读的），所以历史回看能还原思考过程，
     * 不必在流式期间另存一份。
     */
    private static String reasoningOf(Msg m) {
        List<ThinkingBlock> blocks;
        try {
            blocks = m.getContentBlocks(ThinkingBlock.class);
        } catch (Exception e) {
            return "";
        }
        if (blocks == null || blocks.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (ThinkingBlock b : blocks) {
            String t = b == null ? null : b.getThinking();
            if (t == null || t.isBlank()) {
                continue;
            }
            if (!sb.isEmpty()) {
                sb.append("\n\n");
            }
            sb.append(t);
        }
        return sb.toString();
    }

    /**
     * 把紧随其后的一条消息并入本条（只对同角色调用）。
     *
     * <p>一轮对话在 AgentScope 的上下文里通常是「多条 ASSISTANT + 中间的 TOOL」：
     * 模型输出一段 → 调工具 → 再输出一段。若逐条渲染，界面上就会出现
     * 「一条回答被拆成两个气泡」。这里合并成一条，与流式渲染的口径一致
     * （流式本来就是把所有增量拼成一条）。
     *
     * <p>时间取最早的一条，用量与耗时累加。
     */
    public MessageView merge(MessageView next) {
        if (next == null) {
            return this;
        }
        String joined;
        if (content == null || content.isBlank()) {
            joined = next.content();
        } else if (next.content() == null || next.content().isBlank()) {
            joined = content;
        } else {
            joined = content + "\n\n" + next.content();
        }
        String joinedReasoning;
        if (reasoning == null || reasoning.isBlank()) {
            joinedReasoning = next.reasoning();
        } else if (next.reasoning() == null || next.reasoning().isBlank()) {
            joinedReasoning = reasoning;
        } else {
            joinedReasoning = reasoning + "\n\n" + next.reasoning();
        }
        String ts = (timestamp == null || timestamp.isBlank()) ? next.timestamp() : timestamp;
        return new MessageView(role, joined, joinedReasoning, ts,
                inputTokens + next.inputTokens(),
                outputTokens + next.outputTokens(),
                totalTokens + next.totalTokens(),
                durationMs + next.durationMs());
    }

    private static String normalizeRole(MsgRole role) {
        if (role == null) {
            return "assistant";
        }
        return switch (role) {
            case USER -> "user";
            case ASSISTANT -> "assistant";
            case SYSTEM -> "system";
            default -> role.name().toLowerCase();
        };
    }
}
