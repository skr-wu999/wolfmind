package com.wolfmind.application.port;

import com.wolfmind.domain.game.event.EventEnvelope;

import java.util.List;

/**
 * Agent 运行时端口（system-design.md §10）。
 *
 * <p>Agent 与对局同进程时是本地调用，拆分为多实例时把实现换成"消费组 + 队列"即可，
 * 领域、事件契约、协议语义全部不变。
 *
 * <p>硬规则：{@code AgentRuntimePort} 的实现**不得**引用 {@code domain.game.model}
 * （DEP-7）——AI 侧只认 {@code GameView}，写路径唯一入口是工具网关与
 * {@code SubmitActionApplicationService}。
 */
public interface AgentRuntimePort {

    /**
     * 处理一个已提交的回合事件。
     *
     * <p>由 {@code cg-agent} 消费 {@code TURN_GRANTED} 触发，不做轮询：{@code TURN_GRANTED}
     * 是唯一的回合发放者，AI 无法自行开始回合，也无法通过工具改变"轮到谁"。
     *
     * @param events 本批已提交事件（含 {@code TURN_GRANTED}）
     */
    void onCommittedEvents(List<EventEnvelope> events);

    /** 对局结束时清理该局全部 Agent 生命周期与短期记忆（长期记忆由归档消费者写入）。 */
    void onGameFinished(String gameId);
}
