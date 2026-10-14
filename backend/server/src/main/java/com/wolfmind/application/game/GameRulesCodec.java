package com.wolfmind.application.game;

import com.wolfmind.domain.game.model.GameException;
import com.wolfmind.domain.game.model.GameRules;
import com.wolfmind.domain.game.model.VoteTieRule;
import com.wolfmind.domain.game.model.WinCondition;
import com.wolfmind.domain.game.model.WolfKillTieBreak;

import java.math.BigDecimal;
import java.util.Map;
import java.util.Set;

/** Decodes optional room overrides; persisted game events keep their own replay decoder. */
public final class GameRulesCodec {
    private static final Set<String> FIELDS = Set.of("speechMaxLength", "turnDeadlineMs", "agentTurnDeadlineMs",
            "voteDeadlineMs", "firstNightLastWords", "laterNightLastWords", "exileLastWords", "dayShotLastWords",
            "witchSelfSave", "witchSelfSaveFirstNightOnly", "witchSaveTargetMustBeKilled", "allowWolfEmptyKill",
            "wolfKillTieBreak", "voteTieRule", "hunterCanShootWhenPoisoned", "winCondition", "abstainAllowed",
            "stalledRoundsLimit");

    private GameRulesCodec() {}

    public static GameRules fromRoomConfig(Map<String, Object> config) {
        if (config == null || !config.containsKey("rules")) return GameRules.DEFAULT;
        if (!(config.get("rules") instanceof Map<?, ?> rules)) {
            throw GameException.Factory.rulesInvalid("config.rules 必须是对象");
        }
        for (Object key : rules.keySet()) {
            if (!(key instanceof String field) || !FIELDS.contains(field)) {
                throw GameException.Factory.rulesInvalid("config.rules 不支持字段：" + key);
            }
        }
        var defaults = GameRules.DEFAULT;
        return new GameRules(
                integer(rules, "speechMaxLength", defaults.speechMaxLength(), 1),
                duration(rules, "turnDeadlineMs", defaults.turnDeadlineMs()),
                duration(rules, "agentTurnDeadlineMs", defaults.agentTurnDeadlineMs()),
                duration(rules, "voteDeadlineMs", defaults.voteDeadlineMs()),
                flag(rules, "firstNightLastWords", defaults.firstNightLastWords()),
                flag(rules, "laterNightLastWords", defaults.laterNightLastWords()),
                flag(rules, "exileLastWords", defaults.exileLastWords()),
                flag(rules, "dayShotLastWords", defaults.dayShotLastWords()),
                flag(rules, "witchSelfSave", defaults.witchSelfSave()),
                flag(rules, "witchSelfSaveFirstNightOnly", defaults.witchSelfSaveFirstNightOnly()),
                flag(rules, "witchSaveTargetMustBeKilled", defaults.witchSaveTargetMustBeKilled()),
                flag(rules, "allowWolfEmptyKill", defaults.allowWolfEmptyKill()),
                choice(rules, "wolfKillTieBreak", defaults.wolfKillTieBreak(), WolfKillTieBreak.class),
                choice(rules, "voteTieRule", defaults.voteTieRule(), VoteTieRule.class),
                flag(rules, "hunterCanShootWhenPoisoned", defaults.hunterCanShootWhenPoisoned()),
                choice(rules, "winCondition", defaults.winCondition(), WinCondition.class),
                flag(rules, "abstainAllowed", defaults.abstainAllowed()),
                integer(rules, "stalledRoundsLimit", defaults.stalledRoundsLimit(), 0));
    }

    private static int integer(Map<?, ?> rules, String key, int fallback, int minimum) {
        long value = wholeNumber(rules, key, fallback);
        if (value < minimum || value > Integer.MAX_VALUE) throw invalid(key, "必须是 " + minimum + "..2147483647 的整数");
        return (int) value;
    }

    private static long duration(Map<?, ?> rules, String key, long fallback) {
        long value = wholeNumber(rules, key, fallback);
        if (value <= 0) throw invalid(key, "必须是正整数毫秒数");
        return value;
    }

    private static long wholeNumber(Map<?, ?> rules, String key, long fallback) {
        if (!rules.containsKey(key)) return fallback;
        if (!(rules.get(key) instanceof Number value)) throw invalid(key, "必须是整数");
        try { return new BigDecimal(value.toString()).longValueExact(); }
        catch (NumberFormatException | ArithmeticException error) { throw invalid(key, "必须是有效的 64 位整数"); }
    }

    private static boolean flag(Map<?, ?> rules, String key, boolean fallback) {
        if (!rules.containsKey(key)) return fallback;
        if (!(rules.get(key) instanceof Boolean value)) throw invalid(key, "必须是布尔值");
        return value;
    }

    private static <E extends Enum<E>> E choice(Map<?, ?> rules, String key, E fallback, Class<E> type) {
        if (!rules.containsKey(key)) return fallback;
        if (!(rules.get(key) instanceof String value)) throw invalid(key, "必须是枚举字符串");
        try { return Enum.valueOf(type, value); }
        catch (IllegalArgumentException error) { throw invalid(key, "不支持该选项，允许值：" + java.util.Arrays.toString(type.getEnumConstants())); }
    }

    private static GameException invalid(String key, String message) {
        return GameException.Factory.rulesInvalid("config.rules." + key + "：" + message);
    }
}
