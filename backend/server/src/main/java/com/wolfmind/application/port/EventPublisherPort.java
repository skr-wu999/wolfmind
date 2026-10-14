package com.wolfmind.application.port;

import com.wolfmind.domain.game.event.EventEnvelope;

import java.util.List;

/**
 * 事件发布端口（event-design.md §1）。
 *
 * <p>"集成事件"不是独立的组件：发布就是把这个已持久化的事实交给消费者，Event Bus 就是
 * Redis Stream。发布发生在状态与事件成功提交**之后**（DESIGN.md §6 流程）；发布失败不影响
 * 状态一致性，由事件存留 + 消费组位点补偿。
 */
public interface EventPublisherPort {

    /**
     * 发布已提交的事件。
     *
     * @param consumerGroup 目标消费组（{@code cg-projection} / {@code cg-broadcast} /
     *                      {@code cg-agent} / {@code cg-insight}），各自独立位点
     */
    void publish(String consumerGroup, List<EventEnvelope> events);

    /**
     * 推送给实时订阅者（{@code cg-broadcast}）。
     *
     * <p>推送不属于事务：允许延迟与重复，客户端以帧内 {@code seq} 幂等丢弃。
     */
    void publishRealtime(List<EventEnvelope> events);
}
