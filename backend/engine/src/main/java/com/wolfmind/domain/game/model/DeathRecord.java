package com.wolfmind.domain.game.model;

import java.util.EnumSet;
import java.util.Set;

/**
 * 死亡记录（domain-model.md §5.4）。
 *
 * <p>{@code causes} 是死因集合，用于表达同夜既被狼刀又被毒药的情况；技能可用性判定读集合
 * 而不是 {@code primaryCause}：被毒死的猎人不能开枪（INV-8）。
 *
 * <p>{@code announced} 区分"死亡已是对局事实"与"死讯已向全场公布"：夜间结算先产生
 * {@code PLAYER_KILLED}（{@code JUDGE}），天亮时才用 {@code DEATH_ANNOUNCED}（{@code PUBLIC}）
 * 公布。这一层区分让"遗言资格"可以精确判定——只有已公布的死亡才有遗言。
 */
public record DeathRecord(
        PlayerId playerId,
        SeatNo seatNo,
        Set<DeathCause> causes,
        DeathCause primaryCause,
        int round,
        boolean announced) {

    public DeathRecord {
        if (playerId == null || seatNo == null) {
            throw new IllegalArgumentException("DeathRecord 的 playerId 与 seatNo 不能为空");
        }
        if (causes == null || causes.isEmpty()) {
            throw new IllegalArgumentException("DeathRecord.causes 不能为空");
        }
        causes = Set.copyOf(EnumSet.copyOf(causes));
        if (primaryCause == null) {
            primaryCause = DeathCause.primaryOf(causes);
        }
        if (round < 1) {
            throw new IllegalArgumentException("round 必须 ≥ 1");
        }
    }

    /** 未公布的死亡。 */
    public static DeathRecord of(PlayerId playerId, SeatNo seatNo, Set<DeathCause> causes, int round) {
        return new DeathRecord(playerId, seatNo, causes, DeathCause.primaryOf(causes), round, false);
    }

    /** 已公布的死亡（被放逐者的死亡在计票时就已是公开事实）。 */
    public static DeathRecord announced(
            PlayerId playerId, SeatNo seatNo, Set<DeathCause> causes, int round) {
        return new DeathRecord(playerId, seatNo, causes, DeathCause.primaryOf(causes), round, true);
    }

    public boolean causedBy(DeathCause cause) {
        return causes.contains(cause);
    }

    public boolean poisoned() {
        return causes.contains(DeathCause.WITCH_POISON);
    }

    /** 标记为已公布。 */
    public DeathRecord markAnnounced() {
        return announced ? this : new DeathRecord(playerId, seatNo, causes, primaryCause, round, true);
    }

    /** 合并同一次结算中针对同一玩家的多个死因。 */
    public DeathRecord merge(DeathRecord other) {
        if (!playerId.equals(other.playerId)) {
            throw new IllegalArgumentException("只能合并同一玩家的死亡记录");
        }
        EnumSet<DeathCause> merged = EnumSet.copyOf(causes);
        merged.addAll(other.causes);
        return new DeathRecord(
                playerId, seatNo, merged, DeathCause.primaryOf(merged), round, announced || other.announced);
    }
}
