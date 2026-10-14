package com.wolfmind.domain.game.service;

import com.wolfmind.domain.game.model.Action;
import com.wolfmind.domain.game.model.GameException;
import com.wolfmind.domain.game.model.GamePhase;
import com.wolfmind.domain.game.model.GameSnapshot;
import com.wolfmind.domain.game.model.GameStatus;
import com.wolfmind.domain.game.model.HunterShotAction;
import com.wolfmind.domain.game.model.PlayerSnapshot;
import com.wolfmind.domain.game.model.Role;
import com.wolfmind.domain.game.model.SeatNo;
import com.wolfmind.domain.game.model.SeerInspectAction;
import com.wolfmind.domain.game.model.SkillState;
import com.wolfmind.domain.game.model.SkipAction;
import com.wolfmind.domain.game.model.SkipReason;
import com.wolfmind.domain.game.model.Speech;
import com.wolfmind.domain.game.model.SpeechAction;
import com.wolfmind.domain.game.model.Vote;
import com.wolfmind.domain.game.model.VoteAction;
import com.wolfmind.domain.game.model.WitchAction;
import com.wolfmind.domain.game.model.WitchMode;
import com.wolfmind.domain.game.model.WolfChatAction;
import com.wolfmind.domain.game.model.WolfKillAction;

import java.util.Optional;

/**
 * 行动合法性校验（domain-model.md §6.1）。
 *
 * <p>校验顺序固定：先校验"能不能做"（V1–V6），再校验"做得对不对"（V7–V8）。任一条失败都抛
 * {@link GameException}，被拒绝的命令不产生任何事件（INV-11）。
 *
 * <p>纯函数：只读聚合快照，不修改状态、不访问时钟与仓储。
 */
public final class ActionValidator {

    private ActionValidator() {}

    /** 校验并把行动者的 Player 快照返回给调用方，避免阶段处理器重复查找。 */
    public static PlayerSnapshot validate(GameSnapshot state, Action action) {
        // V1 对局必须处于 RUNNING
        validateRunning(state);

        PlayerSnapshot actor = findActor(state, action);

        // V2 行动座位存在且存活（LAST_WORDS 例外：遗言玩家已经死亡）
        validateAlive(state, actor, action);

        // V3 行动类型在当前阶段被接受
        validatePhaseAccepts(state, action);

        // V4 行动者角色具备该能力
        validateRole(state, actor, action);

        // V5 需要回合窗口的阶段，行动者必须是回合持有者
        validateTurn(state, actor, action);

        // V6 本阶段提交次数未超限
        validateNotYetSubmitted(state, actor, action);

        // V7 目标合法
        validateTarget(state, actor, action);

        // V8 资源可用
        validateResources(state, actor, action);

        return actor;
    }

    private static void validateRunning(GameSnapshot state) {
        if (state.status() == GameStatus.FINISHED) {
            throw GameException.Factory.alreadyFinished();
        }
        if (state.status() != GameStatus.RUNNING) {
            throw GameException.Factory.notRunning(state.status().name());
        }
        if (state.phase() == GamePhase.GAME_OVER) {
            throw GameException.Factory.alreadyFinished();
        }
    }

    private static PlayerSnapshot findActor(GameSnapshot state, Action action) {
        return RoleLocator.at(state.players(), action.actor())
                .orElseThrow(() -> GameException.Factory.notAllowed(
                        "座位 " + action.actor() + " 不存在"));
    }

    private static void validateAlive(GameSnapshot state, PlayerSnapshot actor, Action action) {
        // 三个豁免，都源于"行动者按构造就是死者"或"跳过是阶段推进的合法输入"：
        // 1) 遗言阶段：死者唯一的表态机会。发言与跳过都必须放行——domain-model.md §7 把
        //    LAST_WORDS 的 SkipAction 映射为 SPEECH_SKIPPED；若只放行 SpeechAction，
        //    一个卡住的遗言回合将永远无法被超时看护推进（deadlock）。
        // 2) 猎人开枪阶段：猎人是在死亡结算中被转入该阶段的，因此行动者必然已死亡。
        //    豁免必须收紧到 pendingHunter 本人。
        if (state.phase() == GamePhase.LAST_WORDS
                && (action instanceof SpeechAction || action instanceof SkipAction)) {
            return;
        }
        if (state.phase() == GamePhase.HUNTER_SHOOT && isPendingHunter(state, actor, action)) {
            return;
        }
        if (!actor.alive()) {
            throw GameException.Factory.notAllowed(
                    "座位 " + actor.seatNo() + " 已死亡，不再拥有行动权（INV-9）");
        }
    }

    /** 该行动是否来自"等待开枪的猎人"本人（只允许开枪与跳过两种表达）。 */
    private static boolean isPendingHunter(GameSnapshot state, PlayerSnapshot actor, Action action) {
        if (state.pendingHunter() == null || !(action instanceof HunterShotAction || action instanceof SkipAction)) {
            return false;
        }
        return state.pendingHunter().intValue() == actor.seatNo().value();
    }

    private static void validatePhaseAccepts(GameSnapshot state, Action action) {
        GamePhase phase = state.phase();
        boolean accepted = switch (action) {
            case SpeechAction ignored -> phase == GamePhase.DAY_DISCUSS || phase == GamePhase.LAST_WORDS;
            case VoteAction ignored -> phase == GamePhase.DAY_VOTE;
            case WolfKillAction ignored -> phase == GamePhase.NIGHT_WOLF;
            case SeerInspectAction ignored -> phase == GamePhase.NIGHT_SEER;
            case WitchAction ignored -> phase == GamePhase.NIGHT_WITCH;
            case HunterShotAction ignored -> phase == GamePhase.HUNTER_SHOOT;
            case SkipAction ignored -> phase.acceptsActions();
            case WolfChatAction ignored -> phase == GamePhase.NIGHT_WOLF;
        };
        if (!accepted) {
            throw GameException.Factory.notAllowed(
                    "阶段 " + phase + " 不接受 " + action.type() + "（domain-model.md §4）");
        }
    }

    private static void validateRole(GameSnapshot state, PlayerSnapshot actor, Action action) {
        Role required = switch (action) {
            case WolfKillAction ignored -> Role.WEREWOLF;
            case WolfChatAction ignored -> Role.WEREWOLF;
            case SeerInspectAction ignored -> Role.SEER;
            case WitchAction ignored -> Role.WITCH;
            case HunterShotAction ignored -> Role.HUNTER;
            default -> null;
        };
        if (required != null && actor.role() != required) {
            throw GameException.Factory.notAllowed(
                    "座位 " + actor.seatNo() + " 的角色不能执行 " + action.type());
        }
    }

    private static void validateTurn(GameSnapshot state, PlayerSnapshot actor, Action action) {
        // DAY_VOTE 与 NIGHT_WOLF 是同时提交、无顺序的阶段，不依赖 currentTurn
        if (!state.phase().grantsTurn()) {
            return;
        }
        if (state.currentTurn() == null) {
            throw GameException.Factory.turnNotYours("当前阶段尚未发放回合");
        }
        if (!state.currentTurn().playerId().equals(actor.playerId())) {
            throw GameException.Factory.turnNotYours(
                    "当前回合属于座位 " + state.currentTurn().seatNo().value()
                            + "（提交者座位 " + actor.seatNo().value() + "）");
        }
    }

    private static void validateNotYetSubmitted(GameSnapshot state, PlayerSnapshot actor, Action action) {
        int seat = actor.seatNo().value();
        switch (action) {
            case VoteAction ignored -> {
                if (state.votes().containsKey(seat)) {
                    throw GameException.Factory.idempotent("座位 " + seat + " 本阶段已投票（V6）");
                }
            }
            case SkipAction ignored -> {
                if (state.phase() == GamePhase.DAY_VOTE && state.votes().containsKey(seat)) {
                    throw GameException.Factory.idempotent("座位 " + seat + " 本阶段已投票（V6）");
                }
            }
            case WolfKillAction ignored -> {
                if (state.wolfNominations().stream()
                        .anyMatch(nomination -> nomination.wolfSeatNo() == seat)) {
                    throw GameException.Factory.idempotent("座位 " + seat + " 本夜已提名（V6）");
                }
            }
            case SeerInspectAction ignored -> {
                if (state.inspectedTarget() != null) {
                    throw GameException.Factory.idempotent("预言家本夜已查验（V6）");
                }
            }
            case WitchAction ignored -> {
                if (state.witchActed()) {
                    throw GameException.Factory.idempotent("女巫本夜已用药或已跳过（V6）");
                }
            }
            case WolfChatAction ignored -> {
                if (state.wolfChatCount(seat) >= WolfChatAction.MAX_PER_NIGHT) {
                    throw GameException.Factory.notAllowed(
                            "座位 " + seat + " 本夜夜聊已达 " + WolfChatAction.MAX_PER_NIGHT + " 条上限（V6）");
                }
            }
            default -> {
                // 发言、猎人开枪由回合窗口与 currentTurn 保证单次提交
            }
        }
    }

    private static void validateTarget(GameSnapshot state, PlayerSnapshot actor, Action action) {
        switch (action) {
            case SpeechAction speechAction ->
                    speechAction.speech().validateLength(state.rules().speechMaxLength());
            case VoteAction voteAction -> {
                if (voteAction.abstain()) {
                    if (!state.rules().abstainAllowed()) {
                        throw GameException.Factory.notAllowed("本局不允许弃权");
                    }
                    return;
                }
                requireAliveTarget(state, actor, voteAction.target(), "投票目标必须存活（INV-10）");
            }
            case WolfKillAction wolfKillAction -> {
                if (wolfKillAction.isEmptyKill()) {
                    if (!state.rules().allowWolfEmptyKill()) {
                        throw GameException.Factory.notAllowed("本局不允许弃刀");
                    }
                    return;
                }
                SeatNo target = wolfKillAction.target();
                requireAliveTarget(state, actor, target, "狼刀目标必须存活");
                PlayerSnapshot targetPlayer = RoleLocator.at(state.players(), target).orElseThrow();
                if (targetPlayer.isWolf()) {
                    throw GameException.Factory.invalidTarget(
                            "狼刀目标不得为狼人阵营玩家（INV-6）");
                }
            }
            case SeerInspectAction seerInspectAction ->
                    requireAliveTarget(state, actor, seerInspectAction.target(), "查验目标必须存活");
            case WitchAction witchAction -> validateWitchTarget(state, actor, witchAction);
            case HunterShotAction hunterShotAction -> {
                if (hunterShotAction.isGiveUp()) {
                    return; // 放弃开枪总是合法的
                }
                SeatNo target = hunterShotAction.target();
                requireAliveTarget(state, actor, target, "开枪目标必须存活");
            }
            case SkipAction skip -> {
                if (state.phase() == GamePhase.DAY_VOTE && skip.reason() == SkipReason.VOLUNTARY
                        && !state.rules().abstainAllowed()) {
                    throw GameException.Factory.notAllowed("本局不允许主动弃权");
                }
            }
            case WolfChatAction chat -> chat.message().validateLength(WolfChatAction.MAX_LENGTH);
        }
    }

    private static void validateWitchTarget(GameSnapshot state, PlayerSnapshot actor, WitchAction action) {
        if (action.mode() == WitchMode.PASS) {
            return;
        }
        SeatNo target = action.target();
        boolean selfSave = action.mode() == WitchMode.SAVE && actor.seatNo().equals(target);
        if (selfSave) {
            // 自救不走"不能指向自己"的通用校验，是否允许只由规则决定
            if (!state.rules().witchCanSelfSave(state.round())) {
                throw GameException.Factory.notAllowed(state.rules().witchSelfSave()
                        ? "女巫只有首夜可以自救（witchSelfSaveFirstNightOnly=true）"
                        : "本局女巫不可自救（witchSelfSave=false）");
            }
        } else {
            requireAliveTarget(state, actor, target, "用药目标必须存活");
        }

        if (action.mode() == WitchMode.SAVE) {
            if (state.rules().witchSaveTargetMustBeKilled()) {
                Integer killed = state.wolfKillTarget();
                if (killed == null || killed != target.value()) {
                    throw GameException.Factory.invalidTarget(
                            "解药只能救当夜被狼刀的目标（witchSaveTargetMustBeKilled=true）");
                }
            }
        }
    }

    private static void requireAliveTarget(
            GameSnapshot state, PlayerSnapshot actor, SeatNo target, String message) {
        if (target == null) {
            throw GameException.Factory.invalidTarget("目标不能为空");
        }
        if (target.equals(actor.seatNo())) {
            throw GameException.Factory.invalidTarget("不能指向自己");
        }
        PlayerSnapshot targetPlayer = RoleLocator.at(state.players(), target)
                .orElseThrow(() -> GameException.Factory.invalidTarget("座位 " + target + " 不存在"));
        if (!targetPlayer.alive()) {
            throw GameException.Factory.invalidTarget(message);
        }
    }

    private static void validateResources(GameSnapshot state, PlayerSnapshot actor, Action action) {
        SkillState skill = actor.skillState();
        switch (action) {
            case WitchAction witchAction -> {
                if (witchAction.mode() == WitchMode.SAVE && !skill.antidoteAvailable()) {
                    throw GameException.Factory.skillUnavailable("解药已用完（INV-7）");
                }
                if (witchAction.mode() == WitchMode.POISON && !skill.poisonAvailable()) {
                    throw GameException.Factory.skillUnavailable("毒药已用完（INV-7）");
                }
                // INV-7：同一夜不得同时使用解药与毒药
                if (witchAction.mode() == WitchMode.SAVE && state.witchPoisoned() != null) {
                    throw GameException.Factory.notAllowed("女巫同夜不可同时使用两种药（INV-7）");
                }
                if (witchAction.mode() == WitchMode.POISON && state.witchSaved() != null) {
                    throw GameException.Factory.notAllowed("女巫同夜不可同时使用两种药（INV-7）");
                }
            }
            case HunterShotAction hunterShotAction -> {
                if (!hunterShotAction.isGiveUp() && !skill.shotAvailable()) {
                    throw GameException.Factory.skillUnavailable("开枪次数已用尽（INV-8）");
                }
            }
            default -> {
                // 其它行动没有资源限制
            }
        }
    }

    /** 发言内容是否合法（供阶段处理器在构造 Speech 前使用）。 */
    public static void validateSpeechContent(Speech speech, int maxLength) {
        speech.validateLength(maxLength);
    }

    /** 便于诊断：当前阶段是否接受任何行动。 */
    public static boolean acceptsActions(GamePhase phase) {
        return phase.acceptsActions();
    }

    /** 便于测试与调用方构造领域票值对象。 */
    public static Vote normalizeVote(VoteAction action) {
        return Vote.resolve(
                action.actor().value(), action.target() == null ? null : action.target().value());
    }
}
