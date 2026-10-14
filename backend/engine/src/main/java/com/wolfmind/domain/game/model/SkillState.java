package com.wolfmind.domain.game.model;

/**
 * 一次性技能可用性（domain-model.md §5.4）。
 *
 * <p>技能用后置 {@code false}，重放时由事件恢复，不重新推导。
 */
public record SkillState(boolean antidoteAvailable, boolean poisonAvailable, boolean shotAvailable) {

    /** 无技能角色。 */
    public static final SkillState NONE = new SkillState(false, false, false);

    public static SkillState forRole(Role role) {
        return switch (role) {
            case WITCH -> new SkillState(true, true, false);
            case HUNTER -> new SkillState(false, false, true);
            case WEREWOLF, SEER, VILLAGER -> NONE;
        };
    }

    public SkillState consumeAntidote() {
        return new SkillState(false, poisonAvailable, shotAvailable);
    }

    public SkillState consumePoison() {
        return new SkillState(antidoteAvailable, false, shotAvailable);
    }

    public SkillState disableShot() {
        return new SkillState(antidoteAvailable, poisonAvailable, false);
    }

    public SkillState consumeShot() {
        return disableShot();
    }
}
