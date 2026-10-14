package com.wolfmind.infrastructure.agent;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 拒绝码的唯一来源（tool-gateway.md §4/§5、api-spec.md §1.2）。
 *
 * <p>为什么需要一个常量类：拒绝码同时出现在三处——网关的结构化拒绝、审计的 {@code denialCode}、
 * 策略层 repair 的逻辑分支。任何一处写字符串字面量，都会让"网关返回 TOOL_ARGS_INVALID、
 * 策略层判断 TOOL_ARG_INVALID"这种拼写差异变成只在生产日志里才发现的缺陷。
 *
 * <p><b>文档缺口（已向 Lead 报告）</b>：{@code TOOL_UNKNOWN} / {@code TOOL_ARGS_INVALID} /
 * {@code TOOL_IDENTITY_MISMATCH} / {@code BUDGET_EXCEEDED} 只定义在 tool-gateway.md §4，
 * api-spec.md §1.2 的错误码表里没有它们。§5 要求"code 与 REST 错误码同一套枚举"，
 * 因此这四个码应当补进 §1.2（属共享契约，本任务不改文档）；在补齐之前，Agent 侧的取值
 * 以本类为准，其余码（{@code GAME_ALREADY_FINISHED} / {@code ACTION_NOT_ALLOWED} /
 * {@code TURN_NOT_YOURS} / {@code ACTION_INVALID_TARGET} / {@code GAME_BUSY} /
 * {@code GAME_VERSION_CONFLICT}）与 §1.2 逐字一致。
 */
public final class DenialCodes {

    // G1：工具名不在注册表白名单内
    public static final String TOOL_UNKNOWN = "TOOL_UNKNOWN";
    // G2：参数 Schema（类型、枚举、范围、长度、未知字段）
    public static final String TOOL_ARGS_INVALID = "TOOL_ARGS_INVALID";
    // G3：身份绑定（gameId / playerId / role / seatNo 与视图不一致，或参数里夹带身份字段）
    public static final String TOOL_IDENTITY_MISMATCH = "TOOL_IDENTITY_MISMATCH";
    // G4：对局不在 RUNNING
    public static final String GAME_ALREADY_FINISHED = "GAME_ALREADY_FINISHED";
    // G5 / G7 / G8 / G10：阶段、角色、次数、资源不允许
    public static final String ACTION_NOT_ALLOWED = "ACTION_NOT_ALLOWED";
    // G6：需要回合窗口的阶段不是本人回合
    public static final String TURN_NOT_YOURS = "TURN_NOT_YOURS";
    // G9：目标非法（死亡、自身、队友、不在候选集内）
    public static final String ACTION_INVALID_TARGET = "ACTION_INVALID_TARGET";
    // G11：回合预算（次数与时长）
    public static final String BUDGET_EXCEEDED = "BUDGET_EXCEEDED";

    // 应用层/领域侧原样透传的码（api-spec.md §1.2）
    public static final String GAME_BUSY = "GAME_BUSY";
    public static final String GAME_VERSION_CONFLICT = "GAME_VERSION_CONFLICT";
    public static final String SEAT_NOT_YOURS = "SEAT_NOT_YOURS";
    public static final String GAME_RECOVERING = "GAME_RECOVERING";
    public static final String SERVICE_DEGRADED = "SERVICE_DEGRADED";

    /** 可原样重试的码（tool-gateway.md §5）：其余码必须换动作、换目标或停止重试。 */
    private static final Set<String> RETRYABLE = Set.of(
            GAME_BUSY, GAME_VERSION_CONFLICT, GAME_RECOVERING, SERVICE_DEGRADED);

    /**
     * 领域拒绝码 → 工具拒绝码（tool-gateway.md §9：网关做"AI 侧前置门"，领域做最终判定）。
     *
     * <p>大部分同名透传；只有语义需要归一化的才改写，且改写必须有明确理由：
     * <ul>
     *   <li>{@code VALIDATION_ERROR} / {@code SPEECH_INVALID} → {@code TOOL_ARGS_INVALID}：
     *       参数形态问题在工具侧就是同一个码，模型只需要一套修法；</li>
     *   <li>{@code SKILL_UNAVAILABLE} → {@code ACTION_NOT_ALLOWED}：对应 G10 资源门；</li>
     *   <li>{@code ACTION_ALREADY_SUBMITTED} → {@code ACTION_NOT_ALLOWED}：对应 G8 次数门
     *       "本阶段已提交"（§4 明确该门的码是 {@code ACTION_NOT_ALLOWED} 或幂等返回）；</li>
     *   <li>{@code GAME_NOT_RUNNING} → {@code GAME_ALREADY_FINISHED}：G4 的码就是后者，
     *       同一个"别再做动作了"的语义不应该因为是谁先发现而变成两个码。</li>
     * </ul>
     * 未登记的码原样透传：宁可让一个陌生码进 Trace 被人看见，也不要静默改写。
     */
    // 用 Map.ofEntries 而不是 Map.of：Map.of 的定长重载最多 10 对，
    // 超过后会因为没有变参重载而编译失败（javac 不会退回到 ofEntries）。
    private static final Map<String, String> FROM_DOMAIN = Map.ofEntries(
            Map.entry("GAME_BUSY", GAME_BUSY),
            Map.entry("GAME_VERSION_CONFLICT", GAME_VERSION_CONFLICT),
            Map.entry("SEAT_NOT_YOURS", SEAT_NOT_YOURS),
            Map.entry("ACTION_INVALID_TARGET", ACTION_INVALID_TARGET),
            Map.entry("TURN_NOT_YOURS", TURN_NOT_YOURS),
            Map.entry("ACTION_NOT_ALLOWED", ACTION_NOT_ALLOWED),
            Map.entry("VALIDATION_ERROR", TOOL_ARGS_INVALID),
            Map.entry("SPEECH_INVALID", TOOL_ARGS_INVALID),
            Map.entry("SKILL_UNAVAILABLE", ACTION_NOT_ALLOWED),
            Map.entry("ACTION_ALREADY_SUBMITTED", ACTION_NOT_ALLOWED),
            Map.entry("GAME_NOT_RUNNING", GAME_ALREADY_FINISHED),
            Map.entry("GAME_ALREADY_FINISHED", GAME_ALREADY_FINISHED),
            Map.entry("GAME_RECOVERING", GAME_RECOVERING),
            Map.entry("SERVICE_DEGRADED", SERVICE_DEGRADED),
            Map.entry("INTERNAL_ERROR", SERVICE_DEGRADED));

    private DenialCodes() {}

    public static boolean retryable(String code) {
        return code != null && RETRYABLE.contains(code);
    }

    /** 领域拒绝码归一化；未登记的码原样返回（{@code null} 视为 {@code ACTION_NOT_ALLOWED}）。 */
    public static String fromDomainCode(String domainCode) {
        if (domainCode == null || domainCode.isBlank()) {
            return ACTION_NOT_ALLOWED;
        }
        return FROM_DOMAIN.getOrDefault(domainCode, domainCode);
    }

    /** 全部可出现的拒绝码，供契约测试断言"没有未登记的码"。 */
    public static List<String> all() {
        return List.of(
                TOOL_UNKNOWN, TOOL_ARGS_INVALID, TOOL_IDENTITY_MISMATCH, GAME_ALREADY_FINISHED,
                ACTION_NOT_ALLOWED, TURN_NOT_YOURS, ACTION_INVALID_TARGET, BUDGET_EXCEEDED,
                GAME_BUSY, GAME_VERSION_CONFLICT, SEAT_NOT_YOURS, GAME_RECOVERING, SERVICE_DEGRADED);
    }
}
