package com.wolfmind.application.agent.strategy;

import com.wolfmind.application.agent.model.AgentConfig;
import com.wolfmind.application.agent.model.AgentView;
import com.wolfmind.application.agent.model.Candidate;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 候选生成器：按角色与阶段选择 {@link RoleStrategy}，产出**未经打分**的候选集
 * （strategy-layer.md §2 的数据流：{@code CandidateGenerator → DecisionPolicy}）。
 *
 * <p>这里只做两件事：选策略、把材料打包成 {@link RoleContext}。合法性的最终边界在各自的
 * 角色策略里，因为它们才知道"这个角色的目标约束是什么"。角色未知或阶段为空时返回空列表：
 * 空候选集是合法结果，由 {@code FallbackPlanner} 转成 {@code SKIP}（§9）。
 */
public final class CandidateGenerator {

    private final Map<String, RoleStrategy> strategies = new LinkedHashMap<>();
    private final ThreatModel threatModel;
    private final SuspicionModel suspicionModel;

    /** 默认装配：§5 的五个角色各一个策略。 */
    public CandidateGenerator(ThreatModel threatModel, SuspicionModel suspicionModel) {
        this(List.of(
                new WerewolfRoleStrategy(),
                new SeerRoleStrategy(),
                new WitchRoleStrategy(),
                new HunterRoleStrategy(),
                new VillagerRoleStrategy()), threatModel, suspicionModel);
    }

    public CandidateGenerator(
            List<RoleStrategy> roleStrategies, ThreatModel threatModel, SuspicionModel suspicionModel) {
        for (RoleStrategy strategy : roleStrategies) {
            strategies.put(StrategyCodes.normalize(strategy.role()), strategy);
        }
        this.threatModel = threatModel;
        this.suspicionModel = suspicionModel;
    }

    /**
     * 生成候选集。
     *
     * @param role 以调用方传入的角色为准（来自 {@code TurnRequest}）；为空时回落到视图里的自己
     */
    public List<Candidate> generate(
            AgentView view, String role, String phase, AgentConfig config, FactStore facts) {
        if (view == null || facts == null) {
            return List.of();
        }
        RoleStrategy strategy = strategies.get(effectiveRole(view, role));
        if (strategy == null) {
            return List.of();
        }
        RoleContext context = new RoleContext(
                view, StrategyCodes.normalize(phase), config, facts, threatModel, suspicionModel);
        List<Candidate> candidates = strategy.generate(context);
        return candidates == null ? List.of() : new ArrayList<>(candidates);
    }

    /** 某角色某阶段的硬约束清单（进 prompt 的 SYSTEM 分区）。 */
    public List<String> hardConstraints(AgentView view, String role, String phase) {
        RoleStrategy strategy = strategies.get(effectiveRole(view, role));
        return strategy == null ? List.of() : strategy.hardConstraints(view, StrategyCodes.normalize(phase));
    }

    /** 已注册的角色策略，供装配与测试断言"五个角色都有实现"（§5）。 */
    public List<String> supportedRoles() {
        return List.copyOf(strategies.keySet());
    }

    private static String effectiveRole(AgentView view, String role) {
        String normalized = StrategyCodes.normalize(role);
        if (!normalized.isEmpty()) {
            return normalized;
        }
        return view != null && view.me() != null ? StrategyCodes.normalize(view.me().role()) : "";
    }
}
