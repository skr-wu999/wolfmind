package com.wolfmind.application.port;

import com.wolfmind.domain.game.event.EventEnvelope;

import java.util.List;

/**
 * 通知端口：事务提交**之后**的对外动作（system-design.md §5「推送不属于事务」）。
 *
 * <p>实现可以是 WebSocket 推送、Agent 触发或指标上报。它不得反过来修改对局状态：
 * 状态只能由"领域事件被重放"改变（INV-13）。
 */
public interface NotificationPort {

    /** 对局事件已提交。允许延迟与重复，消费者必须幂等。 */
    void onEventsCommitted(String gameId, int version, List<EventEnvelope> events);

    /** 对局进入终态。 */
    void onGameFinished(String gameId);
}
