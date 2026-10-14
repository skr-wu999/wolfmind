package com.wolfmind.application.agent.view;

import com.wolfmind.application.agent.model.AgentView;

/**
 * AI 侧视图查询端口：给一个座位构造它有权看到的 {@link AgentView}。
 *
 * <p>它与 {@code GameViewQueryPort} 的分工：
 * <ul>
 *   <li>{@code GameViewQueryPort} 面向 REST/回放，返回 {@code GameViewSnapshot}（含领域枚举）
 *       与按可见性过滤的事件信封；</li>
 *   <li>本端口面向 Agent，返回**不依赖领域类型**的 {@link AgentView}（字符串表达角色与阶段），
 *       供 DEP-7 成立：{@code application.agent} 不引用 {@code domain.game.model}。</li>
 * </ul>
 *
 * <p>实现放在 {@code infrastructure.agent}：它是应用层端口 + 基础设施适配器的组合，
 * 负责把领域投影"翻译"成 Agent 形状，并**复用同一份可见性判定**，不另写过滤逻辑（AP-6）。
 *
 * <p>只读：本端口永不产生事件、永不修改状态。
 */
public interface AgentViewQueryPort {

    /**
     * 构造某座位的角色可见视图。
     *
     * @param gameId   对局
     * @param playerId 座位玩家 id；必须是本局实际存在的座位，否则实现应抛
     *                 {@code GameException(GAME_NOT_RUNNING)} 或返回空
     * @return 座位视图；座位不属于本局时返回 {@code null}
     */
    AgentView viewFor(String gameId, String playerId);

    /** 该座位当前是否仍在局中（用于 {@code GAME_FINISHED} 后清理实例的判定）。 */
    boolean isSeatInGame(String gameId, String playerId);
}
