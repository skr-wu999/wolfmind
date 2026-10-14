package com.wolfmind.perf;

import com.wolfmind.application.agent.model.AgentConfig;
import com.wolfmind.application.agent.runtime.AIControllerAdapter;
import com.wolfmind.application.agent.runtime.AgentRuntimeImpl;
import com.wolfmind.application.agent.strategy.WolfMindStrategy;
import com.wolfmind.domain.game.model.GameRules;
import com.wolfmind.domain.game.model.GameStatus;
import com.wolfmind.domain.game.model.RoleComposition;
import com.wolfmind.infrastructure.agent.AgentViewAdapter;
import com.wolfmind.infrastructure.agent.ToolGatewayAdapter;
import com.wolfmind.infrastructure.agent.wiring.InfrastructureGameHarness;
import com.wolfmind.infrastructure.memory.InMemoryAgentTrace;
import com.wolfmind.infrastructure.memory.InMemoryMemoryPort;
import com.wolfmind.testsupport.ControlledTurnDriver;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 性能基线（Phase 5 的"性能测试"）：一局九人规则基线对局的推进成本。
 *
 * <p><b>默认不跑。</b>它用真实时钟测量墙钟时间，因此结果取决于机器；放进默认测试套件里，
 * 断言要么太松（没有信息量）要么太紧（换台机器就红）。运行方式：
 * <pre>
 *   mvn -o -pl server -am test -Dwolfmind.perf.enabled=true \
 *       -Dtest=PerformanceBenchmarkTest -Dsurefire.failIfNoSpecifiedTests=false
 * </pre>
 *
 * <p><b>它测量的是什么。</b>九个座位全部走规则基线（不调用模型），因此测的是
 * "领域 + 应用层 + 工具网关 + 驱动"这条链路的自身开销，而不是模型延迟。
 * 这条数字的用途是回答"一局需要多少步、每一步多贵"，从而给出可预测的规模上限；
 * 含模型的真人对局成本由 {@code /api/v1/metrics} 的 token 与延迟指标回答。
 *
 * <p><b>阈值是防退化的粗界</b>，不是性能承诺：单步超过 100 ms 或单局超过 5000 步说明
 * 驱动路径上出现了意料之外的开销（例如加入了阻塞 I/O）。
 */
@EnabledIfSystemProperty(named = "wolfmind.perf.enabled", matches = "true")
class PerformanceBenchmarkTest {

    /** 测量局数；{@code -Dwolfmind.perf.games=N} 覆盖。 */
    private static final int GAMES = Integer.getInteger("wolfmind.perf.games", 5);

    private static final String REPORT_FILE = "perf-report.json";

    @Test
    @DisplayName("九人规则基线对局：单步耗时、事件吞吐与重放成本")
    void nineAiRuleBaselineThroughput() throws IOException {
        // 先跑一局不计入统计：JIT 尚未编译热点路径时第一步可以到百毫秒量级，
        // 把它算进 p95 会让这份基线测量的其实是"类加载速度"。
        runOneGame("g_perf_warmup", 1L);

        List<GameRun> runs = new ArrayList<>();
        for (int index = 0; index < GAMES; index++) {
            runs.add(runOneGame("g_perf_" + index, 20240501L + index));
        }

        long totalSteps = runs.stream().mapToLong(GameRun::steps).sum();
        long totalEvents = runs.stream().mapToLong(GameRun::events).sum();
        long totalNanos = runs.stream().mapToLong(GameRun::elapsedNanos).sum();
        List<Long> stepNanos = new ArrayList<>();
        runs.forEach(run -> stepNanos.addAll(run.stepNanos()));
        stepNanos.sort(Long::compareTo);

        Map<String, Object> report = new LinkedHashMap<>();
        report.put("mode", "rule-baseline (no model calls)");
        report.put("games", GAMES);
        report.put("finishedGames", runs.stream().filter(GameRun::finished).count());
        report.put("totalSteps", totalSteps);
        report.put("totalEvents", totalEvents);
        report.put("totalMillis", totalNanos / 1_000_000);
        report.put("eventsPerSecond", totalNanos == 0 ? null
                : Math.round(totalEvents * 1_000_000_000d / totalNanos));
        report.put("stepP50Millis", Math.round(percentile(stepNanos, 0.50) / 1e6 * 100) / 100d);
        report.put("stepP95Millis", Math.round(percentile(stepNanos, 0.95) / 1e6 * 100) / 100d);
        report.put("stepMaxMillis", Math.round(percentile(stepNanos, 1.0) / 1e6 * 100) / 100d);
        report.put("replayMillisPerGame", Math.round(runs.stream()
                .mapToLong(GameRun::replayNanos).average().orElse(0) / 1e6 * 100) / 100d);
        report.put("games", runs.stream().map(GameRun::describe).toList());
        String json = com.wolfmind.infrastructure.json.Json.write(report);
        Files.writeString(Path.of(REPORT_FILE), json + System.lineSeparator(), StandardCharsets.UTF_8);

        System.out.println("[perf] 报告已写入 " + Path.of(REPORT_FILE).toAbsolutePath());
        System.out.println("[perf] " + json);

        assertEquals(GAMES, runs.stream().filter(GameRun::finished).count(),
                "规则基线必须每局都能打完；打不完说明推进链路有阻塞点");
        // 判据是 p95 而不是 max：偶发的 GC 与首次分支预测失败会产生离群值，
        // 用它当门槛只会让这份基线随机器抖动。max 留作十倍宽的粗界，抓的是"真的插进了阻塞调用"。
        double p95Millis = percentile(stepNanos, 0.95) / 1e6;
        double maxMillis = percentile(stepNanos, 1.0) / 1e6;
        assertTrue(p95Millis < 100d,
                "单步 p95 超过 100ms：推进路径上可能出现了阻塞调用（模型调用必须走 Agent 子系统且受时限约束）");
        assertTrue(maxMillis < 1_000d, "单步最坏值超过 1s：出现了量级异常的阻塞：" + maxMillis + "ms");
        assertTrue(totalSteps < 5_000L * GAMES, "单局步数异常：" + totalSteps);
    }

    private record GameRun(
            String gameId, boolean finished, long steps, long events, long elapsedNanos,
            long replayNanos, List<Long> stepNanos) {

        Map<String, Object> describe() {
            return Map.of(
                    "gameId", gameId,
                    "finished", finished,
                    "steps", steps,
                    "events", events,
                    "elapsedMillis", elapsedNanos / 1_000_000,
                    "replayMillis", replayNanos / 1_000_000);
        }
    }

    private static GameRun runOneGame(String gameId, long seed) {
        InfrastructureGameHarness harness = new InfrastructureGameHarness(1_730_000_000_000L);
        AgentViewAdapter views = new AgentViewAdapter(harness.viewQuery());
        InMemoryAgentTrace trace = new InMemoryAgentTrace();
        ToolGatewayAdapter gateway = new ToolGatewayAdapter(
                views, harness.viewQuery(), harness.submitService(), harness.idempotency(), trace, harness.time());
        AgentRuntimeImpl runtime = new AgentRuntimeImpl(
                views, new WolfMindStrategy(gateway), gateway, new InMemoryMemoryPort(), trace, null, harness.time());
        ControlledTurnDriver driver = new ControlledTurnDriver(harness.repository(),
                new AIControllerAdapter(runtime, views, gateway, harness.time(),
                        AgentConfig.ruleBaseline("perf")),
                harness.time(), harness.publisher(), harness.eventStore(), harness.lock());

        harness.startNineAiGame(gameId, RoleComposition.STANDARD_NINE, GameRules.DEFAULT, seed);
        List<Long> stepNanos = new ArrayList<>();
        long startedAt = System.nanoTime();
        int steps = 0;
        while (steps < 5_000 && harness.repository().load(gameId).game().status() == GameStatus.RUNNING) {
            harness.advanceClock(200L);
            long stepStart = System.nanoTime();
            driver.step(gameId);
            stepNanos.add(System.nanoTime() - stepStart);
            steps++;
        }
        long elapsed = System.nanoTime() - startedAt;
        boolean finished = harness.repository().load(gameId).game().status() == GameStatus.FINISHED;

        // 重放成本：从事件流全量重建一次聚合，等价于恢复流程里最坏的那条路径。
        long replayStart = System.nanoTime();
        harness.repository().load(gameId);
        long replay = System.nanoTime() - replayStart;

        return new GameRun(gameId, finished, steps, harness.eventStream(gameId).size(),
                elapsed, replay, List.copyOf(stepNanos));
    }

    /** 最近百分位（线性插值）。样本量小，插值比"取第 k 个"更稳定。 */
    private static double percentile(List<Long> sorted, double fraction) {
        if (sorted.isEmpty()) {
            return 0d;
        }
        double position = fraction * (sorted.size() - 1);
        int lower = (int) Math.floor(position);
        int upper = (int) Math.ceil(position);
        double weight = position - lower;
        return sorted.get(lower) * (1 - weight) + sorted.get(upper) * weight;
    }
}
