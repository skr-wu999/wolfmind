package com.wolfmind.domain.game.model;

/**
 * 死因（domain-model.md §5.4）。
 *
 * <p>声明顺序即 {@code primaryCause} 的优先级顺序：{@code WOLF_KILL} &gt; {@code WITCH_POISON}
 * &gt; {@code HUNTER_SHOT} &gt; {@code EXILE}。技能可用性判定必须读整个 {@code causes} 集合，
 * 不能只看 {@code primaryCause}：被毒死的猎人不能开枪（INV-8）。
 */
public enum DeathCause {
    WOLF_KILL,
    WITCH_POISON,
    HUNTER_SHOT,
    EXILE;

    /** 取优先级最高的死因（声明顺序）。 */
    public static DeathCause primaryOf(java.util.Collection<DeathCause> causes) {
        for (DeathCause candidate : values()) {
            if (causes.contains(candidate)) {
                return candidate;
            }
        }
        throw new IllegalArgumentException("死因集合不能为空");
    }
}
