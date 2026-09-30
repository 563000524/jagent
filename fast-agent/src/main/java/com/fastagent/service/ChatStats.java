package com.fastagent.service;

/**
 * 一轮生成的用量统计。
 *
 * <p>两个来源刻意分开：
 * <ul>
 *   <li>{@code durationMs} 是<b>后端自己测</b>的（从受理到收尾），比模型自报的可靠，
 *       而且涵盖了重试、工具调用等模型不感知的时间；</li>
 *   <li>token 取自 AgentScope 的 {@code ChatUsage}，一轮里可能发生多次模型调用
 *       （工具调用、子 agent 会各算一次），这里按次<b>累加</b>，
 *       所以是「这一轮总共消耗」而不是「最后一次调用消耗」。</li>
 * </ul>
 *
 * <p>模型没回传用量时各字段为 0，界面据此决定要不要显示这一项。
 */
public record ChatStats(long durationMs, int inputTokens, int outputTokens, int totalTokens) {

    public static final ChatStats EMPTY = new ChatStats(0L, 0, 0, 0);

    /** 是否拿到了 token 数据 */
    public boolean hasTokens() {
        return totalTokens > 0 || inputTokens > 0 || outputTokens > 0;
    }

    public ChatStats plus(ChatStats other) {
        if (other == null) {
            return this;
        }
        return new ChatStats(
                Math.max(durationMs, other.durationMs),
                inputTokens + other.inputTokens,
                outputTokens + other.outputTokens,
                totalTokens + other.totalTokens);
    }
}
