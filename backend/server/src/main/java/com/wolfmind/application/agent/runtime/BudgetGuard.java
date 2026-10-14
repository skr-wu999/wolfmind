package com.wolfmind.application.agent.runtime;

import com.wolfmind.application.port.TimePort;

/**
 * 回合预算守卫（agent-runtime.md §9、§13）。
 *
 * <p>预算是**降级触发点**而不是异常来源：任何一项超限都让工作流转入兜底，
 * 由 {@code FallbackPlanner} 产出规则 Top-1 动作。因此这里的方法返回布尔值而不抛异常。
 *
 * <p>两条时间约束在 {@link #withinSubmitWindow} 与 {@link #pastSoftDeadline} 里：
 * <ul>
 *   <li>{@code deadline - submitReserveMs} 之前必须完成提交（默认预留 500ms）；</li>
 *   <li>{@code deadline - softDeadlineMs} 到期终止推理（默认预留 1500ms）。</li>
 * </ul>
 *
 * <p><b>时间源必须与 Runtime 的 {@link TimePort} 一致。</b>早期版本直接用
 * {@code System.currentTimeMillis()}，于是"受控时钟"的测试与真实对局会被判成完全不同的状态：
 * 测试的时钟锚点在 1.73e12 附近，与系统时钟差了几十天，所有 deadline 都被判成"早已过期"。
 * 因此这里优先使用注入的 {@link TimePort}。
 */
public final class BudgetGuard {

    private final TimePort clock;
    private final long nowOverride;

    /** 生产用：时间由 Runtime 的 {@link TimePort} 提供。 */
    public BudgetGuard(TimePort clock) {
        this.clock = clock;
        this.nowOverride = -1L;
    }

    /** 未显式提供时钟时的兜底：用系统时钟（真实部署）。 */
    public BudgetGuard() {
        this(null);
    }

    /** 测试用：固定"当前时刻"，让时间相关断言可复现。 */
    public BudgetGuard(long fixedNowMillis) {
        this.clock = null;
        this.nowOverride = fixedNowMillis;
    }

    private long now() {
        if (nowOverride >= 0) {
            return nowOverride;
        }
        return clock == null ? System.currentTimeMillis() : clock.nowMillis();
    }

    /** 是否还能再调用一次 LLM（含修复轮）。 */
    public boolean tryConsumeLlmCall(AgentWorkflow.FlowContext state) {
        return state.llmCalls() < state.request().budget().maxLlmCalls();
    }

    /** 是否还能再调用一次工具。 */
    public boolean tryConsumeToolCall(int usedToolCalls, int maxToolCalls) {
        return usedToolCalls < maxToolCalls;
    }

    /** 是否已过"允许推理"的软截止；过期后应直接进入兜底，不再起模型调用。 */
    public boolean pastSoftDeadline(AgentWorkflow.FlowContext state) {
        long softDeadline = state.request().budget().softDeadlineAt(state.request().deadline());
        return now() >= softDeadline;
    }

    /** 是否仍在"允许提交"的窗口内（提交预留之前）。 */
    public boolean withinSubmitWindow(AgentWorkflow.FlowContext state) {
        long submitDeadline = state.request().budget().submitDeadlineAt(state.request().deadline());
        return now() < submitDeadline;
    }

    /** 综合判断：是否应当立即进入兜底。 */
    public boolean shouldFallbackNow(AgentWorkflow.FlowContext state) {
        return pastSoftDeadline(state) || !tryConsumeLlmCall(state);
    }
}
