package com.wolfmind.infrastructure.agent;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * G2 参数 Schema 校验（tool-gateway.md §4 G2、§12"参数注入"）：
 * 非法枚举、超范围整数、超长发言、未知字段、互斥与必填组。
 */
class ToolArgsValidatorTest {

    private static final ToolArgsValidator.Limits LIMITS =
            new ToolArgsValidator.Limits(500, 100, true);

    @Test
    @DisplayName("非法枚举：scope 只认 SELF/PUBLIC/MY_CAMP，GOD 被拒绝")
    void illegalScopeEnumIsRejected() {
        assertTrue(ToolArgsValidator
                .validate(ToolCatalog.QUERY_GAME_STATE, Map.of("scope", "SELF"), null, LIMITS).ok());
        assertTrue(ToolArgsValidator
                .validate(ToolCatalog.QUERY_GAME_STATE, Map.of("scope", "MY_CAMP"), null, LIMITS).ok());

        ToolArgsValidator.Outcome god = ToolArgsValidator.validate(
                ToolCatalog.QUERY_GAME_STATE, Map.of("scope", "GOD"), null, LIMITS);
        assertEquals(ToolArgsValidator.Finding.ARGS_INVALID, god.finding(), "不存在 GOD scope（§8）");
        assertEquals(DenialCodes.TOOL_ARGS_INVALID, god.code());

        ToolArgsValidator.Outcome wrongCase = ToolArgsValidator.validate(
                ToolCatalog.QUERY_GAME_STATE, Map.of("scope", "public"), null, LIMITS);
        assertEquals(ToolArgsValidator.Finding.ARGS_INVALID, wrongCase.finding(),
                "枚举大小写敏感，不做兜底猜测");
    }

    @Test
    @DisplayName("必填与互斥：发言必须二选一，skip 与 content 不可同时出现")
    void speakGroupsAreEnforced() {
        assertTrue(ToolArgsValidator.validate(
                ToolCatalog.SPEAK, Map.of("actionType", "SPEAK", "content", "你好"), "SPEAK", LIMITS).ok());
        assertTrue(ToolArgsValidator.validate(
                ToolCatalog.SPEAK, Map.of("actionType", "SKIP", "skip", true, "reason", "VOLUNTARY"),
                "SKIP", LIMITS).ok());

        Map<String, Object> both = Map.of(
                "actionType", "SPEAK", "content", "你好", "skip", true, "reason", "VOLUNTARY");
        assertEquals(ToolArgsValidator.Finding.ARGS_INVALID,
                ToolArgsValidator.validate(ToolCatalog.SPEAK, both, "SPEAK", LIMITS).finding(),
                "content 与 skip 互斥");

        assertEquals(ToolArgsValidator.Finding.ARGS_INVALID,
                ToolArgsValidator.validate(
                        ToolCatalog.SPEAK, Map.of("actionType", "SPEAK"), "SPEAK", LIMITS).finding(),
                "既没有 content 也没有 skip");
    }

    @Test
    @DisplayName("整数范围：座位号必须落在 1..9，小数、字符串、越界都拒绝")
    void integerRanges() {
        assertTrue(ToolArgsValidator.validate(
                ToolCatalog.VOTE, Map.of("targetSeatNo", 9), "VOTE", LIMITS).ok());

        for (Object bad : new Object[] {0, 10, -1, 1.5, "5", true}) {
            Map<String, Object> args = new LinkedHashMap<>();
            args.put("targetSeatNo", bad);
            assertEquals(ToolArgsValidator.Finding.ARGS_INVALID,
                    ToolArgsValidator.validate(ToolCatalog.VOTE, args, "VOTE", LIMITS).finding(),
                    "座位号 " + bad + " 应被拒绝");
        }
    }

    @Test
    @DisplayName("长度上限：发言受本局 speechMaxLength 约束，超长直接拒绝")
    void speechLengthFollowsGameRules() {
        ToolArgsValidator.Limits shortLimit = new ToolArgsValidator.Limits(10, 100, true);
        assertTrue(ToolArgsValidator.validate(
                ToolCatalog.SPEAK,
                Map.of("actionType", "SPEAK", "content", "1234567890"), "SPEAK", shortLimit).ok());

        ToolArgsValidator.Outcome tooLong = ToolArgsValidator.validate(
                ToolCatalog.SPEAK,
                Map.of("actionType", "SPEAK", "content", "12345678901"), "SPEAK", shortLimit);
        assertEquals(ToolArgsValidator.Finding.ARGS_INVALID, tooLong.finding());
        assertTrue(tooLong.message().contains("11"), "拒绝原因要能指导修复：" + tooLong.message());

        assertTrue(ToolArgsValidator.validate(
                ToolCatalog.SPEAK,
                Map.of("actionType", "SPEAK", "content", "x".repeat(500)), "SPEAK", LIMITS).ok(),
                "500 字在上限内");
    }

    @Test
    @DisplayName("历史查询：limit 上限来自 gateway.queryHistory.maxLimit（默认 100）")
    void historyLimitIsCapped() {
        assertTrue(ToolArgsValidator.validate(
                ToolCatalog.QUERY_HISTORY, Map.of("limit", 100), null, LIMITS).ok());

        ToolArgsValidator.Outcome tooMuch = ToolArgsValidator.validate(
                ToolCatalog.QUERY_HISTORY, Map.of("limit", 101), null, LIMITS);
        assertEquals(ToolArgsValidator.Finding.ARGS_INVALID, tooMuch.finding());
        assertEquals(DenialCodes.TOOL_ARGS_INVALID, tooMuch.code());

        assertEquals(ToolArgsValidator.Finding.ARGS_INVALID,
                ToolArgsValidator.validate(ToolCatalog.QUERY_HISTORY, Map.of("limit", 0), null, LIMITS).finding());
        assertTrue(ToolArgsValidator.validate(
                ToolCatalog.QUERY_HISTORY,
                Map.of("fromVersion", 1, "toVersion", 12, "limit", 50), null, LIMITS).ok());
    }

    @Test
    @DisplayName("未知字段：默认直接拒绝；关闭 denyUnknownArgs 时忽略")
    void unknownFields() {
        ToolArgsValidator.Outcome unknown = ToolArgsValidator.validate(
                ToolCatalog.QUERY_GAME_STATE, Map.of("scope", "SELF", "unexpected", "x"), null, LIMITS);
        assertEquals(ToolArgsValidator.Finding.ARGS_INVALID, unknown.finding());
        assertTrue(unknown.message().contains("unexpected"));

        ToolArgsValidator.Limits lenient = new ToolArgsValidator.Limits(500, 100, false);
        assertTrue(ToolArgsValidator.validate(
                ToolCatalog.QUERY_GAME_STATE, Map.of("scope", "SELF", "unexpected", "x"), null, lenient).ok(),
                "gateway.denyUnknownArgs=false 时未知字段被忽略");
    }

    @Test
    @DisplayName("身份字段不是未知字段：按身份注入拒绝（TOOL_IDENTITY_MISMATCH）")
    void identityKeysAreInjectionNotUnknownField() {
        for (String key : new String[] {"playerId", "gameId", "role", "seatNo", "actorId"}) {
            Map<String, Object> args = new LinkedHashMap<>();
            args.put("scope", "SELF");
            args.put(key, "p_9");
            ToolArgsValidator.Outcome outcome =
                    ToolArgsValidator.validate(ToolCatalog.QUERY_GAME_STATE, args, null, LIMITS);
            assertEquals(ToolArgsValidator.Finding.IDENTITY_INJECTION, outcome.finding(),
                    key + " 必须按身份注入处理");
            assertEquals(DenialCodes.TOOL_IDENTITY_MISMATCH, outcome.code());
            assertTrue(outcome.injectedKeys().contains(key));
        }
    }

    @Test
    @DisplayName("技能参数：必须给目标 / 必须无目标 / 枚举白名单")
    void skillShapeFollowsMatrix() {
        assertEquals(ToolArgsValidator.Finding.ARGS_INVALID,
                ToolArgsValidator.validate(ToolCatalog.SKILL, Map.of("skill", "SEER_INSPECT"), "SEER_INSPECT", LIMITS)
                        .finding(),
                "查验必须给出目标");
        assertTrue(ToolArgsValidator.validate(
                ToolCatalog.SKILL, Map.of("skill", "SEER_INSPECT", "targetSeatNo", 5), "SEER_INSPECT", LIMITS).ok());
        assertTrue(ToolArgsValidator.validate(
                ToolCatalog.SKILL, Map.of("skill", "WITCH_PASS"), "WITCH_PASS", LIMITS).ok());
        assertEquals(ToolArgsValidator.Finding.ARGS_INVALID,
                ToolArgsValidator.validate(
                        ToolCatalog.SKILL, Map.of("skill", "WITCH_PASS", "targetSeatNo", 5), "WITCH_PASS", LIMITS)
                        .finding(),
                "不用药不得携带目标（ActionCodec 也会拒绝）");
        assertEquals(ToolArgsValidator.Finding.ARGS_INVALID,
                ToolArgsValidator.validate(ToolCatalog.SKILL, Map.of("skill", "MAGIC"), "MAGIC", LIMITS).finding(),
                "未知技能名");
        assertEquals(ToolArgsValidator.Finding.ARGS_INVALID,
                ToolArgsValidator.validate(ToolCatalog.SKILL, Map.of(), null, LIMITS).finding(),
                "缺少 skill");
    }

    @Test
    @DisplayName("目标策略：投票与狼刀允许无目标（弃权/空刀），但必须二选一")
    void optionalTargetsNeedExplicitForm() {
        assertTrue(ToolArgsValidator.validate(
                ToolCatalog.VOTE, Map.of("abstain", true), "VOTE", LIMITS).ok());
        assertTrue(ToolArgsValidator.validate(
                ToolCatalog.VOTE, Map.of("targetSeatNo", 3), "VOTE", LIMITS).ok());
        assertEquals(ToolArgsValidator.Finding.ARGS_INVALID,
                ToolArgsValidator.validate(ToolCatalog.VOTE, Map.of(), "VOTE", LIMITS).finding(),
                "既不给目标也不弃权");
        assertEquals(ToolArgsValidator.Finding.ARGS_INVALID,
                ToolArgsValidator.validate(
                        ToolCatalog.VOTE, Map.of("abstain", true, "targetSeatNo", 3), "VOTE", LIMITS).finding(),
                "弃权与目标互斥");

        assertTrue(ToolArgsValidator.validate(
                ToolCatalog.KILL, Map.of("empty", true), "WOLF_KILL", LIMITS).ok());
        assertFalse(ToolArgsValidator.validate(
                ToolCatalog.KILL, Map.of("empty", true, "targetSeatNo", 3), "WOLF_KILL", LIMITS).ok());
    }
}
