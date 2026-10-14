package com.wolfmind.infrastructure.metrics;

import com.wolfmind.application.port.MetricsPort;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;

/**
 * 进程内指标实现（Phase 5 可观测性）。
 *
 * <p><b>热路径上不加锁</b>：{@link LongAdder} 在竞争下按 cell 分段累加，{@link AtomicLong}
 * 用于只需要"最后写入者获胜"的仪表。指标写失败不该影响对局，因此这里也不做任何可能抛异常的
 * 校验——名字为空就直接忽略。
 *
 * <p><b>指标随进程消失</b>，这是刻意的：它是"这一轮运行健康吗"的观察窗口，不是审计数据。
 * 需要跨重启的历史，应该去看事件流与决策轨迹（它们才是真相）。
 */
public final class InProcessMetrics implements MetricsPort {

    private final Map<String, LongAdder> counters = new ConcurrentHashMap<>();
    private final Map<String, AtomicLong> gauges = new ConcurrentHashMap<>();
    private final Map<String, TimerState> timers = new ConcurrentHashMap<>();
    private final long sinceEpochMillis = System.currentTimeMillis();

    @Override
    public void counter(String name, long delta) {
        if (name == null || name.isBlank()) {
            return;
        }
        counters.computeIfAbsent(name, key -> new LongAdder()).add(delta);
    }

    @Override
    public void gauge(String name, long value) {
        if (name == null || name.isBlank()) {
            return;
        }
        gauges.computeIfAbsent(name, key -> new AtomicLong()).set(value);
    }

    @Override
    public void timing(String name, long millis) {
        if (name == null || name.isBlank()) {
            return;
        }
        TimerState state = timers.computeIfAbsent(name, key -> new TimerState());
        state.count.increment();
        state.total.add(Math.max(0L, millis));
        state.recordMax(Math.max(0L, millis));
    }

    @Override
    public Snapshot snapshot() {
        Map<String, Long> counterView = new LinkedHashMap<>();
        counters.forEach((name, adder) -> counterView.put(name, adder.sum()));
        Map<String, Long> gaugeView = new LinkedHashMap<>();
        gauges.forEach((name, value) -> gaugeView.put(name, value.get()));
        Map<String, Timer> timerView = new LinkedHashMap<>();
        timers.forEach((name, state) -> timerView.put(
                name, new Timer(state.count.sum(), state.total.sum(), state.max.get())));
        return new Snapshot(sinceEpochMillis, counterView, gaugeView, timerView);
    }

    /** 重置全部指标；只给测试用，生产没有理由清零一个观察窗口。 */
    public void reset() {
        counters.clear();
        gauges.clear();
        timers.clear();
    }

    private static final class TimerState {
        private final LongAdder count = new LongAdder();
        private final LongAdder total = new LongAdder();
        private final AtomicLong max = new AtomicLong();

        private void recordMax(long value) {
            max.accumulateAndGet(value, Math::max);
        }
    }
}
