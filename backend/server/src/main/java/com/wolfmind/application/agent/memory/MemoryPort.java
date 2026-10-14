package com.wolfmind.application.agent.memory;

import java.util.List;

/**
 * 记忆读写端口（agent-runtime.md §7）。
 *
 * <p>与领域记忆的区别：这里只关心"Agent 的短期/长期记忆"，不涉及对局状态。
 * 短期记忆由 {@code cg-agent} 消费者在事件到达时更新，**Runtime 只读**
 * （agent-runtime.md §7 规则 1）；长期记忆在 {@code GAME_FINISHED} 后由 Runtime 写入。
 *
 * <p>隐私底线（§7 规则 2）：跨局记忆只保留策略性内容与匿名化统计，
 * **不保留具体对局的隐藏信息**（例如"第 3 号是预言家"）——否则一条记忆会在下一局变成跨局泄漏。
 */
public interface MemoryPort {

    /**
     * 召回短期记忆（本局内）。
     *
     * @param gameId   对局
     * @param playerId 座位
     * @param limit    召回条数上限
     */
    List<MemoryEntry> loadShortTerm(String gameId, String playerId, int limit);

    /**
     * 召回长期记忆（跨局），按 {@code agentId + role + phase} 过滤（§7 规则 4）。
     *
     * @param agentConfigId 配置维度；同一配置的 Agent 共享策略经验
     * @param role          角色过滤，{@code null} 表示不限
     * @param phase         阶段过滤，{@code null} 表示不限
     */
    List<MemoryEntry> loadLongTerm(String agentConfigId, String role, String phase, int limit);

    /** 写入长期记忆；写入前必须经过隐私过滤（由实现负责）。 */
    void saveLongTerm(String agentConfigId, MemoryEntry entry);

    /** 追加短期记忆；由消费者的 MemoryUpdater 调用，Runtime 不调用。 */
    void appendShortTerm(String gameId, String playerId, MemoryEntry entry);

    /** 对局结束时清理短期记忆。 */
    void clearShortTerm(String gameId, String playerId);

    /** 记忆条目（agent-runtime.md §7 的四个长期类型 + 短期摘要）。 */
    record MemoryEntry(
            Type type,
            String role,
            String phase,
            String content,
            double importance,
            long occurredAt) {

        public enum Type {
            /** 本局事件摘要。 */
            EPISODE,
            /** 跨局事实（匿名化）。 */
            FACT,
            /** 策略偏好。 */
            STRATEGY,
            /** 复盘结论。 */
            REFLECTION
        }

        public MemoryEntry {
            if (type == null) {
                throw new IllegalArgumentException("记忆类型不能为空");
            }
            if (content == null || content.isBlank()) {
                throw new IllegalArgumentException("记忆内容不能为空");
            }
            if (importance < 0 || importance > 1) {
                throw new IllegalArgumentException("importance 必须在 0..1 之间");
            }
        }

        public static MemoryEntry strategy(String role, String phase, String content) {
            return new MemoryEntry(Type.STRATEGY, role, phase, content, 0.6, 0L);
        }
    }
}
