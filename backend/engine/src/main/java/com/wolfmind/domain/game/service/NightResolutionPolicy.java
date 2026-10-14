package com.wolfmind.domain.game.service;

import com.wolfmind.domain.game.model.Camp;
import com.wolfmind.domain.game.model.DeathCause;
import com.wolfmind.domain.game.model.DeathRecord;
import com.wolfmind.domain.game.model.PlayerSnapshot;
import com.wolfmind.domain.game.model.SeatNo;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 夜晚结算顺序（domain-model.md §6.3）。
 *
 * <p>输入当夜狼刀目标、女巫用药，输出死亡集合与死因集合。纯函数：不访问时钟、随机数与仓储。
 *
 * <pre>
 * 1. deaths := {}
 * 2. 若 wolfKillTarget != null
 *      若解药在当夜被用于该目标 → 该目标不死
 *      否则 deaths[wolfKillTarget] += WOLF_KILL
 * 3. 若 witchPoisoned != null → deaths[witchPoisoned] += WITCH_POISON
 * 4. 对每个死亡按座位号升序输出 DeathRecord
 * </pre>
 */
public final class NightResolutionPolicy {

    private NightResolutionPolicy() {}

    /**
     * 结算结果。
     *
     * @param deaths       当夜死亡，按座位号升序（事件顺序因此确定，INV-16）
     * @param savedSeat    被解药救下的座位；未使用解药时为 {@code null}
     * @param killNegated  狼刀是否被解药化解
     */
    public record Resolution(List<DeathRecord> deaths, Integer savedSeat, boolean killNegated) {

        public Resolution {
            deaths = List.copyOf(deaths);
        }

        public boolean peacefulNight() {
            return deaths.isEmpty();
        }
    }

    /**
     * 结算当夜。
     *
     * @param players        全部玩家快照（含存活状态与角色）
     * @param wolfKillTarget 狼刀定案座位；空刀时为 {@code null}
     * @param witchSaved     解药目标座位；本夜未用解药时为 {@code null}
     * @param witchPoisoned  毒药目标座位；本夜未用毒药时为 {@code null}
     * @param round          当前轮次，写入死亡记录
     */
    public static Resolution resolve(
            List<PlayerSnapshot> players,
            Integer wolfKillTarget,
            Integer witchSaved,
            Integer witchPoisoned,
            int round) {

        Map<Integer, Set<DeathCause>> deaths = new LinkedHashMap<>();
        boolean killNegated = false;

        // 第 2 步：狼刀 → 解药
        if (wolfKillTarget != null) {
            if (witchSaved != null && witchSaved.intValue() == wolfKillTarget.intValue()) {
                killNegated = true;
            } else {
                deaths.computeIfAbsent(wolfKillTarget, key -> EnumSet.noneOf(DeathCause.class))
                        .add(DeathCause.WOLF_KILL);
            }
        }

        // 第 3 步：毒药（同夜双药在 ActionValidator 已被拒绝，这里只做合并）
        if (witchPoisoned != null) {
            deaths.computeIfAbsent(witchPoisoned, key -> EnumSet.noneOf(DeathCause.class))
                    .add(DeathCause.WITCH_POISON);
        }

        Map<Integer, PlayerSnapshot> bySeat = new LinkedHashMap<>();
        for (PlayerSnapshot player : players) {
            bySeat.put(player.seatNo().value(), player);
        }

        List<DeathRecord> records = new ArrayList<>();
        deaths.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .forEach(entry -> {
                    PlayerSnapshot player = bySeat.get(entry.getKey());
                    if (player == null) {
                        throw new IllegalStateException("结算引用了不存在的座位 " + entry.getKey());
                    }
                    records.add(DeathRecord.of(
                            player.playerId(),
                            SeatNo.of(entry.getKey()),
                            EnumSet.copyOf(entry.getValue()),
                            round));
                });
        records.sort(Comparator.comparing(DeathRecord::seatNo));

        return new Resolution(records, killNegated ? wolfKillTarget : null, killNegated);
    }

    /**
     * 死亡是否意味着猎人失去开枪能力（INV-8）。
     *
     * <p>放在结算策略里而不是聚合里，使"被毒不能开枪"这条规则只有一处实现，可被单测直接覆盖。
     */
    public static boolean disablesShot(DeathRecord death, boolean hunterCanShootWhenPoisoned) {
        return death.causedBy(DeathCause.WITCH_POISON) && !hunterCanShootWhenPoisoned;
    }

    /**
     * 猎人是否可以开枪。
     *
     * @param hunter                  猎人快照（判定 {@code skillState.shotAvailable}）
     * @param death                   导致死亡的记录
     * @param hunterCanShootWhenPoisoned 规则参数 {@code hunterCanShootWhenPoisoned}
     * @param allowedOrigin           本次死亡来源是否触发开枪（狼刀与被放逐触发；被枪击不再连锁）
     */
    public static boolean canHunterShoot(
            PlayerSnapshot hunter,
            DeathRecord death,
            boolean hunterCanShootWhenPoisoned,
            boolean allowedOrigin) {
        if (!allowedOrigin) {
            return false;
        }
        if (!hunter.skillState().shotAvailable()) {
            return false;
        }
        return !disablesShot(death, hunterCanShootWhenPoisoned);
    }

    /** 便于诊断：死因集合的可读形式（不含角色信息，避免日志成为泄漏渠道）。 */
    public static String describe(DeathRecord death) {
        return death.seatNo() + ":" + death.causes();
    }
}
