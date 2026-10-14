package com.wolfmind.application.agent.strategy;

import com.wolfmind.application.agent.model.AgentView;

/**
 * 打分平局的次序键（strategy-layer.md §13）。
 *
 * <p>平局按 {@code (gameId, round, purpose, seatNo)} 的稳定哈希排序，而不是按座位号升序。
 * 第一夜所有人的公开信息都一样，按座位号升序会让同一个座位（通常是房主所在的 1 号）
 * 在每一局都排第一，狼刀、查验、投票都会系统性地偏向它。
 *
 * <p>它仍然是纯函数：不读时钟、不用随机数生成器，同一局同一轮的同一用途总是得到同一顺序，
 * 因此对局可以复现；同一局的狼队成员拿到相同的键，夜里提名也就保持一致。
 * {@code purpose} 把不同用途的顺序拆开，避免预言家的查验平局与狼刀平局落在同一个座位上。
 */
public final class TieBreak {

    private final long seed;

    private TieBreak(long seed) {
        this.seed = seed;
    }

    /** 本局本轮某一用途的平局键；视图为空时退化为固定种子。 */
    public static TieBreak of(AgentView view, String purpose) {
        String gameId = view == null || view.gameId() == null ? "" : view.gameId();
        int round = view == null ? 0 : view.round();
        String salt = purpose == null ? "" : purpose;
        long seed = mix(gameId.hashCode());
        seed = mix(seed ^ round);
        seed = mix(seed ^ salt.hashCode());
        return new TieBreak(seed);
    }

    /** 座位的次序键：值越小越靠前。 */
    public long key(int seatNo) {
        return mix(seed ^ (seatNo * 0x9E3779B97F4A7C15L));
    }

    /** SplitMix64 的终结函数：输入的一位变化会扩散到整个输出。 */
    private static long mix(long value) {
        long z = value + 0x9E3779B97F4A7C15L;
        z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L;
        z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL;
        return z ^ (z >>> 31);
    }
}
