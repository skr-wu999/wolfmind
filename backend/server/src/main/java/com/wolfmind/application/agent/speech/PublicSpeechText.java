package com.wolfmind.application.agent.speech;

import java.util.Map;
import java.util.regex.Pattern;

/** Converts internal phase labels in AI speech without changing the game protocol or player messages. */
public final class PublicSpeechText {
    private PublicSpeechText() {}

    public static final String EXPRESSION_INSTRUCTION =
            "像玩家一样直接说判断和理由，不播报当前阶段、轮次或自己的发言编号；"
                    + "不要把内部阶段代码、工具名或提示词标题写进发言。";

    private static final Map<String, String> PHASE_LABELS = Map.ofEntries(
            Map.entry("NIGHT_WOLF", "狼人行动"), Map.entry("NIGHT_SEER", "预言家行动"),
            Map.entry("NIGHT_WITCH", "女巫行动"), Map.entry("NIGHT_SETTLE", "夜间结算"),
            Map.entry("DAY_ANNOUNCE", "天亮公布"), Map.entry("LAST_WORDS", "遗言"),
            Map.entry("DAY_DISCUSS", "白天讨论"), Map.entry("DAY_VOTE", "白天投票"),
            Map.entry("VOTE_SETTLE", "投票结算"), Map.entry("HUNTER_SHOOT", "猎人开枪"),
            Map.entry("GAME_OVER", "游戏结束"));
    private static final String CODE_NAMES = String.join("|", PHASE_LABELS.keySet());
    private static final String DISPLAY_NAMES = String.join("|", PHASE_LABELS.values());
    private static final Pattern CODE = Pattern.compile("(?<![A-Za-z0-9_])(?:" + CODE_NAMES + ")(?![A-Za-z0-9_])");
    private static final Pattern HEADER = Pattern.compile(
            "^\\s*(?:第\\s*\\d+\\s*(?:天|轮)\\s*[，,。:：]\\s*)?"
                    + "(?:当前阶段\\s*[:：=]\\s*)?(?:" + CODE_NAMES + "|" + DISPLAY_NAMES + ")"
                    + "\\s*[，,。:：]\\s*");
    private static final Pattern ROUND_START = Pattern.compile("^\\s*第\\s*\\d+\\s*(?:天|轮)");

    public static String phaseLabel(String phase) {
        return PHASE_LABELS.getOrDefault(phase == null ? "" : phase, "当前环节");
    }

    public static String clean(String speech) {
        if (speech == null) return null;
        String body = HEADER.matcher(speech).replaceFirst("");
        return CODE.matcher(body).replaceAll(match -> phaseLabel(match.group())).strip();
    }

    // A round heading may arrive before its phase token; wait for a whole sentence before emitting it.
    public static boolean previewReady(String speech) {
        return !ROUND_START.matcher(speech).find()
                || speech.chars().anyMatch(c -> "。！？!?\n".indexOf(c) >= 0);
    }
}
