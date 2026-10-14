package com.wolfmind.application.agent.strategy;

import com.wolfmind.application.agent.model.ActionProposal;
import com.wolfmind.application.agent.model.AgentConfig;
import com.wolfmind.application.agent.model.AgentContext;
import com.wolfmind.application.agent.model.AgentView;
import com.wolfmind.application.agent.model.Candidate;
import com.wolfmind.application.agent.tool.ToolGatewayPort;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 策略层默认实现（strategy-layer.md 全部 13 节）。
 *
 * <p>它是一个**装配点**而不是逻辑仓库：候选生成、打分、校验、防泄漏、兜底、上下文装配
 * 各自在独立组件里，本类只负责按数据流把它们串起来，并把"同源"要求落到实处：
 * <ul>
 *   <li>动作白名单来自 {@link ToolGatewayPort#allowedActionTypes}（§7 约束 1：prompt 里的
 *       硬约束与网关校验必须同源），候选集再按这份白名单过滤一次，避免"提示词说可以、
 *       网关说不行"；</li>
 *   <li>工具白名单来自 {@link ToolGatewayPort#availableTools}，只进 prompt；</li>
 *   <li>没有任何写路径：本类不持有仓储、事件流、Redis/SQLite 客户端，只读视图与端口的方法。</li>
 * </ul>
 *
 * <p>构造函数的端口**允许为 null**，此时回退到 {@link DefensiveActionMatrix}。这只是防御：
 * 离线单测与"纯规则基线"不该被迫拖一个假网关进来。生产装配必须注入真实端口，
 * 否则候选集将基于一份离线矩阵，而不是网关的权限判定。
 */
public final class WolfMindStrategy implements StrategyPort {

    /** 工具网关；为 {@code null} 时使用内置兜底矩阵（见类注释）。 */
    private final ToolGatewayPort toolGateway;

    private final FactExtractor factExtractor = new FactExtractor();
    private final ThreatModel threatModel = new ThreatModel();
    private final SuspicionModel suspicionModel = new SuspicionModel();
    private final CandidateGenerator candidateGenerator = new CandidateGenerator(threatModel, suspicionModel);
    private final DecisionPolicy decisionPolicy = new DecisionPolicy(threatModel);
    private final OutputValidator outputValidator = new OutputValidator();
    private final LeakGuard leakGuard = new LeakGuard();
    private final EchoGuard echoGuard = new EchoGuard();
    private final FallbackPlanner fallbackPlanner = new FallbackPlanner();
    private final ContextAssembler contextAssembler = new ContextAssembler();

    public WolfMindStrategy(ToolGatewayPort toolGateway) {
        this.toolGateway = toolGateway;
    }

    /** 无网关装配：仅用于离线单测与纯规则基线（候选集基于内置兜底矩阵）。 */
    public WolfMindStrategy() {
        this(null);
    }

    @Override
    public List<Candidate> candidates(AgentView view, String role, String phase, AgentConfig config) {
        if (view == null) {
            return List.of();
        }
        FactStore facts = factExtractor.extractStore(view);
        String effectiveRole = effectiveRole(view, role);
        List<Candidate> generated = candidateGenerator.generate(view, effectiveRole, phase, config, facts);
        if (generated.isEmpty()) {
            // 空候选集是合法结果（StrategyPort 的契约）：交给 FallbackPlanner 产出 SKIP
            return List.of();
        }
        List<Candidate> legal = filterByAllowedActions(generated, effectiveRole, phase);
        return decisionPolicy.score(view, effectiveRole, config, legal, facts);
    }

    @Override
    public List<String> allowedActions(String role, String phase) {
        if (toolGateway != null) {
            List<String> fromGateway = toolGateway.allowedActionTypes(role, phase);
            return fromGateway == null ? List.of() : List.copyOf(fromGateway);
        }
        return DefensiveActionMatrix.actionTypes(role, phase);
    }

    /** 本角色本阶段可用的工具白名单：与 {@link #allowedActions} 同源，只用于 prompt。 */
    public List<String> availableTools(String role, String phase) {
        if (toolGateway != null) {
            List<String> fromGateway = toolGateway.availableTools(role, phase);
            return fromGateway == null ? List.of() : List.copyOf(fromGateway);
        }
        return DefensiveActionMatrix.toolNames(role, phase);
    }

    @Override
    public ValidationResult validate(String raw, List<Candidate> candidates, AgentView view, AgentConfig config) {
        List<String> allowed = view == null || view.me() == null
                ? List.of()
                : allowedActions(effectiveRole(view, view.me().role()), view.phase());
        return outputValidator.validate(raw, candidates, view, config, allowed);
    }

    @Override
    public LeakCheckResult leakCheck(String text, AgentView view) {
        if (view == null) {
            return LeakCheckResult.clean();
        }
        return leakGuard.check(text, view, factExtractor.extractStore(view));
    }

    @Override
    public EchoCheckResult echoCheck(String text, AgentView view) {
        return echoGuard.check(text, view);
    }

    @Override
    public ActionProposal fallback(List<Candidate> candidates, AgentView view, String role, String phase) {
        FactStore facts = view == null ? null : factExtractor.extractStore(view);
        Integer topSuspect = facts == null ? null : suspicionModel.topSuspectSeat(view, facts).orElse(null);
        return fallbackPlanner.plan(candidates, view, role, phase, topSuspect);
    }

    @Override
    public AgentContext assembleContext(
            AgentView view, List<Candidate> candidates, AgentConfig config, String memory) {
        if (view == null) {
            return contextAssembler.assemble(
                    null, candidates, config, memory, null, List.of(), List.of(), List.of());
        }
        FactStore facts = factExtractor.extractStore(view);
        String role = effectiveRole(view, view.me() == null ? null : view.me().role());
        String phase = view.phase();
        return contextAssembler.assemble(
                view,
                candidates,
                config,
                memory,
                facts,
                allowedActions(role, phase),
                availableTools(role, phase),
                candidateGenerator.hardConstraints(view, role, phase));
    }

    /**
     * 安全模板发言：{@code leakCheck} 重写一次仍违规时的最后手段（§8.2 末段）。
     *
     * <p>它不是 {@code StrategyPort} 的一部分，但工作流的 {@code rewrite} 失败分支需要它，
     * 因此作为附加 API 暴露；不改变冻结接口的任何签名。
     */
    public String safeSpeech(AgentView view) {
        return fallbackPlanner.safeSpeech(view);
    }

    /**
     * Trace 专用观察：发言里提到、但不在候选集里的座位（§8.2 末条"额外承诺允许，但记录到 Trace"）。
     *
     * <p>它不影响任何校验结果，只把"模型说了候选之外的话"这件事留下痕。
     */
    public List<String> traceOnlyObservations(String text, List<Candidate> candidates) {
        if (text == null || text.isBlank()) {
            return List.of();
        }
        Set<Integer> candidateSeats = new LinkedHashSet<>();
        if (candidates != null) {
            for (Candidate candidate : candidates) {
                if (candidate.hasTarget()) {
                    candidateSeats.add(candidate.targetSeatNo());
                }
            }
        }
        List<String> observations = new ArrayList<>();
        for (int seatNo : FactExtractor.mentions(text, null)) {
            if (!candidateSeats.contains(seatNo)) {
                observations.add("发言提到座位 " + seatNo + "，不在本回合候选集内（允许，仅记录）");
            }
        }
        return observations;
    }

    /**
     * 候选集按网关白名单过滤（§7 约束 1 的落地）。
     *
     * <p>唯一的宽容：白名单里一个已知动作类型都没有时不过滤。那说明端口返回的是别的词汇
     * （例如把工具名当动作类型返回），此时过滤会把所有候选清空，让每个 AI 回合都退化成 SKIP；
     * 宁可让网关在提交时给出一次结构化拒绝（tool-gateway.md §5），也不要静默地把整局变成空转。
     */
    private List<Candidate> filterByAllowedActions(List<Candidate> candidates, String role, String phase) {
        List<String> allowed = allowedActions(role, phase);
        Set<String> allowedSet = new LinkedHashSet<>();
        for (String action : allowed) {
            allowedSet.add(StrategyCodes.normalize(action));
        }
        boolean vocabularyMatches = allowedSet.stream().anyMatch(WolfMindStrategy::isKnownActionType);
        if (!vocabularyMatches) {
            return candidates;
        }
        List<Candidate> filtered = new ArrayList<>();
        for (Candidate candidate : candidates) {
            if (allowedSet.contains(StrategyCodes.normalize(candidate.actionType()))) {
                filtered.add(candidate);
            }
        }
        return filtered;
    }

    /** 策略层认识的动作类型全集；用于判断网关返回的是动作类型还是别的词汇。 */
    private static final Set<String> KNOWN_ACTION_TYPES = Set.of(
            StrategyCodes.SPEAK,
            StrategyCodes.VOTE,
            StrategyCodes.WOLF_KILL,
            StrategyCodes.SEER_INSPECT,
            StrategyCodes.WITCH_SAVE,
            StrategyCodes.WITCH_POISON,
            StrategyCodes.WITCH_PASS,
            StrategyCodes.HUNTER_SHOOT,
            StrategyCodes.SKIP);

    private static boolean isKnownActionType(String actionType) {
        return KNOWN_ACTION_TYPES.contains(actionType);
    }

    private static String effectiveRole(AgentView view, String role) {
        String normalized = StrategyCodes.normalize(role);
        if (!normalized.isEmpty()) {
            return normalized;
        }
        return view != null && view.me() != null ? StrategyCodes.normalize(view.me().role()) : "";
    }
}
