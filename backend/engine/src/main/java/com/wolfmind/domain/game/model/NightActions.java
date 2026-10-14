package com.wolfmind.domain.game.model;

/**
 * 当夜临时状态（domain-model.md §5.4）：狼刀目标、女巫用药、查验结果。
 *
 * <p>可变对象，因为它是"同一夜多个行动逐步累积"的暂存区，进入 {@code NIGHT_SETTLE} 后清空。
 * 它不是可独立修改的领域状态：每一次变化都由对应事件驱动。
 */
public final class NightActions {

    private Integer wolfKillTarget;
    private Integer witchSaved;
    private Integer witchPoisoned;
    private Integer inspectedTarget;
    private Camp inspectResult;

    /** 狼刀定案目标座位；{@code null} 表示尚未定案或空刀。 */
    public Integer wolfKillTarget() {
        return wolfKillTarget;
    }

    /** 女巫解药目标座位；{@code null} 表示本夜未使用解药。 */
    public Integer witchSaved() {
        return witchSaved;
    }

    /** 女巫毒药目标座位；{@code null} 表示本夜未使用毒药。 */
    public Integer witchPoisoned() {
        return witchPoisoned;
    }

    public Integer inspectedTarget() {
        return inspectedTarget;
    }

    public Camp inspectResult() {
        return inspectResult;
    }

    public void selectWolfKillTarget(Integer targetSeat) {
        this.wolfKillTarget = targetSeat;
    }

    public void saveByWitch(int targetSeat) {
        this.witchSaved = targetSeat;
    }

    public void poisonByWitch(int targetSeat) {
        this.witchPoisoned = targetSeat;
    }

    public void recordInspection(int targetSeat, Camp result) {
        this.inspectedTarget = targetSeat;
        this.inspectResult = result;
    }

    /** 是否存在任何暂存行动，供测试与诊断使用。 */
    public boolean isEmpty() {
        return wolfKillTarget == null
                && witchSaved == null
                && witchPoisoned == null
                && inspectedTarget == null
                && inspectResult == null;
    }

    /** 清空当夜状态：{@code NIGHT_SETTLE} 结算完成后调用。 */
    public void clear() {
        this.wolfKillTarget = null;
        this.witchSaved = null;
        this.witchPoisoned = null;
        this.inspectedTarget = null;
        this.inspectResult = null;
    }

    /** 复制一份，供快照使用（快照必须是不可变推导，不能持有可变引用）。 */
    public NightActions copy() {
        NightActions copy = new NightActions();
        copy.wolfKillTarget = this.wolfKillTarget;
        copy.witchSaved = this.witchSaved;
        copy.witchPoisoned = this.witchPoisoned;
        copy.inspectedTarget = this.inspectedTarget;
        copy.inspectResult = this.inspectResult;
        return copy;
    }
}
