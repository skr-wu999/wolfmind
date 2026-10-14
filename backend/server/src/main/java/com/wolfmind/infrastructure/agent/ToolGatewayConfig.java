package com.wolfmind.infrastructure.agent;

import java.util.Map;

/**
 * 工具网关配置（tool-gateway.md §11 的六项）。
 *
 * <p>为什么把六项放在一个记录里：这六个数被两处独立读取（网关自身的门，以及接线层的
 * Agent 预算/幂等存储），分散成六个字段名会让"网关说 6 次、Runtime 说 8 次"这种不一致
 * 只能靠人工比对发现。
 *
 * <p>{@code idempotencyTtlMinutes} 在本类里只是"同一个数字"，实际过期由幂等存储实现
 * （内存版 {@code InMemoryIdempotencyPort}、Redis 版 {@code idem:{gameId}:{key}} 的 TTL）。
 * 网关不自己维护过期时间：那样会出现"事件还在、幂等记录已过期"的第二份真相。
 *
 * @param maxToolCallsPerTurn   每回合工具调用上限（查询与提交合计），与 Agent 预算同源
 * @param queryHistoryMaxLimit  {@code QueryHistoryTool} 的单次上限
 * @param idempotencyTtlMinutes 幂等键保留分钟数（交给幂等存储生效）
 * @param versionRetry          版本冲突重试次数（G13）
 * @param auditEnabled          审计开关：关闭后不写 {@code AGENT_TOOL_CALLED}
 * @param denyUnknownArgs       未知工具参数直接拒绝；关闭时按"忽略未知字段"处理
 */
public record ToolGatewayConfig(
        int maxToolCallsPerTurn,
        int queryHistoryMaxLimit,
        int idempotencyTtlMinutes,
        int versionRetry,
        boolean auditEnabled,
        boolean denyUnknownArgs) {

    /** tool-gateway.md §11 的默认值。 */
    public static final ToolGatewayConfig DEFAULT = new ToolGatewayConfig(6, 100, 10, 2, true, true);

    /** 发言长度上限的兜底值：视图不可用时 G2 仍要有一个明确的上限，而不是"不校验"。 */
    public static final int DEFAULT_SPEECH_MAX_LENGTH = 500;

    public ToolGatewayConfig {
        if (maxToolCallsPerTurn < 1) {
            throw new IllegalArgumentException("gateway.maxToolCallsPerTurn 必须 ≥ 1");
        }
        if (queryHistoryMaxLimit < 1) {
            throw new IllegalArgumentException("gateway.queryHistory.maxLimit 必须 ≥ 1");
        }
        if (idempotencyTtlMinutes < 1) {
            throw new IllegalArgumentException("gateway.idempotency.ttlMinutes 必须 ≥ 1");
        }
        if (versionRetry < 0) {
            throw new IllegalArgumentException("gateway.versionRetry 不能为负");
        }
    }

    /**
     * 从配置表构造（键名与 §11 逐字一致），供接线层从 properties/环境变量装配。
     *
     * <p>缺失的键取默认值；类型不符直接失败，而不是静默回退到默认值——配错一个数就当没配，
     * 是"预算看起来生效了但实际不是那个数"的常见成因。
     */
    public static ToolGatewayConfig from(Map<String, Object> properties) {
        Map<String, Object> source = properties == null ? Map.of() : properties;
        return new ToolGatewayConfig(
                intOf(source, "gateway.maxToolCallsPerTurn", DEFAULT.maxToolCallsPerTurn()),
                intOf(source, "gateway.queryHistory.maxLimit", DEFAULT.queryHistoryMaxLimit()),
                intOf(source, "gateway.idempotency.ttlMinutes", DEFAULT.idempotencyTtlMinutes()),
                intOf(source, "gateway.versionRetry", DEFAULT.versionRetry()),
                boolOf(source, "gateway.audit.enabled", DEFAULT.auditEnabled()),
                boolOf(source, "gateway.denyUnknownArgs", DEFAULT.denyUnknownArgs()));
    }

    public ToolGatewayConfig withAuditEnabled(boolean value) {
        return new ToolGatewayConfig(
                maxToolCallsPerTurn, queryHistoryMaxLimit, idempotencyTtlMinutes,
                versionRetry, value, denyUnknownArgs);
    }

    public ToolGatewayConfig withDenyUnknownArgs(boolean value) {
        return new ToolGatewayConfig(
                maxToolCallsPerTurn, queryHistoryMaxLimit, idempotencyTtlMinutes,
                versionRetry, auditEnabled, value);
    }

    public ToolGatewayConfig withVersionRetry(int value) {
        return new ToolGatewayConfig(
                maxToolCallsPerTurn, queryHistoryMaxLimit, idempotencyTtlMinutes,
                value, auditEnabled, denyUnknownArgs);
    }

    public ToolGatewayConfig withMaxToolCallsPerTurn(int value) {
        return new ToolGatewayConfig(
                value, queryHistoryMaxLimit, idempotencyTtlMinutes,
                versionRetry, auditEnabled, denyUnknownArgs);
    }

    private static int intOf(Map<String, Object> source, String key, int fallback) {
        Object raw = source.get(key);
        if (raw == null) {
            return fallback;
        }
        if (raw instanceof Number number) {
            return number.intValue();
        }
        if (raw instanceof String text && !text.isBlank()) {
            try {
                return Integer.parseInt(text.strip());
            } catch (NumberFormatException ex) {
                throw new IllegalArgumentException(key + " 必须是整数，实际为 " + text);
            }
        }
        throw new IllegalArgumentException(key + " 必须是整数，实际为 " + raw);
    }

    private static boolean boolOf(Map<String, Object> source, String key, boolean fallback) {
        Object raw = source.get(key);
        if (raw == null) {
            return fallback;
        }
        if (raw instanceof Boolean flag) {
            return flag;
        }
        if (raw instanceof String text && !text.isBlank()) {
            return Boolean.parseBoolean(text.strip());
        }
        throw new IllegalArgumentException(key + " 必须是布尔值，实际为 " + raw);
    }
}
