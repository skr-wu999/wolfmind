package com.wolfmind.infrastructure.agent;

import com.wolfmind.application.agent.trace.AgentTracePort;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 审计记录器（tool-gateway.md §7）：每次工具调用写一条 {@code AGENT_TOOL_CALLED}。
 *
 * <p>两条硬约束：
 * <ol>
 *   <li><b>只存参数摘要</b>：目标座位、技能名、发言长度——不存发言全文。
 *       "AI 看到了什么、做了什么"是分析策略质量的输入，而发言全文已经在对局事件里，
 *       再存一份只会让审计表变成隐藏信息的第二个泄漏面（§7 最后一句）；</li>
 *   <li><b>不写对局事件流</b>：审计走 {@link AgentTracePort}（{@code game:{id}:agent:{playerId}}），
 *       不改变对局状态（C-4、C-5）。归档到 SQLite 时由归档器标成 {@code DECISION_TRACE}。</li>
 * </ol>
 *
 * <p>只读工具同样记录：不记录的话，"模型为什么在第 3 轮误判"这类问题只能靠猜（§7）。
 */
public final class ToolAuditRecorder {

    /** 决策结论。 */
    public enum Decision {
        ALLOWED,
        DENIED
    }

    /**
     * 一条审计记录。
     *
     * @param argsDigest       参数摘要（目标、技能名、发言长度），不含发言全文
     * @param denialCode       拒绝码；成功时为 {@code null}
     * @param denialMessage    拒绝原因的可读说明；成功时为 {@code null}。
     *                         只记拒绝码不够定位问题——同一个 {@code BUDGET_EXCEEDED} 可能来自
     *                         "回合截止已过"、"本回合调用达上限"或"会话余额为 0"，三者修法完全不同。
     *                        值本身不含敏感信息（只有上限、时刻与计数）。
     * @param resultingEventId 成功时的首个领域事件 id；只读工具与拒绝为 {@code null}
     * @param replayed         是否为幂等命中（返回首次结果，本次没有新事件）
     */
    public record AuditEntry(
            String gameId,
            String playerId,
            String tool,
            String argsDigest,
            Decision decision,
            String denialCode,
            String denialMessage,
            String resultingEventId,
            int version,
            boolean replayed,
            long latencyMs,
            String phase,
            int round,
            int turnIndex,
            String traceId,
            long occurredAt) {

        /** 轨迹 payload：字段与 §7 的表格一一对应。 */
        public Map<String, Object> payload() {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("tool", tool);
            payload.put("argsDigest", argsDigest);
            payload.put("decision", decision.name());
            if (denialCode != null) {
                payload.put("denialCode", denialCode);
            }
            if (denialMessage != null) {
                payload.put("denialMessage", denialMessage);
            }
            if (resultingEventId != null) {
                payload.put("resultingEventId", resultingEventId);
            }
            payload.put("version", version);
            payload.put("replayed", replayed);
            payload.put("latencyMs", latencyMs);
            payload.put("phase", phase);
            payload.put("round", round);
            payload.put("turnIndex", turnIndex);
            payload.put("traceId", traceId);
            return payload;
        }
    }

    private final AgentTracePort trace;
    private final ToolGatewayConfig config;

    /**
     * @param trace 轨迹端口；{@code null} 表示该部署没有轨迹存储（此时不写审计）
     */
    public ToolAuditRecorder(AgentTracePort trace, ToolGatewayConfig config) {
        this.trace = trace;
        this.config = config == null ? ToolGatewayConfig.DEFAULT : config;
    }

    /** 审计是否生效（配置开关 + 端口存在）。 */
    public boolean enabled() {
        return config.auditEnabled() && trace != null;
    }

    public void record(AuditEntry entry) {
        if (!enabled() || entry == null) {
            return;
        }
        trace.record(new AgentTracePort.AgentTraceEvent(
                AgentTracePort.TraceEventType.AGENT_TOOL_CALLED,
                entry.gameId(),
                entry.playerId(),
                entry.version(),
                entry.phase(),
                entry.round(),
                entry.occurredAt(),
                entry.payload()));
    }
}
