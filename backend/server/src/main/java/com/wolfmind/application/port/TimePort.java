package com.wolfmind.application.port;

/**
 * 时间端口。
 *
 * <p>领域层禁止取当前时间（INV-16、DEP-2），因此时间只能由应用层注入：本端口提供
 * 单调不减的毫秒时间戳，用于填充事件的 {@code occurredAt} 与回合 {@code deadline}。
 *
 * <p>{@code occurredAt} 的单调性由调用方按 event-design.md §3.2 保证：
 * {@code occurredAt = max(now, lastTs)}。
 */
public interface TimePort {

    /** 当前毫秒时间戳。 */
    long nowMillis();
}
