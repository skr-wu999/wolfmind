package com.wolfmind.application.game;

import com.wolfmind.application.port.EventPublisherPort;
import com.wolfmind.domain.game.event.EventEnvelope;

import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * 提交之后的发布编排（event-design.md §9、system-design.md §5）。
 *
 * <p>顺序不可调换：先持久化后发布。发布失败不回滚状态、也不改变返回值——事件已经落地，
 * 由事件存留 + 消费组位点补偿；因此这里逐个消费组隔离异常，一个消费者不可用不影响其他消费者。
 *
 * <p>{@code cg-broadcast} 不在这里用 {@code publish(group, ...)} 走一遍：实时推送由
 * {@link EventPublisherPort#publishRealtime} 承担（推送不属于事务，允许延迟与重复）。
 */
public final class EventPublication {

    private static final Logger LOG = Logger.getLogger(EventPublication.class.getName());

    /** 与 event-design.md §9 的消费组表一致；{@code cg-broadcast} 由 publishRealtime 覆盖。 */
    private static final List<String> CONSUMER_GROUPS = List.of("cg-projection", "cg-agent", "cg-insight");

    private EventPublication() {}

    public static void publish(EventPublisherPort publisher, List<EventEnvelope> envelopes) {
        if (envelopes == null || envelopes.isEmpty()) {
            return;
        }
        for (String consumerGroup : CONSUMER_GROUPS) {
            try {
                publisher.publish(consumerGroup, envelopes);
            } catch (RuntimeException ex) {
                LOG.log(Level.WARNING, "事件已提交但发布失败，等待消费组位点补偿：" + consumerGroup, ex);
            }
        }
        try {
            publisher.publishRealtime(envelopes);
        } catch (RuntimeException ex) {
            LOG.log(Level.WARNING, "事件已提交但实时推送失败，等待客户端重连补偿", ex);
        }
    }
}
