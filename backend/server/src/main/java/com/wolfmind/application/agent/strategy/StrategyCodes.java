package com.wolfmind.application.agent.strategy;

import java.util.Locale;
import java.util.Set;

/**
 * 策略层的字符串词汇表（strategy-layer.md §5、§7.1、§12）。
 *
 * <p>角色、阶段、阵营在这里只以字符串存在：Agent 侧与领域之间没有编译期依赖（DEP-7、C-5），
 * 一旦引入领域枚举，"AI 只认角色可见视图"这条边界就失效了。代价是拼写风险，因此本类是
 * 整个策略层唯一的字面量集散地——其它类不重复写字面量，避免同一个阶段名出现两种拼写，
 * 导致候选集与工具网关白名单对不上。
 *
 * <p>动作类型与 api-spec.md §5.3 的 {@code actionType} 列逐字一致，它们同时是
 * {@code Candidate.actionType} 与 {@code ActionProposal.actionType} 的取值域。
 */
public final class StrategyCodes {

    private StrategyCodes() {}

    /** 动作类型（api-spec.md §5.3）。 */
    public static final String SPEAK = "SPEAK";
    public static final String VOTE = "VOTE";
    public static final String WOLF_KILL = "WOLF_KILL";
    public static final String SEER_INSPECT = "SEER_INSPECT";
    public static final String WITCH_SAVE = "WITCH_SAVE";
    public static final String WITCH_POISON = "WITCH_POISON";
    public static final String WITCH_PASS = "WITCH_PASS";
    public static final String HUNTER_SHOOT = "HUNTER_SHOOT";
    public static final String SKIP = "SKIP";

    /** 阶段名（domain-model.md §4 的阶段表，这里只作为字符串使用）。 */
    public static final String NIGHT_WOLF = "NIGHT_WOLF";
    public static final String NIGHT_SEER = "NIGHT_SEER";
    public static final String NIGHT_WITCH = "NIGHT_WITCH";
    public static final String NIGHT_SETTLE = "NIGHT_SETTLE";
    public static final String DAY_ANNOUNCE = "DAY_ANNOUNCE";
    public static final String LAST_WORDS = "LAST_WORDS";
    public static final String DAY_DISCUSS = "DAY_DISCUSS";
    public static final String DAY_VOTE = "DAY_VOTE";
    public static final String VOTE_SETTLE = "VOTE_SETTLE";
    public static final String HUNTER_SHOOT_PHASE = "HUNTER_SHOOT";
    public static final String GAME_OVER = "GAME_OVER";

    /** 角色名（与领域 Role 枚举同名，但只是字符串）。 */
    public static final String WEREWOLF = "WEREWOLF";
    public static final String SEER = "SEER";
    public static final String WITCH = "WITCH";
    public static final String HUNTER = "HUNTER";
    public static final String VILLAGER = "VILLAGER";

    /** 需要产出非空发言的阶段（§7.1：不需要发言的阶段 speech 可为空）。 */
    public static final Set<String> SPEECH_PHASES = Set.of(DAY_DISCUSS, LAST_WORDS);

    /** 需要玩家提交动作的阶段：这些阶段里 SKIP 是合法动作（api-spec.md §5.3）。 */
    public static final Set<String> SUBMIT_PHASES = Set.of(
            DAY_DISCUSS, LAST_WORDS, DAY_VOTE, NIGHT_WOLF, NIGHT_SEER, NIGHT_WITCH, HUNTER_SHOOT_PHASE);

    /** {@code Candidate.breakdown} 与 {@code DecisionPolicy} 共用的因子名（§4.3）。 */
    public static final String FACTOR_TARGET = "targetScore";
    public static final String FACTOR_CONSISTENCY = "consistencyScore";
    public static final String FACTOR_EXPOSURE = "exposureRisk";
    public static final String FACTOR_INFO_GAIN = "infoGainScore";
    public static final String FACTOR_PRESSURE = "pressureScore";

    /** 校验失败码（strategy-layer.md §8.1 的每一行都对应一个码，供 repair prompt 与 Trace 使用）。 */
    public static final String FAIL_BAD_JSON = "BAD_JSON";
    public static final String FAIL_ACTION_NOT_ALLOWED = "ACTION_NOT_ALLOWED";
    public static final String FAIL_PHASE_ROLE_MISMATCH = "PHASE_ROLE_MISMATCH";
    public static final String FAIL_TARGET_NOT_IN_CANDIDATES = "TARGET_NOT_IN_CANDIDATES";
    public static final String FAIL_TARGET_INVALID = "TARGET_INVALID";
    public static final String FAIL_SPEECH_LENGTH = "SPEECH_LENGTH";

    /** 防泄漏违规类别（{@code StrategyPort.LeakCheckResult.violationKind}）。 */
    public static final String LEAK_JUDGE_FACT = "JUDGE_FACT";
    public static final String LEAK_PRIVATE_FACT = "PRIVATE_FACT";

    /** 理由码：非法取值一律回落到这个默认值，不触发修复（§8.1 最后一行）。 */
    public static final String REASON_UNSPECIFIED = "UNSPECIFIED";

    /** 兜底理由码：候选集最高分（§9 第 1 条）。 */
    public static final String REASON_FALLBACK_TOP1 = "FALLBACK_TOP1";

    /** 兜底理由码：候选集为空，只能 SKIP（§9 第 2 条）。 */
    public static final String REASON_FALLBACK_EMPTY_CANDIDATES = "FALLBACK_EMPTY_CANDIDATES";

    /** 安全模板发言的理由码：重写仍违规后的最后手段（§8.2 末段）。 */
    public static final String REASON_SAFE_TEMPLATE_SPEECH = "SAFE_TEMPLATE_SPEECH";

    /**
     * 预定义理由码枚举（§7.1：{@code reasonCode} 必须在这个集合内，用于复盘与统计）。
     *
     * <p>它是"统计维度"而不是"约束"：非法取值只回落到 {@link #REASON_UNSPECIFIED}，
     * 不会让一回合的决策作废——理由码缺失不影响动作合法性。
     */
    public static final Set<String> REASON_CODES = Set.of(
            REASON_UNSPECIFIED,
            "HIGH_THREAT_SEER_SUSPECT",
            "HIGH_THREAT_INFO_LEADER",
            "HIGH_THREAT_INFLUENCER",
            "SAFE_KILL_TARGET",
            "PROTECT_TEAMMATE",
            "AVOID_EXPOSURE",
            "EMPTY_KILL",
            "MAX_SUSPICION",
            "SEER_TRUTH_CONTRADICTION",
            "HOSTILE_VOTE_PATTERN",
            "EVASIVE_SPEECH",
            "WOLF_LINK_SUSPECT",
            "TIMELINE_SHIFT",
            "ABSTAIN_NO_CONFIDENCE",
            "VOTE_PRESSURE",
            "INFORMATION_GAIN",
            "UNINSPECTED_FIRST",
            "WITCH_SAVE_NIGHT_VICTIM",
            "WITCH_POISON_SUSPECT",
            "WITCH_PASS_NO_TARGET",
            "HUNTER_GIVE_UP",
            "HUNTER_SHOOT_SUSPECT",
            REASON_SAFE_TEMPLATE_SPEECH,
            REASON_FALLBACK_TOP1,
            REASON_FALLBACK_EMPTY_CANDIDATES);

    /** 理由码是否在预定义枚举内；大小写与首尾空白不计，避免模型写法差异造成误判。 */
    public static boolean isKnownReasonCode(String code) {
        if (code == null || code.isBlank()) {
            return false;
        }
        return REASON_CODES.contains(code.strip().toUpperCase(Locale.ROOT));
    }

    /** 规范化理由码：非法取值回落到默认值而不是拒绝整条输出（§8.1）。 */
    public static String normalizeReasonCode(String code) {
        if (!isKnownReasonCode(code)) {
            return REASON_UNSPECIFIED;
        }
        return code.strip().toUpperCase(Locale.ROOT);
    }

    /** 动作类型与座位/阶段的规范化：所有比较都走这里，避免"speak"与"SPEAK"被当成两个动作。 */
    public static String normalize(String value) {
        return value == null ? "" : value.strip().toUpperCase(Locale.ROOT);
    }
}
