package com.wolfmind.application.agent.model;

import java.util.List;

/**
 * 一个回合的执行结果（agent-runtime.md §5.2）。
 *
 * <p>它只描述"这一回合做了什么"，**不修改任何状态**：状态变化只能由工具网关提交后的领域事件
 * 产生（C-4）。因此 {@code submittedEventId} / {@code version} 是"提交成功后回填的事实"，
 * 而不是"Runtime 想让状态变成什么"。
 *
 * @param status          结果状态
 * @param gameId          对局
 * @param playerId        这个回合属于哪个座位
 * @param submittedAction 已由工具网关提交成功时的动作摘要；未提交时为 {@code null}
 * @param submittedEventId 提交成功后的领域事件 id 列表
 * @param version         提交成功后的对局版本
 * @param candidateSet    策略层给出的候选与打分（分析与调试用）
 * @param chosen          最终选择的动作
 * @param toolCalls       本回合工具调用明细
 * @param llmCalls        本回合 LLM 调用次数
 * @param promptTokens    提示 token 数
 * @param completionTokens 生成 token 数
 * @param latencyMs       端到端耗时
 * @param fallbackUsed    是否走了规则兜底
 * @param errorCode       失败时的结构化原因；{@code FALLBACK} 时为触发兜底的原因（可能为 {@code null}），
 *                        {@code SUBMITTED} 为 {@code null}
 */
public record TurnResult(
        Status status,
        String gameId,
        String playerId,
        ActionProposal submittedAction,
        List<String> submittedEventId,
        int version,
        List<Candidate> candidateSet,
        ActionProposal chosen,
        List<ToolCallRecord> toolCalls,
        int llmCalls,
        int promptTokens,
        int completionTokens,
        long latencyMs,
        boolean fallbackUsed,
        String errorCode) {

    /** 回合结果状态（agent-runtime.md §5.2）。 */
    public enum Status {
        /** 已由工具网关提交成功。 */
        SUBMITTED,
        /** LLM 不可用或超预算，走了规则兜底，且兜底动作已提交。 */
        FALLBACK,
        /** 连兜底都没能提交（例如截止时刻已过）。此时由应用层超时看护提交 SkipAction。 */
        TIMEOUT,
        /** 不可恢复失败。 */
        FAILED
    }

    /** 一次工具调用的审计记录（tool-gateway.md §7）。 */
    public record ToolCallRecord(
            String tool,
            List<String> argsDigest,
            String decision,
            String denialCode,
            String resultingEventId,
            int version,
            long latencyMs) {

        public ToolCallRecord {
            argsDigest = List.copyOf(argsDigest);
        }

        public static ToolCallRecord allowed(
                String tool, List<String> argsDigest, String resultingEventId, int version, long latencyMs) {
            return new ToolCallRecord(tool, argsDigest, "ALLOWED", null, resultingEventId, version, latencyMs);
        }

        public static ToolCallRecord denied(String tool, List<String> argsDigest, String denialCode) {
            return new ToolCallRecord(tool, argsDigest, "DENIED", denialCode, null, 0, 0L);
        }
    }

    public TurnResult {
        submittedEventId = List.copyOf(submittedEventId);
        candidateSet = List.copyOf(candidateSet);
        toolCalls = List.copyOf(toolCalls);
        if (status == null) {
            throw new IllegalArgumentException("TurnResult.status 不能为空");
        }
    }

    /** 记录一次"验证器认为合规但工具网关拒绝"的回合。 */
    public static TurnResult rejected(
            String gameId,
            String playerId,
            ActionProposal chosen,
            List<Candidate> candidateSet,
            List<ToolCallRecord> toolCalls,
            String errorCode,
            long latencyMs) {
        return new TurnResult(
                Status.FAILED, gameId, playerId, null, List.of(), 0, candidateSet, chosen,
                toolCalls, 0, 0, 0, latencyMs, false, errorCode);
    }

    public boolean submitted() {
        return status == Status.SUBMITTED || status == Status.FALLBACK;
    }
}
