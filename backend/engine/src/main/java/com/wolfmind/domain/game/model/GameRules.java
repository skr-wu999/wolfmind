package com.wolfmind.domain.game.model;

/**
 * 单局规则参数。
 *
 * <p>规则在开局写入 {@code GAME_STARTED.rules}，重放时使用事件里的规则，不使用服务端当前配置：
 * 实验室跑过的旧对局必须能按当时的规则复现（domain-model.md §3、event-design.md §7.2）。
 *
 * <p>本记录是"领域规则类"配置的唯一载体；运行调优类配置（锁 TTL、心跳间隔等）不进入领域。
 */
public record GameRules(
        int speechMaxLength,
        long turnDeadlineMs,
        long agentTurnDeadlineMs,
        long voteDeadlineMs,
        boolean firstNightLastWords,
        boolean laterNightLastWords,
        boolean exileLastWords,
        boolean dayShotLastWords,
        boolean witchSelfSave,
        boolean witchSelfSaveFirstNightOnly,
        boolean witchSaveTargetMustBeKilled,
        boolean allowWolfEmptyKill,
        WolfKillTieBreak wolfKillTieBreak,
        VoteTieRule voteTieRule,
        boolean hunterCanShootWhenPoisoned,
        WinCondition winCondition,
        boolean abstainAllowed,
        int stalledRoundsLimit) {

    /** 默认规则：与 domain-model.md §3 的默认值表逐项一致。 */
    public static final GameRules DEFAULT = new GameRules(
            500,
            60_000L,
            60_000L,
            45_000L,
            true,
            false,
            true,
            true,
            true,
            true,
            true,
            true,
            WolfKillTieBreak.MIN_SEAT,
            VoteTieRule.NO_EXILE,
            false,
            WinCondition.SIDE_KILL,
            true,
            3);

    public GameRules {
        if (stalledRoundsLimit < 0) {
            throw GameException.Factory.rulesInvalid("stalledRoundsLimit 必须 ≥ 0（0 表示不启用）");
        }
        if (speechMaxLength < 1) {
            throw GameException.Factory.rulesInvalid("speechMaxLength 必须 ≥ 1");
        }
        if (turnDeadlineMs <= 0 || agentTurnDeadlineMs <= 0 || voteDeadlineMs <= 0) {
            throw GameException.Factory.rulesInvalid("时限必须为正数");
        }
        if (wolfKillTieBreak == null || voteTieRule == null || winCondition == null) {
            throw GameException.Factory.rulesInvalid("裁决规则与胜负条件不能为空");
        }
    }

    /** 按控制者类型取回合时限：AI 座位使用 {@code agentTurnDeadlineMs}。 */
    public long turnDeadlineFor(ControllerType controllerType) {
        return controllerType == ControllerType.AI ? agentTurnDeadlineMs : turnDeadlineMs;
    }

    /**
     * 该轮次的夜间死亡是否有遗言。
     *
     * <p>首夜由 {@code firstNightLastWords} 决定，第二夜起由 {@code laterNightLastWords} 决定。
     */
    public boolean nightDeathHasLastWords(int round) {
        return round <= 1 ? firstNightLastWords : laterNightLastWords;
    }

    /**
     * 该轮次的女巫能否对自己使用解药。
     *
     * <p>{@code witchSelfSave=false} 时全程不可自救；为 {@code true} 时再看
     * {@code witchSelfSaveFirstNightOnly}：只允许首夜自救，第二夜起不可。
     */
    public boolean witchCanSelfSave(int round) {
        return witchSelfSave && (!witchSelfSaveFirstNightOnly || round <= 1);
    }
}
