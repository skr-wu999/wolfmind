package com.wolfmind.infrastructure.memory;

import com.wolfmind.application.port.EventPublisherPort;
import com.wolfmind.domain.game.event.EventEnvelope;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 内存事件发布器：把"已提交的事实"交给消费者。
 *
 * <p>它在测试里承担两个角色：验证"发布发生在提交之后"的调用顺序，以及让投影/推送消费者
 * 可以被单元测试驱动，而不需要真实 Redis。
 *
 * <p>与 Redis 版的语义差异：Redis 的发布不是一次独立动作，而是消费者 {@code XREADGROUP} 自然读到；
 * 因此这里只记录"哪些事件被指派给哪个消费组"，不模拟位点、PEL 与重投递。
 * 至少一次投递、重复消费的容忍能力必须由消费者自己保证（event-design.md §10），
 * 不能依赖发布器。
 */
public final class InMemoryEventPublisher implements EventPublisherPort {

    private final boolean directDelivery;
    public InMemoryEventPublisher() { this(true); }
    public InMemoryEventPublisher(boolean directDelivery) { this.directDelivery = directDelivery; }

    private final Map<String, List<EventEnvelope>> byGroup = new LinkedHashMap<>();
    private final List<EventEnvelope> realtime = new ArrayList<>();

    @Override
    public void publish(String consumerGroup, List<EventEnvelope> events) {
        if (!directDelivery) return;
        if (consumerGroup == null || consumerGroup.isBlank()) {
            throw new IllegalArgumentException("consumerGroup 不能为空");
        }
        if (events == null || events.isEmpty()) {
            return;
        }
        synchronized (byGroup) {
            byGroup.computeIfAbsent(consumerGroup, key -> new ArrayList<>()).addAll(events);
        }
    }

    @Override
    public void publishRealtime(List<EventEnvelope> events) {
        if (!directDelivery) return;
        if (events == null || events.isEmpty()) {
            return;
        }
        synchronized (realtime) {
            realtime.addAll(events);
        }
        // 先记录再通知：监听者（WebSocket 扇出）在通知里读到的状态必须已经包含这批事件。
        for (RealtimeListener listener : listeners) {
            listener.onRealtime(events);
        }
    }

    /**
     * 实时事件的监听者（WebSocket 扇出是唯一的使用者）。
     *
     * <p><b>为什么用监听者而不是给 WebSocket 再造一个发布器 Bean</b>：造第二个
     * {@code EventPublisherPort} 会让"已提交事件的唯一发布点"变成两个，而
     * AGENTS.md §7 要求扇出只发生在提交成功之后。包一层装饰器要么改掉已有测试的断言
     * （{@code realtimePublished()} 不再是被调用的那个实例），要么引入 Bean 覆盖。
     * 监听者模式没有这两个代价：发布点仍然只有一个。
     *
     * <p>监听者抛出的异常被吞掉：一个扇出失败不能影响发布本身，否则"推送失败"
     * 会变成"命令失败"——而事件其实已经落库了。
     */
    public interface RealtimeListener {

        void onRealtime(List<EventEnvelope> events);
    }

    private final List<RealtimeListener> listeners = new java.util.concurrent.CopyOnWriteArrayList<>();

    /** 注册监听者；重复注册同一个实例是幂等的。 */
    public void addRealtimeListener(RealtimeListener listener) {
        if (listener != null && !listeners.contains(listener)) {
            listeners.add(listener);
        }
    }

    public void removeRealtimeListener(RealtimeListener listener) {
        listeners.remove(listener);
    }

    /** 某消费组收到的事件，按发布顺序。 */
    public List<EventEnvelope> publishedTo(String consumerGroup) {
        synchronized (byGroup) {
            return List.copyOf(byGroup.getOrDefault(consumerGroup, List.of()));
        }
    }

    /** 已推送给实时订阅者的事件，按推送顺序（允许重复，客户端以 seq 幂等丢弃）。 */
    public List<EventEnvelope> realtimePublished() {
        synchronized (realtime) {
            return List.copyOf(realtime);
        }
    }

    /** 已被指派过事件的消费组名。 */
    public List<String> consumerGroups() {
        synchronized (byGroup) {
            return List.copyOf(byGroup.keySet());
        }
    }
}
