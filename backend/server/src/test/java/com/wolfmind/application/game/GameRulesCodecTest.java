package com.wolfmind.application.game;

import com.wolfmind.domain.game.model.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.math.BigInteger;
import java.util.HashMap;
import java.util.Map;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

class GameRulesCodecTest {
    @Test void missingAndEmptyRulesKeepTheApprovedDefaults() {
        assertEquals(GameRules.DEFAULT, GameRulesCodec.fromRoomConfig(Map.of()));
        assertEquals(GameRules.DEFAULT, GameRulesCodec.fromRoomConfig(Map.of("rules", Map.of())));
        assertEquals(GameRules.DEFAULT, GameRulesCodec.fromRoomConfig(null));
    }

    @Test void partialOverrideKeepsUnspecifiedFieldsAndAcceptsJsonLongNumbers() {
        var rules = GameRulesCodec.fromRoomConfig(Map.of("rules", Map.of("voteDeadlineMs", 15_000L,
                "abstainAllowed", false, "speechMaxLength", 200)));
        assertEquals(15_000, rules.voteDeadlineMs());
        assertFalse(rules.abstainAllowed());
        assertEquals(200, rules.speechMaxLength());
        assertEquals(GameRules.DEFAULT.turnDeadlineMs(), rules.turnDeadlineMs());
        assertEquals(GameRules.DEFAULT.witchSelfSaveFirstNightOnly(), rules.witchSelfSaveFirstNightOnly());
    }

    @Test void everySupportedRuleCanBeOverridden() {
        var values = new HashMap<String, Object>();
        values.put("speechMaxLength", 123); values.put("turnDeadlineMs", 20_000); values.put("agentTurnDeadlineMs", 25_000);
        values.put("voteDeadlineMs", 15_000); values.put("firstNightLastWords", false); values.put("laterNightLastWords", true);
        values.put("exileLastWords", false); values.put("dayShotLastWords", false); values.put("witchSelfSave", false);
        values.put("witchSelfSaveFirstNightOnly", false); values.put("witchSaveTargetMustBeKilled", false);
        values.put("allowWolfEmptyKill", false); values.put("wolfKillTieBreak", "MAX_SEAT"); values.put("voteTieRule", "NO_EXILE");
        values.put("hunterCanShootWhenPoisoned", true); values.put("winCondition", "WOLVES_OUTNUMBER");
        values.put("abstainAllowed", false); values.put("stalledRoundsLimit", 0);
        assertEquals(new GameRules(123, 20_000, 25_000, 15_000, false, true, false, false, false,
                false, false, false, WolfKillTieBreak.MAX_SEAT, VoteTieRule.NO_EXILE, true,
                WinCondition.WOLVES_OUTNUMBER, false, 0), GameRulesCodec.fromRoomConfig(Map.of("rules", values)));
    }

    static Stream<Map<String, Object>> invalidRules() {
        var nullField = new HashMap<String, Object>(); nullField.put("abstainAllowed", null);
        return Stream.of(Map.of("unknownRule", true), Map.of("voteDeadlineMs", 0), Map.of("agentTurnDeadlineMs", -1),
                Map.of("turnDeadlineMs", "15000"), Map.of("speechMaxLength", 1.5), Map.of("abstainAllowed", "false"),
                Map.of("speechMaxLength", 0), Map.of("speechMaxLength", 2_147_483_648L), Map.of("stalledRoundsLimit", -1),
                Map.of("voteDeadlineMs", new BigInteger("9223372036854775808")), Map.of("voteDeadlineMs", Double.NaN),
                Map.of("voteTieRule", "PK"), Map.of("wolfKillTieBreak", "RANDOM"), Map.of("winCondition", "side_kill"),
                Map.of("winCondition", 1), nullField);
    }

    @ParameterizedTest @MethodSource("invalidRules")
    void invalidOverridesAreRejectedWithTheFieldName(Map<String, Object> fields) {
        var error = assertThrows(GameException.class, () -> GameRulesCodec.fromRoomConfig(Map.of("rules", fields)));
        assertEquals(DomainErrorCode.RULES_INVALID, error.code());
        assertTrue(error.getMessage().contains(fields.keySet().iterator().next()));
    }

    @Test void explicitNullAndNonObjectRulesAreRejected() {
        var nullConfig = new HashMap<String, Object>(); nullConfig.put("rules", null);
        for (Map<String, Object> config : java.util.List.<Map<String, Object>>of(nullConfig, Map.of("rules", "DEFAULT"))) {
            assertEquals(DomainErrorCode.RULES_INVALID,
                    assertThrows(GameException.class, () -> GameRulesCodec.fromRoomConfig(config)).code());
        }
    }
}
