package com.wolfmind.domain.game.service;

import com.wolfmind.domain.game.model.GameException;
import com.wolfmind.domain.game.model.Role;
import com.wolfmind.domain.game.model.RoleComposition;

import java.util.ArrayList;
import java.util.List;

/**
 * 确定性随机源（domain-model.md §10）。
 *
 * <p>领域层禁止使用 {@code java.util.Random}、{@code Clock}、{@code UUID}（INV-16），随机性只允许
 * 出现在"产生事件的那一刻"，且结果必须写入事件 payload：重放时不重新随机，
 * {@code ROLE_ASSIGNED} 事件就是角色分配的唯一真相。
 *
 * <p>这里实现 SplitMix64 与 Fisher–Yates：同一 seed 在任何 JVM 上给出同一排列，
 * 不依赖任何平台随机源。
 */
public final class DeterministicShuffle {

    private long state;

    private DeterministicShuffle(long seed) {
        this.state = seed;
    }

    public static DeterministicShuffle of(long seed) {
        return new DeterministicShuffle(seed);
    }

    /** SplitMix64：无符号 64 位溢出是算法的一部分，因此这里刻意依赖 long 的回绕语义。 */
    private long nextLong() {
        state += 0x9E3779B97F4A7C15L;
        long z = state;
        z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L;
        z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL;
        return z ^ (z >>> 31);
    }

    /** 无偏取模：用无符号右移去掉符号位影响。 */
    private int nextInt(int bound) {
        if (bound <= 0) {
            throw new IllegalArgumentException("bound 必须为正数");
        }
        long value = nextLong() >>> 1;
        return (int) (value % bound);
    }

    /**
     * 原位洗牌（Fisher–Yates）。
     *
     * @param values 待洗牌列表，允许任意可变 List
     */
    public <T> void shuffle(List<T> values) {
        for (int i = values.size() - 1; i > 0; i--) {
            int j = nextInt(i + 1);
            T tmp = values.get(i);
            values.set(i, values.get(j));
            values.set(j, tmp);
        }
    }

    /**
     * 按 {@code RoleComposition} 把角色分配到座位。
     *
     * <p>返回列表下标 {@code i} 对应座位号 {@code i + 1}。角色配置的合法性（总数等于座位数、
     * 狼数 ≥ 1、村民阵营 ≥ 1）由 {@link RoleComposition} 保证，此处只负责确定性分配。
     *
     * @param composition 角色配置
     * @param randomSeed  随机种子，必须写入事件以便重放
     */
    public static List<Role> assignRoles(RoleComposition composition, long randomSeed) {
        List<Role> roles = new ArrayList<>(composition.expandedRoles());
        if (roles.isEmpty()) {
            throw GameException.Factory.compositionInvalid("角色配置展开后为空");
        }
        of(randomSeed).shuffle(roles);
        return List.copyOf(roles);
    }
}
