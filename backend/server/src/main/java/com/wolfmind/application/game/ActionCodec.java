package com.wolfmind.application.game;

import com.wolfmind.domain.game.model.Action;
import com.wolfmind.domain.game.model.ActionType;
import com.wolfmind.domain.game.model.DomainErrorCode;
import com.wolfmind.domain.game.model.GameException;
import com.wolfmind.domain.game.model.HunterShotAction;
import com.wolfmind.domain.game.model.SeatNo;
import com.wolfmind.domain.game.model.SeerInspectAction;
import com.wolfmind.domain.game.model.SkipAction;
import com.wolfmind.domain.game.model.SkipReason;
import com.wolfmind.domain.game.model.SpeechAction;
import com.wolfmind.domain.game.model.VoteAction;
import com.wolfmind.domain.game.model.WitchAction;
import com.wolfmind.domain.game.model.WolfChatAction;
import com.wolfmind.domain.game.model.WolfKillAction;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * 行动编解码：接口契约 {@code actionType + params} 与领域 {@link Action} 之间的唯一转换点
 * （api-spec.md §5.3、ddd-design.md §6.1 第 5 步）。
 *
 * <p>它只做"请求形态"校验：枚举名、参数是否存在、类型是否正确、座位号是否落在 {@link SeatNo} 的取值域内。
 * 是否允许该行动（角色、阶段、回合、目标是否存活/是否自身）一律由领域判定（C-3、ddd-design.md §3.4）：
 * 同一个 {@code actionType} 对不同角色、不同阶段的合法性是游戏规则，本类不得出现任何分支去判断它。
 *
 * <p>越界的座位号在这里被拦下（{@code VALIDATION_ERROR}），因为 {@link SeatNo} 无法构造出该值；
 * "座位存在但目标非法"（已死亡、指向自身、本局没有该座位）仍由领域以 {@code ACTION_INVALID_TARGET} 表达。
 *
 * <p>提交座位（{@code actorSeat}）来自应用层对 {@code actorId} 与座位 {@code controller.refId} 的匹配，
 * 不是请求参数：客户端声明的座位号不是授权证据（AGENTS.md §8）。
 */
public final class ActionCodec {

    /** {@code params} 中座位号参数的键名，与 api-spec.md §5.3 表格逐字一致。 */
    public static final String PARAM_TARGET_SEAT_NO = "targetSeatNo";
    /** 投票弃权标记。 */
    public static final String PARAM_ABSTAIN = "abstain";
    /** 狼人空刀标记。 */
    public static final String PARAM_EMPTY = "empty";
    /** 猎人放弃开枪标记。 */
    public static final String PARAM_GIVE_UP = "giveUp";
    /** 发言内容。 */
    public static final String PARAM_CONTENT = "content";
    /** 跳过原因。 */
    public static final String PARAM_REASON = "reason";

    /** 解析外部 {@code actionType} 字符串；未知取值必须显式失败，不做大小写或别名的兜底猜测。 */
    public ActionType parseType(String rawActionType) {
        if (rawActionType == null || rawActionType.isBlank()) {
            throw validation("actionType", "不能为空");
        }
        try {
            return ActionType.valueOf(rawActionType.strip());
        } catch (IllegalArgumentException ex) {
            throw validation("actionType", "未知取值 " + rawActionType + "，允许值见 api-spec.md §5.3");
        }
    }

    /**
     * 还原领域行动。
     *
     * @param type      已解析的行动类型
     * @param params    请求参数；缺省或显式 {@code null} 值都按"未提供"处理
     * @param actorSeat 提交座位，由应用层按 {@code actorId} 匹配座位控制者得到
     */
    public Action decode(ActionType type, Map<String, Object> params, SeatNo actorSeat) {
        Objects.requireNonNull(type, "type");
        if (actorSeat == null) {
            throw validation("actorSeat", "缺少提交座位");
        }
        Map<String, Object> safe = normalize(params);
        return switch (type) {
            case SPEAK -> SpeechAction.of(actorSeat.value(), text(safe, PARAM_CONTENT));
            case VOTE -> decodeVote(safe, actorSeat);
            case WOLF_KILL -> decodeWolfKill(safe, actorSeat);
            case SEER_INSPECT -> SeerInspectAction.of(actorSeat.value(), seat(safe, PARAM_TARGET_SEAT_NO));
            case WITCH_SAVE -> WitchAction.save(actorSeat.value(), seat(safe, PARAM_TARGET_SEAT_NO));
            case WITCH_POISON -> WitchAction.poison(actorSeat.value(), seat(safe, PARAM_TARGET_SEAT_NO));
            case WITCH_PASS -> decodeWitchPass(safe, actorSeat);
            case HUNTER_SHOOT -> decodeHunterShot(safe, actorSeat);
            case SKIP -> SkipAction.of(actorSeat.value(), skipReason(safe));
            case WOLF_CHAT -> WolfChatAction.of(actorSeat.value(), text(safe, PARAM_CONTENT));
        };
    }

    /**
     * 反查：{@link Action} 值对象对应的 {@link ActionType}。
     *
     * <p>它以密封接口的具体类型为唯一依据，不读 {@link Action#type()}，因此可以用它交叉校验
     * "领域行动的自身类型"与"接口契约类型"是否一致（例如 {@code WitchAction} 的三种模式）。
     */
    public ActionType typeOf(Action action) {
        if (action == null) {
            throw validation("action", "不能为空");
        }
        return switch (action) {
            case SpeechAction ignored -> ActionType.SPEAK;
            case VoteAction ignored -> ActionType.VOTE;
            case WolfKillAction ignored -> ActionType.WOLF_KILL;
            case SeerInspectAction ignored -> ActionType.SEER_INSPECT;
            case WitchAction witch -> switch (witch.mode()) {
                case SAVE -> ActionType.WITCH_SAVE;
                case POISON -> ActionType.WITCH_POISON;
                case PASS -> ActionType.WITCH_PASS;
            };
            case HunterShotAction ignored -> ActionType.HUNTER_SHOOT;
            case SkipAction ignored -> ActionType.SKIP;
            case WolfChatAction ignored -> ActionType.WOLF_CHAT;
        };
    }

    private Action decodeVote(Map<String, Object> params, SeatNo actorSeat) {
        if (flag(params, PARAM_ABSTAIN, false)) {
            reject(params, PARAM_TARGET_SEAT_NO, "弃权票不得携带 " + PARAM_TARGET_SEAT_NO);
            return VoteAction.abstain(actorSeat.value());
        }
        return VoteAction.to(actorSeat.value(), seat(params, PARAM_TARGET_SEAT_NO));
    }

    private Action decodeWolfKill(Map<String, Object> params, SeatNo actorSeat) {
        if (flag(params, PARAM_EMPTY, false)) {
            reject(params, PARAM_TARGET_SEAT_NO, "空刀不得携带 " + PARAM_TARGET_SEAT_NO);
            return WolfKillAction.empty(actorSeat.value());
        }
        return WolfKillAction.at(actorSeat.value(), seat(params, PARAM_TARGET_SEAT_NO));
    }

    private Action decodeWitchPass(Map<String, Object> params, SeatNo actorSeat) {
        reject(params, PARAM_TARGET_SEAT_NO, "不用药的提交不得携带 " + PARAM_TARGET_SEAT_NO);
        return WitchAction.pass(actorSeat.value());
    }

    private Action decodeHunterShot(Map<String, Object> params, SeatNo actorSeat) {
        if (flag(params, PARAM_GIVE_UP, false)) {
            reject(params, PARAM_TARGET_SEAT_NO, "放弃开枪不得携带 " + PARAM_TARGET_SEAT_NO);
            return HunterShotAction.giveUp(actorSeat.value());
        }
        return HunterShotAction.at(actorSeat.value(), seat(params, PARAM_TARGET_SEAT_NO));
    }

    /**
     * 跳过原因缺省为主动跳过：{@code SKIP} 的语义就是"本轮我不做事"，
     * 超时兜底必须由超时看护显式写 {@code TIMEOUT} 才能进入事件，避免两者被混为一谈。
     */
    private SkipReason skipReason(Map<String, Object> params) {
        Object raw = params.get(PARAM_REASON);
        if (raw == null) {
            return SkipReason.VOLUNTARY;
        }
        if (!(raw instanceof String text)) {
            throw validation(PARAM_REASON, "必须是字符串");
        }
        try {
            return SkipReason.valueOf(text.strip());
        } catch (IllegalArgumentException ex) {
            throw validation(PARAM_REASON, "未知取值 " + text + "，允许值：TIMEOUT / VOLUNTARY");
        }
    }

    private String text(Map<String, Object> params, String key) {
        Object raw = params.get(key);
        if (!(raw instanceof String value) || value.isBlank()) {
            throw validation(key, "必须是非空字符串");
        }
        return value;
    }

    private int seat(Map<String, Object> params, String key) {
        Object raw = params.get(key);
        if (raw == null) {
            throw validation(key, "缺少必填参数");
        }
        if (!(raw instanceof Number number)) {
            throw validation(key, "必须是整数座位号");
        }
        double asDouble = number.doubleValue();
        if (asDouble != Math.rint(asDouble)) {
            throw validation(key, "座位号必须是整数，实际为 " + raw);
        }
        long value = number.longValue();
        if (value < 1 || value > SeatNo.MAX) {
            throw validation(key, "座位号必须在 1.." + SeatNo.MAX + " 之间，实际为 " + value);
        }
        return (int) value;
    }

    private boolean flag(Map<String, Object> params, String key, boolean defaultValue) {
        Object raw = params.get(key);
        if (raw == null) {
            return defaultValue;
        }
        if (raw instanceof Boolean value) {
            return value;
        }
        throw validation(key, "必须是布尔值");
    }

    private void reject(Map<String, Object> params, String key, String reason) {
        if (params.get(key) != null) {
            throw validation(key, reason);
        }
    }

    private static Map<String, Object> normalize(Map<String, Object> params) {
        if (params == null || params.isEmpty()) {
            return Map.of();
        }
        // 显式 null 与"未提供"同义：api-spec.md §5.3 没有任何"null 有特殊含义"的参数。
        Map<String, Object> copy = new LinkedHashMap<>();
        params.forEach((key, value) -> {
            if (key != null && value != null) {
                copy.put(key, value);
            }
        });
        return Map.copyOf(copy);
    }

    private static GameException validation(String field, String reason) {
        return GameException.of(
                DomainErrorCode.VALIDATION_ERROR, "参数 " + field + " 非法：" + reason, field, reason);
    }
}
