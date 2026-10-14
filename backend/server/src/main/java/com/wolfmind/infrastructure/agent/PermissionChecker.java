package com.wolfmind.infrastructure.agent;

import com.wolfmind.application.agent.model.AgentView;
import com.wolfmind.application.agent.model.Candidate;
import com.wolfmind.application.agent.model.ToolSession;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 校验流水线 G1..G11（tool-gateway.md §4）：<b>顺序固定，任一步失败立即返回结构化拒绝</b>。
 *
 * <p>G12（幂等）与 G13（提交）需要 I/O，放在 {@link ToolGatewayAdapter}，因为"不产生任何事件"
 * 这条对它们的约束形式不同：G12 是"返回首次结果、不新增事件"，G13 是"版本冲突刷新重试一次"。
 *
 * <p>各步与失败码：
 * <table>
 *   <tr><td>G1</td><td>工具名白名单</td><td>{@code TOOL_UNKNOWN}</td></tr>
 *   <tr><td>G2</td><td>参数 Schema</td><td>{@code TOOL_ARGS_INVALID}（身份字段为
 *       {@code TOOL_IDENTITY_MISMATCH}）</td></tr>
 *   <tr><td>G3</td><td>身份绑定</td><td>{@code TOOL_IDENTITY_MISMATCH}</td></tr>
 *   <tr><td>G4</td><td>对局 RUNNING</td><td>{@code GAME_ALREADY_FINISHED}</td></tr>
 *   <tr><td>G5</td><td>阶段允许该工具</td><td>{@code ACTION_NOT_ALLOWED}</td></tr>
 *   <tr><td>G6</td><td>回合窗口</td><td>{@code TURN_NOT_YOURS}</td></tr>
 *   <tr><td>G7</td><td>角色具备该能力</td><td>{@code ACTION_NOT_ALLOWED}</td></tr>
 *   <tr><td>G8</td><td>次数门</td><td>{@code ACTION_NOT_ALLOWED}</td></tr>
 *   <tr><td>G9</td><td>目标门</td><td>{@code ACTION_INVALID_TARGET}</td></tr>
 *   <tr><td>G10</td><td>资源门</td><td>{@code ACTION_NOT_ALLOWED}</td></tr>
 *   <tr><td>G11</td><td>回合预算</td><td>{@code BUDGET_EXCEEDED}</td></tr>
 * </table>
 *
 * <p>网关不含规则（§9）：这里只判断"这个工具在这个阶段对这个角色是否开放"、"这个目标是否
 * 在合法候选集内"。票数、结算、胜负、弃权是否允许、空刀是否允许一律留给领域
 * （{@code ActionValidator} / 各 Policy）。
 *
 * <p>两处刻意的裁决（文档未写，已在交付说明里报告）：
 * <ol>
 *   <li>只读工具不受 G6 约束：它们不产生事件、不改变状态，"等待回合时看一眼"不应非法；</li>
 *   <li>G11 的计数在网关侧独立记一份（{@link TurnBudgetLedger}），因为会话记录的余额
 *       无法在调用后被扣减；两个上限取"都要满足"而不是取小值，避免出现"给大了就绕过"。</li>
 * </ol>
 */
public final class PermissionChecker {

    /** G1..G11 的结论。 */
    public record Outcome(Denial denial, Integer resolvedTargetSeat, List<Candidate> alternatives) {

        public Outcome {
            alternatives = List.copyOf(alternatives);
        }

        public boolean allowed() {
            return denial == null;
        }

        static Outcome pass(Integer target, List<Candidate> alternatives) {
            return new Outcome(null, target, alternatives);
        }

        static Outcome reject(Denial denial) {
            return new Outcome(denial, null, denial.alternatives());
        }
    }

    private final ToolGatewayConfig config;
    private final TurnBudgetLedger ledger;

    public PermissionChecker(ToolGatewayConfig config, TurnBudgetLedger ledger) {
        this.config = config == null ? ToolGatewayConfig.DEFAULT : config;
        this.ledger = ledger == null ? new TurnBudgetLedger() : ledger;
    }

    /**
     * 执行 G1..G11。
     *
     * @param declaredActionType 模型/策略层声明的动作类型；只读工具传 {@code null}
     * @param args               模型可见参数（{@link ToolCatalog#modelArgs}）
     * @param view               会话座位的当前可见视图；{@code null} 表示身份无法确认
     */
    public Outcome check(
            ToolSession session,
            String toolName,
            String declaredActionType,
            Map<String, Object> args,
            AgentView view,
            long nowMillis) {

        // G1 工具名白名单
        if (!ToolCatalog.isRegistered(toolName)) {
            return Outcome.reject(Denial.of(DenialCodes.TOOL_UNKNOWN,
                    "未注册的工具 " + toolName + "，可用工具：" + String.join(", ", ToolCatalog.TOOLS)));
        }

        String effectiveAction = ToolCatalog.effectiveActionType(
                declaredActionType, args != null && args.containsKey("content"));

        // G2 参数 Schema（含"参数里夹带身份字段"的扫描，它按 G3 的语义返回身份不匹配）
        int speechMaxLength = view == null
                ? ToolGatewayConfig.DEFAULT_SPEECH_MAX_LENGTH
                : view.ruleInt("speechMaxLength", ToolGatewayConfig.DEFAULT_SPEECH_MAX_LENGTH);
        ToolArgsValidator.Outcome argsOutcome = ToolArgsValidator.validate(
                toolName, args, effectiveAction, ToolArgsValidator.Limits.of(speechMaxLength, config));
        if (!argsOutcome.ok()) {
            return Outcome.reject(Denial.of(argsOutcome.code(), argsOutcome.message()));
        }

        // G3 身份绑定：会话 ↔ 视图；会话身份之外的任何来源都不参与授权
        var binding = IdentityGuard.bind(session, view);
        if (binding.isPresent()) {
            return Outcome.reject(Denial.of(binding.get().code(), binding.get().message()));
        }
        // 目标提示：notes 里提到"本回合目标"不算伪造身份，因此要把目标身份一并告诉守卫
        Integer declaredTargetSeat = intArg(args, "targetSeatNo");
        String declaredTargetPlayerId = stringArg(args, "targetId");
        if (declaredTargetPlayerId == null && declaredTargetSeat != null) {
            declaredTargetPlayerId = playerIdOfSeat(view, declaredTargetSeat);
        }
        var claimed = IdentityGuard.claimedIdentity(
                stringArg(args, "notes"), session, declaredTargetPlayerId, declaredTargetSeat);
        if (claimed.isPresent()) {
            return Outcome.reject(Denial.of(claimed.get().code(), claimed.get().message()));
        }

        // G4 对局必须 RUNNING（GAME_OVER 是终态阶段，与状态双保险）
        if (!"RUNNING".equals(view.status()) || "GAME_OVER".equals(view.phase())) {
            return Outcome.reject(Denial.of(DenialCodes.GAME_ALREADY_FINISHED,
                    "对局 " + view.gameId() + " 的状态是 " + view.status()
                            + "（阶段 " + view.phase() + "），不再接受工具调用"));
        }

        String role = roleOf(session, view);
        String phase = view.phase();

        // G5 阶段允许该工具（兜底动作 SKIP 允许任意已注册工具承载）
        if (!RolePhaseMatrix.toolAllowed(role, phase, toolName, effectiveAction)) {
            return Outcome.reject(Denial.of(DenialCodes.ACTION_NOT_ALLOWED,
                    "阶段 " + phase + " 不允许工具 " + toolName + "（角色 " + role + "）")
                    .withAlternatives(alternatives(view, role, phase, effectiveAction, null)));
        }

        // G6 回合窗口：只对写动作生效（只读工具见类注释）
        if (effectiveAction != null && RolePhaseMatrix.requiresTurnWindow(phase) && !isMyTurn(view)) {
            String holder = view.currentTurn() == null
                    ? "尚未发放回合"
                    : "座位 " + view.currentTurn().seatNo();
            return Outcome.reject(Denial.of(DenialCodes.TURN_NOT_YOURS,
                    "阶段 " + phase + " 的回合属于 " + holder + "，不是座位 " + session.seatNo()));
        }

        // G7 角色具备该能力
        if (effectiveAction != null && !RolePhaseMatrix.actionAllowed(role, phase, effectiveAction)) {
            return Outcome.reject(Denial.of(DenialCodes.ACTION_NOT_ALLOWED,
                    "角色 " + role + " 在阶段 " + phase + " 不能提交 " + declaredActionType)
                    .withAlternatives(alternatives(view, role, phase, effectiveAction, null)));
        }

        // G8 次数门：矩阵声明的每夜/每局配额，以及女巫同夜双药的互斥（INV-7）
        Denial quotaDenial = checkQuota(view, role, phase, effectiveAction);
        if (quotaDenial != null) {
            return Outcome.reject(quotaDenial.withAlternatives(
                    alternatives(view, role, phase, effectiveAction, null)));
        }

        // G9 目标门：解析、四重约束、候选集成员判定。
        // 目标被拒时，"同一动作换一个目标"仍是合法替代，因此候选里保留该动作、只排掉被拒的目标。
        TargetResolution target = resolveTarget(view, session, toolName, effectiveAction, args);
        if (target.denial() != null) {
            return Outcome.reject(target.denial().withAlternatives(
                    alternatives(view, role, phase, effectiveAction, declaredTargetSeat)));
        }

        // G10 资源门：skillState 与规则参数
        Denial resourceDenial = checkResources(view, effectiveAction, target.seatNo());
        if (resourceDenial != null) {
            return Outcome.reject(resourceDenial.withAlternatives(
                    alternatives(view, role, phase, effectiveAction, null)));
        }

        // G11 回合预算：次数与时长。两次上限都要满足，通过后才计数（被 G1..G10 拒绝的调用
        // 不烧预算：那些是协议错误，把它们计入会让"改对参数"变成不可能）
        Denial budgetDenial = checkBudget(session, nowMillis, effectiveAction);
        if (budgetDenial != null) {
            return Outcome.reject(budgetDenial);
        }

        return Outcome.pass(target.seatNo(), List.of());
    }

    // ---------------------------------------------------------------- 各门的实现

    private static String roleOf(ToolSession session, AgentView view) {
        String role = view.me() == null ? null : view.me().role();
        return role == null ? session.role() : role;
    }

    private static boolean isMyTurn(AgentView view) {
        if (view.currentTurn() == null) {
            return false;
        }
        return view.me() != null && view.me().playerId().equals(view.currentTurn().playerId());
    }

    /** G8：次数门。数据来自本座位可见的私有事实与技能状态，不自己推演规则。 */
    private static Denial checkQuota(AgentView view, String role, String phase, String action) {
        if (action == null) {
            return null;
        }
        // INV-7 与"每夜 1 次"共享同一个证据：本夜已经用过药（任一药）就不能再提交任何用药动作。
        // 它比矩阵里的"全局 1 次"更严，因此必须在配额判定之前先查。
        Denial sameNight = sameNightDrugExclusion(action, view, view.round());
        if (sameNight != null) {
            return sameNight;
        }
        List<RolePhaseMatrix.Grant> grants = RolePhaseMatrix.grantsFor(role, phase, action);
        if (grants.isEmpty()) {
            return null;
        }
        RolePhaseMatrix.Quota quota = grants.get(0).quota();
        return switch (quota) {
            case NONE -> null;
            case PER_NIGHT -> perNight(action, view, view.round());
            case PER_GAME -> perGame(action, view);
        };
    }

    /** INV-7：同一夜不得同用解药与毒药，也不可能一夜用两次同一种药。 */
    private static Denial sameNightDrugExclusion(String action, AgentView view, int round) {
        if (!List.of("WITCH_SAVE", "WITCH_POISON", "WITCH_PASS").contains(action)) {
            return null;
        }
        boolean used = hasFact(view, AgentView.PrivateFact.FactKind.WITCH_SAVED, round)
                || hasFact(view, AgentView.PrivateFact.FactKind.WITCH_POISONED, round);
        return used
                ? Denial.of(DenialCodes.ACTION_NOT_ALLOWED,
                        "女巫本夜已经用药，同夜不可再次用药（INV-7、每夜 1 次）")
                : null;
    }

    private static Denial perNight(String action, AgentView view, int round) {
        if ("SEER_INSPECT".equals(action)
                && hasFact(view, AgentView.PrivateFact.FactKind.SEER_INSPECT, round)) {
            return Denial.of(DenialCodes.ACTION_NOT_ALLOWED, "预言家本夜已查验（每夜 1 次，V6）");
        }
        return null;
    }

    private static Denial perGame(String action, AgentView view) {
        return switch (action) {
            case "WITCH_SAVE" -> hasFact(view, AgentView.PrivateFact.FactKind.WITCH_SAVED, null)
                    ? Denial.of(DenialCodes.ACTION_NOT_ALLOWED, "解药全局只能用 1 次（INV-7）")
                    : null;
            case "WITCH_POISON" -> hasFact(view, AgentView.PrivateFact.FactKind.WITCH_POISONED, null)
                    ? Denial.of(DenialCodes.ACTION_NOT_ALLOWED, "毒药全局只能用 1 次（INV-7）")
                    : null;
            // 猎人开枪的"全局 1 次"由 skillState.shotAvailable 表达，属 G10 资源门
            default -> null;
        };
    }

    private static boolean hasFact(
            AgentView view, AgentView.PrivateFact.FactKind kind, Integer round) {
        return view.privateEvents().stream()
                .filter(fact -> fact.is(kind))
                .anyMatch(fact -> round == null || fact.round() == round);
    }

    /** G9 的解析结果：合法目标座位，或一条拒绝。 */
    private record TargetResolution(Integer seatNo, Denial denial) {

        static TargetResolution none() {
            return new TargetResolution(null, null);
        }
    }

    /**
     * G9 目标门：存活、非自身、非队友、在候选集内四重约束（§8"目标越界"）。
     *
     * <p>模型可以给 {@code targetId}（玩家 id）、{@code targetSeatNo}（座位号）或都不给
     * （弃权/空刀/放弃开枪/不用药）。两个都给时必须指向同一座位，否则按参数注入拒绝。
     */
    private static TargetResolution resolveTarget(
            AgentView view, ToolSession session, String toolName, String action, Map<String, Object> args) {
        ToolCatalog.TargetPolicy policy = ToolCatalog.targetPolicy(toolName, action);
        if (policy == null) {
            return TargetResolution.none();
        }
        String targetId = stringArg(args, "targetId");
        Integer targetSeat = intArg(args, "targetSeatNo");

        Integer byId = null;
        if (targetId != null) {
            byId = seatOfPlayer(view, targetId);
            if (byId == null) {
                return new TargetResolution(null, Denial.of(DenialCodes.ACTION_INVALID_TARGET,
                        "目标 " + targetId + " 不在对局 " + view.gameId() + " 中"));
            }
        }
        if (byId != null && targetSeat != null && !byId.equals(targetSeat)) {
            return new TargetResolution(null, Denial.of(DenialCodes.TOOL_ARGS_INVALID,
                    "targetId 与 targetSeatNo 指向不同座位（" + byId + " / " + targetSeat + "）"));
        }
        Integer resolved = byId != null ? byId : targetSeat;

        if (resolved == null) {
            return policy == ToolCatalog.TargetPolicy.REQUIRED
                    ? new TargetResolution(null, Denial.of(DenialCodes.TOOL_ARGS_INVALID,
                            action + " 必须给出 targetId 或 targetSeatNo"))
                    : TargetResolution.none();
        }

        int mySeat = view.me() == null ? session.seatNo() : view.me().seatNo();
        AgentView.SeatView seat = seatOf(view, resolved);
        if (seat == null) {
            return new TargetResolution(null, Denial.of(DenialCodes.ACTION_INVALID_TARGET,
                    "座位 " + resolved + " 不在对局中"));
        }
        if (!seat.alive()) {
            return new TargetResolution(null, Denial.of(DenialCodes.ACTION_INVALID_TARGET,
                    "目标座位 " + resolved + " 已死亡"));
        }

        RolePhaseMatrix.TargetRule rule = RolePhaseMatrix
                .grantsFor(roleOf(session, view), view.phase(), action).stream()
                .findFirst()
                .map(RolePhaseMatrix.Grant::target)
                .orElse(RolePhaseMatrix.TargetRule.NONE);
        if (rule == RolePhaseMatrix.TargetRule.WITCH_SAVE && resolved == mySeat) {
            // 自救不走"不能指向自己"的通用门，是否允许只由规则决定（与领域 ActionValidator 同口径）
            return view.witchCanSelfSave()
                    ? new TargetResolution(resolved, null)
                    : new TargetResolution(null, Denial.of(DenialCodes.ACTION_INVALID_TARGET,
                            view.ruleBool("witchSelfSave", false)
                                    ? "女巫只有首夜可以自救（witchSelfSaveFirstNightOnly=true）"
                                    : "本局女巫不可自救（witchSelfSave=false）"));
        }
        if (resolved == mySeat) {
            return new TargetResolution(null, Denial.of(DenialCodes.ACTION_INVALID_TARGET,
                    "目标不能是本人座位 " + mySeat));
        }
        if (rule == RolePhaseMatrix.TargetRule.ALIVE_NON_TEAMMATE && isTeammate(view, resolved)) {
            return new TargetResolution(null, Denial.of(DenialCodes.ACTION_INVALID_TARGET,
                    "目标座位 " + resolved + " 是狼队友，狼刀不得指向队友（INV-6）"));
        }
        if (!legalTargets(view, rule).contains(resolved)) {
            return new TargetResolution(null, Denial.of(DenialCodes.ACTION_INVALID_TARGET,
                    "座位 " + resolved + " 不在当前合法候选集内"));
        }
        return new TargetResolution(resolved, null);
    }

    /** G10 资源门：技能状态与规则参数（{@code witchSelfSave} 等规则判定仍在领域）。 */
    private static Denial checkResources(AgentView view, String action, Integer targetSeat) {
        if (action == null || view.me() == null || view.me().skills() == null) {
            return null;
        }
        AgentView.SkillView skills = view.me().skills();
        return switch (action) {
            case "WITCH_SAVE" -> skills.antidoteAvailable()
                    ? null
                    : Denial.of(DenialCodes.ACTION_NOT_ALLOWED, "解药已用完（INV-7）");
            case "WITCH_POISON" -> skills.poisonAvailable()
                    ? null
                    : Denial.of(DenialCodes.ACTION_NOT_ALLOWED, "毒药已用完（INV-7）");
            case "HUNTER_SHOOT" -> targetSeat != null && !skills.shotAvailable()
                    ? Denial.of(DenialCodes.ACTION_NOT_ALLOWED, "开枪次数已用尽（INV-8）")
                    : null;
            default -> null;
        };
    }

    /** G11 回合预算。 */
    private Denial checkBudget(ToolSession session, long nowMillis, String action) {
        if (session.deadline() > 0 && nowMillis >= session.deadline()) {
            return Denial.of(DenialCodes.BUDGET_EXCEEDED,
                    "回合提交截止时刻已过（deadline=" + session.deadline() + "，now=" + nowMillis + "）");
        }
        var key = session.turnKey();
        int used = ledger.usedCount(key);
        if (used >= config.maxToolCallsPerTurn()) {
            return Denial.of(DenialCodes.BUDGET_EXCEEDED,
                    "本回合工具调用已达上限 " + config.maxToolCallsPerTurn() + " 次（gateway.maxToolCallsPerTurn）");
        }
        if (session.budgetExhausted()) {
            return Denial.of(DenialCodes.BUDGET_EXCEEDED, "工具会话剩余调用次数为 0");
        }
        ledger.consume(key);
        return null;
    }

    // ---------------------------------------------------------------- 候选与工具方法

    /**
     * "现在还能做什么"（§5 的 {@code allowedAlternatives}）：由同一份矩阵与同一份视图派生，
     * 只列真的能通过的组合。
     *
     * @param deniedTargetSeat 目标被拒时被排掉的那个座位；此时保留被拒的动作本身
     *                         （"换个目标"就是最直接的修复方式），只是不列该座位；
     *                         其他门的拒绝则把整个动作去掉（它现在根本不可用）。
     */
    private static List<Candidate> alternatives(
            AgentView view, String role, String phase, String rejectedAction, Integer deniedTargetSeat) {
        List<Candidate> candidates = new ArrayList<>();
        boolean keepRejectedAction = deniedTargetSeat != null;
        int mySeat = view.me() == null ? -1 : view.me().seatNo();
        if (deniedTargetSeat != null && deniedTargetSeat == mySeat) {
            // 目标是"自己"这种拒绝没有"换个目标"可言，按动作不可用处理
            keepRejectedAction = false;
        }
        for (String action : RolePhaseMatrix.actionTypesFor(role, phase)) {
            if (action.equals(rejectedAction) && !keepRejectedAction) {
                continue;
            }
            RolePhaseMatrix.TargetRule rule = RolePhaseMatrix.grantsFor(role, phase, action).stream()
                    .findFirst()
                    .map(RolePhaseMatrix.Grant::target)
                    .orElse(RolePhaseMatrix.TargetRule.NONE);
            List<Integer> seats = legalTargets(view, rule);
            if (seats.isEmpty() || rule == RolePhaseMatrix.TargetRule.NONE) {
                candidates.add(Candidate.noTarget(action, "当前可提交：" + action));
                continue;
            }
            for (Integer seat : seats) {
                if (seat.equals(deniedTargetSeat)) {
                    continue;
                }
                AgentView.SeatView target = seatOf(view, seat);
                candidates.add(new Candidate(
                        action,
                        seat,
                        target == null ? null : target.playerId(),
                        "当前可选目标：" + seat,
                        0.0,
                        Map.of(),
                        List.of(),
                        List.of()));
            }
        }
        return List.copyOf(candidates);
    }

    /** 合法目标集：按目标约束过滤存活座位；不含自己（女巫自救由规则参数决定，见 G9）。 */
    private static List<Integer> legalTargets(AgentView view, RolePhaseMatrix.TargetRule rule) {
        if (rule == RolePhaseMatrix.TargetRule.NONE) {
            return List.of();
        }
        int mySeat = view.me() == null ? -1 : view.me().seatNo();
        List<Integer> seats = new ArrayList<>();
        for (AgentView.SeatView seat : view.aliveOthers()) {
            if (seat.seatNo() == mySeat) {
                continue;
            }
            if (rule == RolePhaseMatrix.TargetRule.ALIVE_NON_TEAMMATE && isTeammate(view, seat.seatNo())) {
                continue;
            }
            seats.add(seat.seatNo());
        }
        return List.copyOf(seats);
    }

    /**
     * 是否队友：只看投影已经给出的阵营信息。狼人的队友角色在投影里是可见的（WOLF 可见性），
     * 村民看到的 {@code camp} 一律为 {@code null}，因此这里不会给村民任何额外信息。
     */
    private static boolean isTeammate(AgentView view, int seatNo) {
        AgentView.SeatView seat = seatOf(view, seatNo);
        return seat != null && "WEREWOLF".equals(seat.role());
    }

    private static AgentView.SeatView seatOf(AgentView view, int seatNo) {
        if (view.me() != null && view.me().seatNo() == seatNo) {
            return new AgentView.SeatView(
                    view.me().playerId(), view.me().seatNo(), view.me().alive(),
                    view.me().role(), view.me().camp(), view.me().controllerType());
        }
        return view.seat(seatNo).orElse(null);
    }

    private static Integer seatOfPlayer(AgentView view, String playerId) {
        if (view.me() != null && view.me().playerId().equals(playerId)) {
            return view.me().seatNo();
        }
        return view.other().stream()
                .filter(seat -> seat.playerId().equals(playerId))
                .map(AgentView.SeatView::seatNo)
                .findFirst()
                .orElse(null);
    }

    /** 座位 → 玩家 id；用于把"notes 里提到的目标"识别成合法引用而不是身份伪造。 */
    private static String playerIdOfSeat(AgentView view, int seatNo) {
        AgentView.SeatView seat = seatOf(view, seatNo);
        return seat == null ? null : seat.playerId();
    }

    private static String stringArg(Map<String, Object> args, String key) {
        Object value = args == null ? null : args.get(key);
        return value instanceof String text ? text.strip() : null;
    }

    private static Integer intArg(Map<String, Object> args, String key) {
        Object value = args == null ? null : args.get(key);
        return value instanceof Number number ? number.intValue() : null;
    }

    /** 便于测试与诊断：当前视角下的合法目标座位集合。 */
    public static Set<Integer> legalTargets(AgentView view, String role, String phase, String action) {
        RolePhaseMatrix.TargetRule rule = RolePhaseMatrix.grantsFor(role, phase, action).stream()
                .findFirst()
                .map(RolePhaseMatrix.Grant::target)
                .orElse(RolePhaseMatrix.TargetRule.NONE);
        return new LinkedHashSet<>(legalTargets(view, rule));
    }
}
