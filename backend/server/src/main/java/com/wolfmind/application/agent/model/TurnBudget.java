package com.wolfmind.application.agent.model;

import com.wolfmind.application.agent.llm.LlmClient;

/**
 * 单回合预算（agent-runtime.md §9、§13）。
 *
 * <p>预算耗尽不是异常而是降级触发点：{@code BudgetGuard} 直接进入兜底，
 * 由 {@code FallbackPlanner} 产出规则 Top-1 动作。
 *
 * @param maxLlmCalls      每回合 LLM 调用上限（含修复轮），原生工具默认 4，JSON 默认 2
 * @param maxToolCalls     每回合工具调用上限（查询与提交合计），默认 6
 * @param maxTokens        上下文总预算，默认 8000
 * @param softDeadlineMs   "终止推理进入兜底"的剩余毫秒数，默认 1500
 * @param submitReserveMs  "必须完成提交"的剩余毫秒数，默认 500
 */
public record TurnBudget(
        int maxLlmCalls,
        int maxToolCalls,
        int maxTokens,
        long softDeadlineMs,
        long submitReserveMs) {

    /** agent-runtime.md §13 的默认值。 */
    public static final TurnBudget DEFAULT = new TurnBudget(2, 6, 8000, 1500L, 500L);
    public static final TurnBudget FUNCTION_CALLING = DEFAULT.withMaxLlmCalls(4);

    /** 创建使用模型的座位时选择默认预算；显式座位预算由调用方直接保留。 */
    public static TurnBudget defaultFor(LlmClient.InteractionMode mode) {
        return switch (java.util.Objects.requireNonNull(mode, "mode")) {
            case FUNCTION_CALLING -> FUNCTION_CALLING;
            case JSON_PROPOSAL -> DEFAULT;
        };
    }

    public TurnBudget {
        if (maxLlmCalls < 0) {
            throw new IllegalArgumentException("maxLlmCalls 不能为负");
        }
        if (maxToolCalls < 0) {
            throw new IllegalArgumentException("maxToolCalls 不能为负");
        }
        if (maxTokens < 1) {
            throw new IllegalArgumentException("maxTokens 必须 ≥ 1");
        }
        if (softDeadlineMs < 0 || submitReserveMs < 0) {
            throw new IllegalArgumentException("预留时间不能为负");
        }
        if (submitReserveMs > softDeadlineMs) {
            // 提交预留必须小于"终止推理"的预留，否则会出现"已经该提交了但还在推理"
            throw new IllegalArgumentException("submitReserveMs 不能大于 softDeadlineMs");
        }
    }

    /**
     * 由回合 deadline 推导软截止时刻。
     *
     * <p>{@code softDeadlineMs} 是"相对 deadline 的预留"，因此这里返回的是绝对时刻：
     * {@code deadline - softDeadlineMs}。到期终止图执行并转入兜底。
     */
    public long softDeadlineAt(long turnDeadline) {
        return turnDeadline - softDeadlineMs;
    }

    /** 必须完成提交的绝对时刻：{@code deadline - submitReserveMs}。 */
    public long submitDeadlineAt(long turnDeadline) {
        return turnDeadline - submitReserveMs;
    }

    public TurnBudget withMaxLlmCalls(int value) {
        return new TurnBudget(value, maxToolCalls, maxTokens, softDeadlineMs, submitReserveMs);
    }

    public TurnBudget withMaxToolCalls(int value) {
        return new TurnBudget(maxLlmCalls, value, maxTokens, softDeadlineMs, submitReserveMs);
    }
}
