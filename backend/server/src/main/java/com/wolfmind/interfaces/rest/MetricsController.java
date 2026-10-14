package com.wolfmind.interfaces.rest;

import com.wolfmind.application.agent.runtime.LlmCostGuard;
import com.wolfmind.application.port.MetricsPort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 运维指标（Phase 5 可观测性）。
 *
 * <p><b>为什么不复用 {@code /health}：</b>两者回答的问题不同。{@code /health} 回答"实例该不该被摘掉"
 * （依赖是否可用），因此它的状态只能有 UP/DOWN 两态；本端点回答"它跑得怎么样"，
 * 因此永远不会返回 5xx——把"指标里有异常数字"表达成"服务不健康"会让编排层重启一个其实正常工作的实例。
 *
 * <p><b>输出可以被 Prometheus 抓取器直接读取</b>（扁平的一层 {@code counters} / {@code gauges}），
 * 但本端点不依赖任何指标框架：第一版只需要四个数——对局推进、事件写入、模型成本、降级次数。
 */
@RestController
@RequestMapping(MetricsController.BASE_PATH)
class MetricsController {

    static final String BASE_PATH = "/api/v1";

    private static final Logger LOG = LoggerFactory.getLogger(MetricsController.class);

    private final ObjectProvider<MetricsPort> metrics;
    private final ObjectProvider<LlmCostGuard> costGuard;

    MetricsController(ObjectProvider<MetricsPort> metrics, ObjectProvider<LlmCostGuard> costGuard) {
        this.metrics = metrics;
        this.costGuard = costGuard;
    }

    @GetMapping("/metrics")
    Map<String, Object> metrics() {
        MetricsPort port = metrics.getIfAvailable();
        if (port == null) {
            LOG.debug("该部署未装配 MetricsPort，/metrics 返回空快照");
            port = MetricsPort.NOOP;
        }
        MetricsPort.Snapshot snapshot = port.snapshot();
        long now = System.currentTimeMillis();

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("since", snapshot.sinceEpochMillis());
        body.put("uptimeMillis", Math.max(0L, now - snapshot.sinceEpochMillis()));
        body.put("counters", snapshot.counters());
        body.put("gauges", snapshot.gauges());
        Map<String, Object> timers = new LinkedHashMap<>();
        snapshot.timers().forEach((name, timer) -> {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("count", timer.count());
            item.put("totalMs", timer.totalMs());
            item.put("maxMs", timer.maxMs());
            item.put("averageMs", timer.averageMs());
            timers.put(name, item);
        });
        body.put("timers", timers);
        LlmCostGuard guard = costGuard.getIfAvailable();
        body.put("llmBudget", guard == null ? Map.of("note", "未装配成本守卫") : guard.report());
        body.put("llmCost", llmCost(snapshot));
        return body;
    }

    /**
     * token 成本的运维视图。
     *
     * <p><b>不换算成货币。</b>价格随供应商、模型、缓存与批量折扣变化，把它写死在代码里，
     * 一个季度后就会显示一个错误金额——而错误金额会被当成事实引用。这里只给 token 数，
     * 换算交给运维侧的报价单。
     */
    private static Map<String, Object> llmCost(MetricsPort.Snapshot snapshot) {
        long prompt = snapshot.counters()
                .getOrDefault(MetricsPort.Names.LLM_PROMPT_TOKENS, 0L);
        long completion = snapshot.counters()
                .getOrDefault(MetricsPort.Names.LLM_COMPLETION_TOKENS, 0L);
        long calls = snapshot.counters().getOrDefault(MetricsPort.Names.LLM_CALLS, 0L);
        Map<String, Object> cost = new LinkedHashMap<>();
        cost.put("calls", calls);
        cost.put("promptTokens", prompt);
        cost.put("completionTokens", completion);
        cost.put("totalTokens", prompt + completion);
        cost.put("averageTokensPerCall", calls == 0 ? null : Math.round((double) (prompt + completion) / calls));
        cost.put("failures", snapshot.counters().getOrDefault(MetricsPort.Names.LLM_FAILURES, 0L));
        cost.put("budgetRejections",
                snapshot.counters().getOrDefault(MetricsPort.Names.LLM_BUDGET_REJECTIONS, 0L));
        cost.put("note", "只统计 token，不换算金额：价格随供应商与模型变化，写死在代码里会变成错误的事实。");
        return cost;
    }

    /** 指标名目录；便于运维在面板里对齐名字，避免"打错一个字母少一条线"。 */
    @GetMapping("/metrics/names")
    Map<String, Object> names() {
        List<String> all = new ArrayList<>();
        all.add(MetricsPort.Names.EVENTS_APPENDED);
        all.add(MetricsPort.Names.GAMES_FINISHED);
        all.add(MetricsPort.Names.GAMES_ABORTED);
        all.add(MetricsPort.Names.TURNS_FINISHED);
        all.add(MetricsPort.Names.TURNS_FALLBACK);
        all.add(MetricsPort.Names.LLM_CALLS);
        all.add(MetricsPort.Names.LLM_FAILURES);
        all.add(MetricsPort.Names.LLM_PROMPT_TOKENS);
        all.add(MetricsPort.Names.LLM_COMPLETION_TOKENS);
        all.add(MetricsPort.Names.LLM_LATENCY);
        all.add(MetricsPort.Names.LLM_BUDGET_REJECTIONS);
        all.add(MetricsPort.Names.LLM_BUDGET_BLOCKED_SEATS);
        all.add(MetricsPort.Names.ARCHIVE_PENDING_GAMES);
        all.add(MetricsPort.Names.ARCHIVE_FAILED_GAMES);
        all.add(MetricsPort.Names.ARCHIVE_FAILURES);
        for(String group:List.of("cg-projection","cg-broadcast","cg-insight"))
            for(String suffix:List.of("pending","blocked","failures","read.failures"))
                all.add("wolfmind.consumer."+group+"."+suffix);
        all.add("wolfmind.consumer.cg-insight.repair.failures");
        all.addAll(List.of("wolfmind.agent.turn.unregistered", "wolfmind.agent.trace.live-events",
                "wolfmind.agent.trace.finished-games", "wolfmind.agent.trace.evicted-games",
                "wolfmind.agent.trace.archive.failures", "wolfmind.agent.trace.archive.pending-games",
                "wolfmind.agent.trace.archive.dropped-games"));
        return Map.of("name", all);
    }
}
