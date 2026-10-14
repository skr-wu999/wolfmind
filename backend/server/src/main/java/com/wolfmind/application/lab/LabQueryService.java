package com.wolfmind.application.lab;

import com.wolfmind.application.agent.trace.AgentTracePort;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 实验室读用例（api-spec.md §8）：配置目录、批次进度、比对表。
 *
 * <p><b>比对表的分母是"已结束的对局"，不是"计划的对局"。</b>每一行都带 {@code games}，
 * 并显式给出 {@code pending}：一个跑了 3/50 局的配置与一个跑了 50/50 局的配置，
 * 胜率数字看起来一样，含义完全不同。
 *
 * <p><b>token 与耗时来自决策轨迹</b>（{@code AGENT_LLM_INVOKED}），与 AI 分析页同源。
 * 规则基线座位没有这类轨迹，因此在比对表里它们的 token 为 0 而不是缺失——
 * 0 正确地表示"这份配置没有花钱"。
 */
public final class LabQueryService {

    private final LabStorePort store;
    private final AgentTracePort traces;
    private final com.wolfmind.application.agent.llm.LlmSettings llmSettings;

    public LabQueryService(
            LabStorePort store,
            AgentTracePort traces,
            com.wolfmind.application.agent.llm.LlmSettings llmSettings) {
        this.store = Objects.requireNonNull(store, "store");
        this.traces = Objects.requireNonNull(traces, "traces");
        this.llmSettings = Objects.requireNonNull(llmSettings, "llmSettings");
    }

    /**
     * 配置目录 + 最近一个批次。
     *
     * <p>两者都按调用者账号过滤：实验配置含模型、温度与人格，批次含"我正在跑什么"，
     * 都不是公共资产。
     */
    public Map<String, Object> catalog(String ownerId, int page, int pageSize) {
        List<LabAgentConfig> configs = store.configs(ownerId, page, pageSize);
        List<LabBatch> batches = store.batches(ownerId, 1, 20);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("items", configs);
        body.put("page", page);
        body.put("pageSize", pageSize);
        body.put("total", store.configs(ownerId, 1, 1000).size());
        body.put("batches", batches);
        body.put("latestBatchId", batches.isEmpty() ? null : batches.get(0).batchId());
        body.put("models", knownModels(configs));
        return body;
    }

    /**
     * 表单可选的模型清单。
     *
     * <p><b>它不是"可用模型目录"，而是"服务端已经见过的模型"</b>：供应商配置里的默认模型，
     * 加上这个账号已保存配置里用过的模型。刻意不去问供应商"你有哪些模型"——
     * 那需要一次网络调用与一份会过期的缓存，而实验室要的只是"我上次用的那个还在不在列表里"。
     */
    private List<Map<String, Object>> knownModels(List<LabAgentConfig> configs) {
        java.util.LinkedHashMap<String, Map<String, Object>> models = new java.util.LinkedHashMap<>();
        if (llmSettings.configured()) {
            models.put(llmSettings.defaultModel(), Map.of(
                    "id", llmSettings.defaultModel(),
                    "name", llmSettings.defaultModel() + "（服务端默认）",
                    "description", "由环境变量 WOLFMIND_LLM_* 配置，是未指定模型时的兜底。"));
        }
        for (LabAgentConfig config : store.configs(null, 1, 1000)) {
            if (config.model() == null || config.model().isBlank()) {
                continue;
            }
            models.putIfAbsent(config.model(), Map.of(
                    "id", config.model(),
                    "name", config.model(),
                    "description", "来自已保存的 Agent 配置。"));
        }
        return List.copyOf(models.values());
    }

    /**
     * 批次详情。
     *
     * @param ownerId 调用者账号；不是批次所有者时按"不存在"处理，避免用 404/403 的差别
     *                去探测别人的批次 id 是否存在
     */
    public LabBatch batch(String ownerId, String batchId) {
        LabBatch batch = store.batch(batchId)
                .orElseThrow(() -> new LabNotFoundException("批次不存在：" + batchId));
        if (batch.ownerId() != null && !batch.ownerId().equals(ownerId)) {
            throw new LabNotFoundException("批次不存在：" + batchId);
        }
        return batch;
    }

    /**
     * 按配置与角色汇总比对结果。
     *
     * @param agentConfigIds 只比对这几份配置；为空表示该账号目录里的全部
     * @param role           只比对这个角色；{@code null} 表示不限
     */
    public Map<String, Object> comparisons(String ownerId, List<String> agentConfigIds, String role) {
        Map<String, LabAgentConfig> configs = new LinkedHashMap<>();
        for (LabAgentConfig config : store.configs(ownerId, 1, 1000)) {
            if (agentConfigIds.isEmpty() || agentConfigIds.contains(config.agentConfigId())) {
                configs.put(config.agentConfigId(), config);
            }
        }
        Map<String, Aggregate> aggregates = new LinkedHashMap<>();

        for (LabBatch batch : store.batches(ownerId, 1, 200)) {
            for (LabBatch.GameRef game : batch.games()) {
                if (!LabBatch.GAME_FINISHED.equals(game.status())) {
                    continue;
                }
                for (Map.Entry<String, LabBatch.AssignedConfig> entry : game.roleAssignments().entrySet()) {
                    String roleName = entry.getKey();
                    LabBatch.AssignedConfig assigned = entry.getValue();
                    if (!configs.containsKey(assigned.agentConfigId())) {
                        continue;
                    }
                    if (role != null && !role.isBlank() && !role.equals(roleName)) {
                        continue;
                    }
                    Aggregate aggregate = aggregates.computeIfAbsent(
                            assigned.agentConfigId() + "|" + roleName,
                            key -> new Aggregate(assigned.agentConfigId(), roleName, assigned.camp()));
                    aggregate.games++;
                    aggregate.roundsTotal += game.rounds() == null ? 0 : game.rounds();
                    if (aggregate.camp.equals(game.winner())) {
                        aggregate.wins++;
                    }
                    accumulateCost(aggregate, game.gameId(), assigned.seatNo());
                }
            }
        }

        List<Map<String, Object>> rows = new ArrayList<>();
        for (Aggregate aggregate : aggregates.values()) {
            LabAgentConfig config = configs.get(aggregate.agentConfigId);
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("agentConfigId", aggregate.agentConfigId);
            row.put("name", config == null ? aggregate.agentConfigId : config.name());
            row.put("model", config == null ? null : config.model());
            row.put("strategyProfile", config == null ? null : config.strategyProfile());
            row.put("role", aggregate.role);
            row.put("camp", aggregate.camp);
            row.put("games", aggregate.games);
            row.put("wins", aggregate.wins);
            row.put("winRate", aggregate.games == 0
                    ? null : Math.round(((double) aggregate.wins / aggregate.games) * 10_000d) / 10_000d);
            row.put("averageRounds", aggregate.games == 0
                    ? null : Math.round(((double) aggregate.roundsTotal / aggregate.games) * 100d) / 100d);
            row.put("llmCalls", aggregate.llmCalls);
            row.put("tokens", aggregate.tokens);
            row.put("averageLlmLatencyMs", aggregate.llmCalls == 0
                    ? null : Math.round((double) aggregate.latencyTotal / aggregate.llmCalls));
            row.put("fallbackTurns", aggregate.fallbackTurns);
            rows.add(row);
        }
        rows.sort((left, right) -> {
            int byRole = String.valueOf(left.get("role")).compareTo(String.valueOf(right.get("role")));
            return byRole != 0 ? byRole : String.valueOf(left.get("agentConfigId"))
                    .compareTo(String.valueOf(right.get("agentConfigId")));
        });

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("rows", rows);
        body.put("provenance",
                "只统计已结束的对局；胜率 = 该配置所在阵营获胜的局数 / 该配置参与且已结束的局数。"
                        + "token 与耗时来自 AGENT_LLM_INVOKED 决策轨迹，规则基线座位为 0。"
                        + "样本量小于 20 局时差异不具统计意义。");
        return body;
    }

    private void accumulateCost(Aggregate aggregate, String gameId, int seatNo) {
        String playerId = "p_" + seatNo;
        for (AgentTracePort.AgentTraceEvent trace : traces.traces(gameId, playerId)) {
            if (trace.type() == AgentTracePort.TraceEventType.AGENT_LLM_INVOKED) {
                aggregate.llmCalls++;
                aggregate.tokens += intOf(trace.payload().get("promptTokens"))
                        + intOf(trace.payload().get("completionTokens"));
                aggregate.latencyTotal += intOf(trace.payload().get("latencyMs"));
            } else if (trace.type() == AgentTracePort.TraceEventType.AGENT_TURN_FINISHED
                    && Boolean.TRUE.equals(trace.payload().get("fallbackUsed"))) {
                aggregate.fallbackTurns++;
            }
        }
    }

    private static int intOf(Object value) {
        return value instanceof Number number ? number.intValue() : 0;
    }

    private static final class Aggregate {
        private final String agentConfigId;
        private final String role;
        private final String camp;
        private int games;
        private int wins;
        private int roundsTotal;
        private int llmCalls;
        private long tokens;
        private long latencyTotal;
        private int fallbackTurns;

        private Aggregate(String agentConfigId, String role, String camp) {
            this.agentConfigId = agentConfigId;
            this.role = role;
            this.camp = camp;
        }
    }

    /** 批次不存在。接口层映射为 404。 */
    public static final class LabNotFoundException extends RuntimeException {

        private static final long serialVersionUID = 1L;

        public LabNotFoundException(String message) {
            super(message);
        }
    }
}
