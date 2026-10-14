package com.wolfmind.infrastructure.agent;

import com.wolfmind.application.agent.model.ActionProposal;
import com.wolfmind.application.agent.model.AgentView;
import com.wolfmind.application.agent.model.ToolSession;
import com.wolfmind.application.agent.llm.LlmClient;
import com.wolfmind.infrastructure.json.Json;
import com.wolfmind.application.agent.tool.ToolGatewayPort;
import com.wolfmind.application.agent.trace.AgentTracePort;
import com.wolfmind.application.agent.view.AgentViewQueryPort;
import com.wolfmind.application.game.SubmitActionApplicationService;
import com.wolfmind.application.port.GameViewQueryPort;
import com.wolfmind.application.port.IdempotencyPort;
import com.wolfmind.application.port.TimePort;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * {@link ToolGatewayPort} 的本地实现（tool-gateway.md §10 Phase 3/4：同进程直接调用）。
 *
 * <p>它只做四件事：把调用路由到读/写处理器、按固定顺序跑校验流水线、写审计、把结果收敛成
 * 成功或结构化拒绝。类里没有任何游戏规则（§9）：票数、结算、胜负、弃权与空刀是否允许
 * 全部由领域判定，网关只回答"这个工具在这个阶段对这个角色是否开放"。
 *
 * <p>流水线落点（每一步都有独立测试：{@code PermissionCheckerTest}、{@code ToolGatewayWriteTest}、
 * {@code ToolGatewayReadTest}、{@code ToolGatewayAuditTest}）：
 * <ol>
 *   <li>G1..G11：{@link PermissionChecker#check}（顺序固定，见该类的表格）；</li>
 *   <li>G12：本类的幂等门——命中 {@code IdempotencyPort} 直接返回首次结果；</li>
 *   <li>G13：{@link WriteActionHandler}——命令提交与"版本冲突重试一次"。</li>
 * </ol>
 *
 * <p>三条不变量：
 * <ul>
 *   <li><b>拒绝不产生事件</b>：G1..G12 的拒绝路径完全不触碰应用层写服务，事件流长度不变；</li>
 *   <li><b>只读无副作用</b>：{@code QueryGameStateTool} / {@code QueryHistoryTool} 只读投影与事件流；</li>
 *   <li><b>不持锁</b>：网关从不获取 {@code game:{id}:lock}，单写者锁只在应用层生效（§6），
 *       把 LLM 延迟带进锁内会阻塞真人与其他 AI。</li>
 * </ul>
 */
public final class ToolGatewayAdapter implements ToolGatewayPort {

    private final AgentViewQueryPort views;
    private final QueryHistoryTool historyTool;
    private final WriteActionHandler writeHandler;
    private final IdempotencyPort idempotency;
    private final PermissionChecker checker;
    private final ToolAuditRecorder audit;
    private final TimePort time;
    private final ToolGatewayConfig config;
    private final ToolTraceIds traceIds = new ToolTraceIds();
    private com.wolfmind.application.scheduling.TurnTaskStorePort taskStore;
    public void bindTaskStore(com.wolfmind.application.scheduling.TurnTaskStorePort store) { this.taskStore = store; }

    private boolean taskAllowed(ToolSession session) {
        return session.fence() == null || taskStore != null && taskStore.reserve(session.fence(), "TOOL",
                session.remainingToolCalls(), time.nowMillis());
    }

    public ToolGatewayAdapter(
            AgentViewQueryPort views,
            GameViewQueryPort events,
            SubmitActionApplicationService submitService,
            IdempotencyPort idempotency,
            AgentTracePort trace,
            TimePort time,
            ToolGatewayConfig config) {
        this.config = config == null ? ToolGatewayConfig.DEFAULT : config;
        this.views = Objects.requireNonNull(views, "views");
        this.historyTool = new QueryHistoryTool(Objects.requireNonNull(events, "events"));
        this.writeHandler = new WriteActionHandler(submitService, this.config);
        this.idempotency = Objects.requireNonNull(idempotency, "idempotency");
        this.time = Objects.requireNonNull(time, "time");
        this.audit = new ToolAuditRecorder(trace, this.config);
        this.checker = new PermissionChecker(this.config, new TurnBudgetLedger());
    }

    /** 默认配置的便捷构造：接线层只关心端口与开关时用它（审计与幂等均由外部注入）。 */
    public ToolGatewayAdapter(
            AgentViewQueryPort views,
            GameViewQueryPort events,
            SubmitActionApplicationService submitService,
            IdempotencyPort idempotency,
            AgentTracePort trace,
            TimePort time) {
        this(views, events, submitService, idempotency, trace, time, ToolGatewayConfig.DEFAULT);
    }

    // ---------------------------------------------------------------- 只读工具

    @Override
    public List<LlmClient.LlmRequest.ToolSpec> toolSpecifications(String role, String phase) {
        return ModelToolCodec.specifications(role, phase, config);
    }

    @Override
    public PreparedCall prepareCall(ToolSession session, LlmClient.ToolCall call) {
        AgentView view = views.viewFor(session.gameId(), session.playerId());
        if (view == null || view.me() == null || IdentityGuard.bind(session, view).isPresent()) {
            return new PreparedCall(null, null, ToolResult.Denied.of(call.name(), "TOOL_IDENTITY_MISMATCH", "身份不匹配"));
        }
        if (!"RUNNING".equals(view.status()) || !session.phase().equals(view.phase())
                || (session.expectedRound() >= 0 && session.expectedRound() != view.round())
                || time.nowMillis() >= session.deadline()) {
            return new PreparedCall(null, null, ToolResult.Denied.of(call.name(), "ACTION_NOT_ALLOWED", "回合已失效"));
        }
        return ModelToolCodec.prepare(call, view, config);
    }

    @Override
    public ModelReadResult executeRead(ToolSession session, LlmClient.ToolCall call) {
        PreparedCall prepared = prepareCall(session, call);
        ToolResult result;
        if (!prepared.valid()) result = prepared.rejection();
        else if (!ToolCatalog.READ_TOOLS.contains(call.name())) {
            result = ToolResult.Denied.of(call.name(), "ACTION_NOT_ALLOWED", "此入口仅允许只读工具");
        } else {
            Map<String, Object> args = ModelToolCodec.arguments(call);
            result = call.name().equals(ToolCatalog.QUERY_GAME_STATE)
                    ? queryGameState(session, (String) args.get("scope"))
                    : queryHistory(session, integer(args, "fromVersion"), integer(args, "toVersion"),
                            args.get("limit") == null ? config.queryHistoryMaxLimit() : integer(args, "limit"));
        }
        AgentView authorized = null;
        Map<String, Object> payload;
        if (result instanceof ToolResult.Ok ok) {
            payload = Map.of("ok", true, "version", ok.version(), "payload", ok.payload());
            if (ok.payload().get(QueryGameStateTool.KEY_QUERY) instanceof QueryOutcome query) authorized = query.view();
            if (call.name().equals(ToolCatalog.QUERY_HISTORY)) authorized = views.viewFor(session.gameId(), session.playerId());
        } else {
            ToolResult.Denied denied = (ToolResult.Denied) result;
            payload = Map.of("ok", false, "code", denied.code(), "message", "工具查询被拒绝");
        }
        String content = Json.write(payload);
        if (content.length() > 24000) {
            result = ToolResult.Denied.of(call.name(), "TOOL_ARGS_INVALID", "结果过大，请缩小查询区间或 limit");
            content = Json.write(Map.of("ok", false, "code", "TOOL_ARGS_INVALID", "message", "结果过大，请缩小查询范围"));
            authorized = null;
        }
        return new ModelReadResult(result, content, authorized);
    }

    private static Integer integer(Map<String, Object> args, String field) {
        return args.get(field) instanceof Number value ? value.intValue() : null;
    }

    @Override
    public ToolResult queryGameState(ToolSession session, String scope) {
        if (!taskAllowed(session)) return ToolResult.Denied.of(ToolCatalog.QUERY_GAME_STATE, "ACTION_NOT_ALLOWED", "任务已失效或预算耗尽");
        long startedAt = time.nowMillis();
        String tool = ToolCatalog.QUERY_GAME_STATE;
        String traceId = traceIds.next(session.gameId(), session.playerId(), tool);
        Map<String, Object> args = new LinkedHashMap<>();
        if (scope != null) {
            args.put("scope", scope);
        }

        AgentView view = views.viewFor(session.gameId(), session.playerId());
        PermissionChecker.Outcome outcome = checker.check(session, tool, null, args, view, startedAt);
        if (!outcome.allowed()) {
            return deny(session, tool, digest("scope=" + scope), outcome.denial(), view, startedAt, traceId);
        }
        ToolResult.Ok result = QueryGameStateTool.handle(scope, view);
        allow(session, tool, digest("scope=" + scope + " version=" + view.version()),
                result, view, startedAt, traceId, false);
        return result;
    }

    @Override
    public ToolResult queryHistory(ToolSession session, Integer fromVersion, Integer toVersion, int limit) {
        if (!taskAllowed(session)) return ToolResult.Denied.of(ToolCatalog.QUERY_HISTORY, "ACTION_NOT_ALLOWED", "任务已失效或预算耗尽");
        long startedAt = time.nowMillis();
        String tool = ToolCatalog.QUERY_HISTORY;
        String traceId = traceIds.next(session.gameId(), session.playerId(), tool);
        Map<String, Object> args = new LinkedHashMap<>();
        if (fromVersion != null) {
            args.put("fromVersion", fromVersion);
        }
        if (toVersion != null) {
            args.put("toVersion", toVersion);
        }
        args.put("limit", limit);

        AgentView view = views.viewFor(session.gameId(), session.playerId());
        PermissionChecker.Outcome outcome = checker.check(session, tool, null, args, view, startedAt);
        String digest = digest("from=" + fromVersion + " to=" + toVersion + " limit=" + limit);
        if (!outcome.allowed()) {
            return deny(session, tool, digest, outcome.denial(), view, startedAt, traceId);
        }
        ToolResult.Ok result = historyTool.handle(session, fromVersion, toVersion, limit, view);
        allow(session, tool, digest, result, view, startedAt, traceId, false);
        return result;
    }

    // ---------------------------------------------------------------- 写工具

    @Override
    public ToolResult submit(ToolSession session, String toolName, ActionProposal proposal) {
        if (!taskAllowed(session)) return ToolResult.Denied.of(toolName, "ACTION_NOT_ALLOWED", "任务已失效或预算耗尽");
        long startedAt = time.nowMillis();
        String traceId = traceIds.next(session.gameId(), session.playerId(), toolName);
        String declaredAction = proposal == null ? null : proposal.actionType();
        Map<String, Object> args = ToolCatalog.modelArgs(toolName, proposal);
        String digest = proposal == null ? digest("action=-") : digest(proposal.describeDigest());

        AgentView view = views.viewFor(session.gameId(), session.playerId());
        if (session.expectedRound() >= 0 && (view == null || !session.phase().equals(view.phase())
                || session.expectedRound() != view.round())) {
            return deny(session, toolName, digest, Denial.of("ACTION_NOT_ALLOWED", "行动所属阶段已失效"), view, startedAt, traceId);
        }
        PermissionChecker.Outcome outcome =
                checker.check(session, toolName, declaredAction, args, view, startedAt);
        if (!outcome.allowed()) {
            return deny(session, toolName, digest, outcome.denial(), view, startedAt, traceId);
        }

        // G12 幂等门：同一个 (gameId, idempotencyKey) 已提交过，原样返回首次结果（§6）。
        // 键含会话版本，因此"同一回合重复执行"收敛，"下一个回合"必然是新键。
        String idempotencyKey = session.idempotencyKey(toolName);
        Optional<IdempotencyPort.RecordedResult> recorded =
                idempotency.find(session.gameId(), idempotencyKey);
        if (recorded.isPresent()) {
            IdempotencyPort.RecordedResult first = recorded.get();
            ToolResult.Ok replayed = new ToolResult.Ok(
                    toolName, first.eventIds(), first.eventTypes(), first.version(),
                    payload(declaredAction, outcome.resolvedTargetSeat(), first.version(), true));
            allow(session, toolName, digest + " idempotent=hit", replayed, view, startedAt, traceId, true);
            return replayed;
        }

        // G13 提交：命令的每个字段都不来自模型（见 WriteActionHandler 的类注释）
        String effectiveAction = ToolCatalog.effectiveActionType(
                declaredAction, proposal != null && proposal.hasSpeech());
        Map<String, Object> params =
                ToolCatalog.domainParams(effectiveAction, proposal, outcome.resolvedTargetSeat());
        WriteActionHandler.Outcome written = writeHandler.handle(
                session, toolName, effectiveAction, params, () -> freshVersion(session), traceId);
        if (!written.accepted()) {
            return deny(session, toolName, digest, written.denial(), view, startedAt, traceId);
        }

        ToolResult.Ok ok = new ToolResult.Ok(
                toolName, written.eventIds(), written.eventTypes(), written.version(),
                payload(effectiveAction, outcome.resolvedTargetSeat(), written.version(), false));
        allow(session, toolName,
                digest + " target=" + outcome.resolvedTargetSeat() + " action=" + effectiveAction,
                ok, view, startedAt, traceId, false);
        return ok;
    }

    // ---------------------------------------------------------------- 矩阵查询（策略层与 prompt 共用）

    @Override
    public List<String> availableTools(String role, String phase) {
        return RolePhaseMatrix.toolsFor(role, phase);
    }

    @Override
    public List<String> allowedActionTypes(String role, String phase) {
        return RolePhaseMatrix.actionTypesFor(role, phase);
    }

    /** 便于接线层与测试：本实例的配置。 */
    public ToolGatewayConfig config() {
        return config;
    }

    /** 便于运维观测：已生成的 traceId 数 == 已接受的调用数。 */
    public long issuedTraceIds() {
        return traceIds.issued();
    }

    // ---------------------------------------------------------------- 内部

    /**
     * 版本冲突重试时刷新 {@code expectedVersion}：重新读一次投影。
     *
     * <p>读不到时退回会话版本：这样第二次提交仍会冲突，最终返回 {@code retryable=true} 的
     * 结构化拒绝，而不是把"读不到视图"伪装成版本冲突。
     */
    private int freshVersion(ToolSession session) {
        AgentView view = views.viewFor(session.gameId(), session.playerId());
        if (session.expectedRound() >= 0 && (view == null || !session.phase().equals(view.phase())
                || session.expectedRound() != view.round() || time.nowMillis() >= session.deadline())) return session.turnVersion();
        return view == null ? session.turnVersion() : view.version();
    }

    private static Map<String, Object> payload(
            String actionType, Integer targetSeat, int version, boolean replayed) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("actionType", actionType);
        if (targetSeat != null) {
            payload.put("targetSeatNo", targetSeat);
        }
        payload.put("version", version);
        payload.put("replayed", replayed);
        return Map.copyOf(payload);
    }

    private static String digest(String summary) {
        return summary == null ? "" : summary;
    }

    private static String digest(List<String> parts) {
        return String.join(" ", parts);
    }

    private ToolResult deny(
            ToolSession session,
            String tool,
            String argsDigest,
            Denial denial,
            AgentView view,
            long startedAt,
            String traceId) {
        record(session, tool, argsDigest, ToolAuditRecorder.Decision.DENIED, denial.code(),
                denial.message(), null, view, 0, false, startedAt, traceId);
        return denial.toResult(tool);
    }

    private void allow(
            ToolSession session,
            String tool,
            String argsDigest,
            ToolResult.Ok ok,
            AgentView view,
            long startedAt,
            String traceId,
            boolean replayed) {
        String firstEventId = ok.eventIds().isEmpty() ? null : ok.eventIds().get(0);
        try {
            record(session, tool, argsDigest, ToolAuditRecorder.Decision.ALLOWED, null,
                    null, firstEventId, view, ok.version(), replayed, startedAt, traceId);
        } catch (RuntimeException failedAudit) {
            if (ok.eventIds().isEmpty()) throw failedAudit;
            System.getLogger(ToolGatewayAdapter.class.getName()).log(System.Logger.Level.WARNING,
                    "行动已提交，工具审计写入失败：" + failedAudit.getClass().getSimpleName());
        }
    }

    /**
     * 写一条审计（§7）。只读工具同样记录：不记录的话，"模型当时看到了什么"就无法复盘。
     *
     * <p>视图不可用时（身份不成立）用会话字段兜底：审计记录必须落下来，
     * 否则"身份不匹配"这类最需要调查的调用反而没有痕迹。
     */
    private void record(
            ToolSession session,
            String tool,
            String argsDigest,
            ToolAuditRecorder.Decision decision,
            String denialCode,
            String denialMessage,
            String eventId,
            AgentView view,
            int version,
            boolean replayed,
            long startedAt,
            String traceId) {
        long now = time.nowMillis();
        audit.record(new ToolAuditRecorder.AuditEntry(
                session.gameId(),
                session.playerId(),
                tool,
                argsDigest,
                decision,
                denialCode,
                denialMessage,
                eventId,
                version,
                replayed,
                Math.max(0L, now - startedAt),
                view == null ? session.phase() : view.phase(),
                view == null ? 0 : view.round(),
                view == null ? 0 : view.turnIndex(),
                traceId,
                now));
    }
}
