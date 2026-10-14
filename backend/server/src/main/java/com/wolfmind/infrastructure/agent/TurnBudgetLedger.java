package com.wolfmind.infrastructure.agent;

import com.wolfmind.application.agent.model.TurnKey;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * G11 的回合调用计数（tool-gateway.md §11 {@code gateway.maxToolCallsPerTurn}、
 * agent-runtime.md §9"工具调用：每回合最多 6 次"）。
 *
 * <p>为什么网关要自己数：{@code ToolSession.remainingToolCalls} 是 Runtime 给的余额，
 * 但记录型会话无法在调用后被扣减，Runtime 每次提交都会用同一个余额构造会话
 * （见 {@code TurnRequest.toolSession(...)} 的调用方式）。只信这个字段，等于把"每回合 6 次"
 * 交给调用方自觉；网关自己再数一遍，两个上限取小者，滥用才会真的被挡住。
 *
 * <p>键含回合版本：下一个回合天然是新键，因此计数不需要显式清理。
 * 容量上限只是长跑进程的保护——超过后淘汰最旧的回合，最坏情况下某个很早的回合计数被重置，
 * 而该回合早已结束（回合结束会推进版本，旧键不会再被使用）。
 *
 * <p>线程安全：所有方法同步；Runtime 可能从多个对局线程并发调用。
 */
public final class TurnBudgetLedger {

    /** 默认容量：够放几百局同时进行的回合，同时让长跑进程的内存占用有上界。 */
    public static final int DEFAULT_CAPACITY = 4096;

    private final int capacity;
    private final Map<TurnKey, Integer> used;

    public TurnBudgetLedger() {
        this(DEFAULT_CAPACITY);
    }

    public TurnBudgetLedger(int capacity) {
        if (capacity < 1) {
            throw new IllegalArgumentException("计数容量必须 ≥ 1");
        }
        this.capacity = capacity;
        this.used = new LinkedHashMap<>(16, 0.75f, false) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<TurnKey, Integer> eldest) {
                return size() > TurnBudgetLedger.this.capacity;
            }
        };
    }

    /** 回合键：同一 (gameId, playerId, turnVersion) 的所有调用共享一个计数。 */
    public static TurnKey key(String gameId, String playerId, int turnVersion) {
        return new TurnKey(gameId, playerId, turnVersion);
    }

    public synchronized int usedCount(TurnKey key) {
        return used.getOrDefault(key, 0);
    }

    /** 记录一次调用并返回累计次数。 */
    public synchronized int consume(TurnKey key) {
        int next = used.getOrDefault(key, 0) + 1;
        used.put(key, next);
        return next;
    }

    /** 测试与运维用：当前保留的回合数。 */
    public synchronized int trackedTurns() {
        return used.size();
    }
}
