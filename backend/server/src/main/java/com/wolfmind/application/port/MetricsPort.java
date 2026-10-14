package com.wolfmind.application.port;

import java.util.Map;

/**
 * 指标端口（Phase 5 可观测性）。
 *
 * <p><b>为什么不用 Spring Boot Actuator / Micrometer：</b>那是一个新的技术选型与一套新的端点契约
 * （{@code /actuator/*}），而本项目在第一版只需要回答四个运维问题：
 * "对局在推进吗、事件在写吗、模型花了多少钱、有没有在降级"。
 * 为此引入一个指标框架，会让"运维限制成文"这件事从"写清楚三个数"变成"配置一套采集链路"。
 * 需要接 Prometheus 时，{@code GET /api/v1/metrics} 的输出可以直接被它的抓取器读。
 *
 * <p><b>无标签（no tags）。</b>按 gameId 打标签会让一个长跑实例的指标基数随对局数无界增长，
 * 而"哪一局慢"应该去查事件流与决策轨迹，不是查指标。这里的指标只回答"整体健康吗"。
 *
 * <p><b>实现必须线程安全且无锁竞争</b>：它在游戏热路径上被调用（每次提交、每次模型调用）。
 */
public interface MetricsPort {

    /** 累加计数器。 */
    void counter(String name, long delta);

    /** 设置瞬时值（仪表）；并发写入以最后一次为准。 */
    void gauge(String name, long value);

    /** 记录一次耗时（毫秒）：同时累加次数、总时长与最大值。 */
    void timing(String name, long millis);

    /** 读取快照：计数、仪表、耗时汇总，以及起点时间。 */
    Snapshot snapshot();

    /** 什么都不做的实现；测试与未装配指标的部署用它，避免到处判空。 */
    MetricsPort NOOP = new MetricsPort() {
        @Override
        public void counter(String name, long delta) {
            // 无指标后端
        }

        @Override
        public void gauge(String name, long value) {
            // 无指标后端
        }

        @Override
        public void timing(String name, long millis) {
            // 无指标后端
        }

        @Override
        public Snapshot snapshot() {
            return new Snapshot(0L, Map.of(), Map.of(), Map.of());
        }
    };

    /**
     * 指标快照。
     *
     * @param sinceEpochMillis 进程内指标起点；配合 {@code uptimeMillis} 判断"是不是刚重启"
     * @param counters         {@code name → 累计值}
     * @param gauges           {@code name → 当前值}
     * @param timers           {@code name → 计数 / 总时长 / 最大时长}
     */
    record Snapshot(
            long sinceEpochMillis,
            Map<String, Long> counters,
            Map<String, Long> gauges,
            Map<String, Timer> timers) {

        public Snapshot {
            counters = Map.copyOf(counters);
            gauges = Map.copyOf(gauges);
            timers = Map.copyOf(timers);
        }
    }

    /** 耗时汇总；{@code averageMs} 在计数为 0 时为 {@code null}。 */
    record Timer(long count, long totalMs, long maxMs) {

        public Long averageMs() {
            return count == 0 ? null : Math.round((double) totalMs / count);
        }
    }

    /**
     * 指标名目录。
     *
     * <p>集中在一处而不是各处拼字符串：一个打错的名字不会报错，只会让面板上少一条线，
     * 而"少一条线"通常几周后才被发现。
     */
    final class Names {

        private Names() {}

        /** 已追加的领域事件总数（每一次成功提交的状态变化）。 */
        public static final String EVENTS_APPENDED = "wolfmind.events.appended";
        /** 进入终态的对局数，按结果分开。 */
        public static final String GAMES_FINISHED = "wolfmind.games.finished";
        public static final String GAMES_ABORTED = "wolfmind.games.aborted";
        /** 已落地的 AI 回合数；{@code FALLBACK} 是其中由规则兜底完成的部分。 */
        public static final String TURNS_FINISHED = "wolfmind.agent.turns";
        public static final String TURNS_FALLBACK = "wolfmind.agent.turns.fallback";
        /** 模型调用与成本。 */
        public static final String LLM_CALLS = "wolfmind.llm.calls";
        public static final String LLM_FAILURES = "wolfmind.llm.failures";
        public static final String LLM_PROMPT_TOKENS = "wolfmind.llm.prompt.tokens";
        public static final String LLM_COMPLETION_TOKENS = "wolfmind.llm.completion.tokens";
        public static final String LLM_LATENCY = "wolfmind.llm.latency";
        /** 因 token 预算（{@code wolfmind.llm.max-tokens-per-game}）被拒绝的模型调用。 */
        public static final String LLM_BUDGET_REJECTIONS = "wolfmind.llm.budget.rejections";
        /** 当前被预算挡住的座位数（仪表）。 */
        public static final String LLM_BUDGET_BLOCKED_SEATS = "wolfmind.llm.budget.blocked.seats";
        public static final String ARCHIVE_PENDING_GAMES = "wolfmind.archive.pending.games";
        public static final String ARCHIVE_FAILED_GAMES = "wolfmind.archive.failed.games";
        public static final String ARCHIVE_FAILURES = "wolfmind.archive.failures";
    }
}
