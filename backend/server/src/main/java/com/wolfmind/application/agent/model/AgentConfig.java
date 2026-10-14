package com.wolfmind.application.agent.model;

import java.util.List;

/**
 * 一个 AI 座位的完整配置（AI 实验室的配置对象，strategy-layer.md §12）。
 *
 * <p><b>这是接入真实 LLM 的唯一参数入口。</b>模型名、API key、baseUrl、温度、档位、人格全部
 * 从这里进入系统，代码里不硬编码任何模型参数。载入方式由接口层决定（后述配置文件或 Spring 绑定），
 * 本记录只负责"参数长什么样"。
 *
 * <p>约束：同一局内同一座位的 {@code AgentConfig} 不变，保证对局可复现
 * （agent-runtime.md §13）。运行时修改配置属于实验室的"新对局"语义，不是"改这一局"。
 *
 * @param agentConfigId   配置标识（实验室与归档用）
 * @param name            配置名，便于比对
 * @param model           模型标识，例如 {@code "openai/gpt-4o-mini"}、{@code "deepseek-chat"}；
 *                        由 {@code LlmClient} 的实现解释，领域与策略层不解析它
 * @param temperature     主决策温度（{@code strategy.temperature.decide}，默认 0.6）
 * @param repairTemperature 修复/重写温度（{@code strategy.temperature.repair}，默认 0.2）
 * @param strategyProfile 打分权重档位：{@code AGGRESSIVE} / {@code BALANCED} / {@code CONSERVATIVE}
 * @param persona         语气与人格，只影响表达，不影响约束
 * @param mode            工作流模式：{@code A} 单次调用（默认）或 {@code B} 两阶段
 * @param summarizeThreshold 触发时间线压缩的公开事件数（{@code strategy.summarizeThreshold}）
 * @param exposureAversion 狼队暴露规避强度（{@code strategy.exposureAversion}）
 * @param memoryRecallLimit 长期记忆召回条数上限
 * @param candidateLimit  候选集大小上限（{@code strategy.candidateLimit}）
 * @param promptTemplateId 提示词模板标识（实验室比对维度）
 * @param budget          本座位的回合预算
 */
public record AgentConfig(
        String agentConfigId,
        String name,
        String model,
        double temperature,
        double repairTemperature,
        StrategyProfile strategyProfile,
        String persona,
        WorkflowMode mode,
        int summarizeThreshold,
        double exposureAversion,
        int memoryRecallLimit,
        int candidateLimit,
        String promptTemplateId,
        TurnBudget budget) {

    /** 打分权重档位（strategy-layer.md §4.3 默认三档）。 */
    public enum StrategyProfile {
        AGGRESSIVE,
        BALANCED,
        CONSERVATIVE
    }

    /** 工作流 LLM 调用模式（strategy-layer.md §6.1）。 */
    public enum WorkflowMode {
        /** 直接生成行动或发言提案；查询与修复仍受总预算约束。默认。 */
        A,
        /** 发言回合先制定内部计划，再生成公开表达；其他行动回合沿用 A。 */
        B
    }

    public AgentConfig {
        if (agentConfigId == null || agentConfigId.isBlank()) {
            throw new IllegalArgumentException("agentConfigId 不能为空");
        }
        if (model == null || model.isBlank()) {
            // 不在这里拒绝：纯规则基线（strategy.profile 走兜底、不调用 LLM）可以没有模型名。
            // 但它必须在真正调用 LLM 之前被 LlmClient 的实现拒绝，错误信息要指向配置而不是运行期。
            model = "";
        }
        if (name == null) {
            name = agentConfigId;
        }
        if (strategyProfile == null) {
            strategyProfile = StrategyProfile.BALANCED;
        }
        if (mode == null) {
            mode = WorkflowMode.A;
        }
        if (persona == null) {
            persona = "中性、简洁、不主动暴露身份";
        }
        if (budget == null) {
            budget = TurnBudget.DEFAULT;
        }
        if (temperature < 0 || temperature > 2) {
            throw new IllegalArgumentException("temperature 必须在 0..2 之间，实际为 " + temperature);
        }
        if (repairTemperature < 0 || repairTemperature > 2) {
            throw new IllegalArgumentException("repairTemperature 必须在 0..2 之间");
        }
        if (candidateLimit < 1) {
            throw new IllegalArgumentException("candidateLimit 必须 ≥ 1");
        }
        if (memoryRecallLimit < 0) {
            throw new IllegalArgumentException("memoryRecallLimit 不能为负");
        }
        if (summarizeThreshold < 0) {
            throw new IllegalArgumentException("summarizeThreshold 不能为负");
        }
    }

    /**
     * 纯规则基线配置：不调用 LLM，全部走 {@code FallbackPlanner}。
     *
     * <p>它的用途是"LLM 完全不可用时 9 个 AI 仍能跑完一局"（agent-runtime.md §10 最后一行）与
     * 实验室的规则基线对照。测试大量使用它，因为它让整条链路可离线、可复现。
     */
    public static AgentConfig ruleBaseline(String agentConfigId) {
        return new AgentConfig(
                agentConfigId,
                "rule-baseline",
                "",
                0.0,
                0.0,
                StrategyProfile.BALANCED,
                "纯规则基线",
                WorkflowMode.A,
                60,
                0.5,
                8,
                3,
                "none",
                TurnBudget.DEFAULT);
    }

    /** 是否允许调用 LLM；纯规则基线返回 false。 */
    public boolean usesLlm() {
        return !model.isBlank();
    }

    public AgentConfig withWorkflowMode(WorkflowMode value) {
        return new AgentConfig(agentConfigId, name, model, temperature, repairTemperature, strategyProfile,
                persona, value, summarizeThreshold, exposureAversion, memoryRecallLimit, candidateLimit,
                promptTemplateId, budget);
    }

    /** 是否启用规则兜底（{@code agent.fallback.enabled}）。 */
    public boolean fallbackEnabled() {
        return true;
    }

    /** 便于日志与 Trace：不包含任何密钥。 */
    public List<String> describeForTrace() {
        return List.of(
                "agentConfigId=" + agentConfigId,
                "model=" + (model.isBlank() ? "(none)" : model),
                "profile=" + strategyProfile,
                "mode=" + mode);
    }
}
