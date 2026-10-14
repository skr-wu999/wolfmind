package com.wolfmind.infrastructure.agent;

import com.wolfmind.application.agent.model.ToolSession;
import com.wolfmind.application.game.ActionResult;
import com.wolfmind.application.game.SubmitActionApplicationService;
import com.wolfmind.application.game.SubmitActionCommand;

import java.util.List;
import java.util.Map;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.IntSupplier;

/**
 * 写路径处理器（tool-gateway.md §1、§6 G13）：把已通过 G1..G12 的动作变成一次命令提交。
 *
 * <p>三件必须由这里保证的事：
 * <ol>
 *   <li><b>命令的五个字段没有一个是模型给的</b>：{@code gameId} / {@code actorId} 来自
 *       {@link ToolSession}，{@code actionType} 与 {@code params} 由网关按已解析的目标重建，
 *       {@code expectedVersion} 取自会话（冲突后刷新一次），{@code idempotencyKey} 由
 *       {@code session.idempotencyKey(toolName)} 生成；</li>
 *   <li><b>版本冲突与 {@code GAME_BUSY} 有限重试</b>（{@code gateway.versionRetry}，默认 2 次）：
 *       刷新投影版本后以同一幂等键重试，锁被占用时先短暂退避；用尽后返回 {@code retryable=true}
 *       的结构化拒绝，由 Runtime 决定是否降级（tool-gateway.md §6）。投票阶段多名 AI 并发提交时
 *       除第一个外都会先撞版本冲突；</li>
 *   <li><b>成功只表示事件已写入</b>：返回值里的 version 与 eventIds 来自应用层的提交结果，
 *       不是网关自己算出来的。</li>
 * </ol>
 *
 * <p>关于 {@code actorId}：应用层按 {@code actorId == player.controller.refId} 反查座位，
 * 而工具会话只有"座位玩家 id"。因此本实现用会话的 {@code playerId} 作为操作者身份，并要求
 * AI 座位在装配时以同一标识作为 {@code agentId}（Phase 3 的 AI 对局装配约定，已向 Lead 报告）。
 * 如果两者不一致，应用层会以 {@code SEAT_NOT_YOURS} 拒绝——网关不会为了"让它成功"去猜别的身份。
 */
public final class WriteActionHandler {

    /** G13 的提交结果。 */
    public record Outcome(Denial denial, List<String> eventIds, List<String> eventTypes, int version) {

        public Outcome {
            eventIds = List.copyOf(eventIds);
            eventTypes = List.copyOf(eventTypes);
        }

        public static Outcome accepted(List<String> eventIds, List<String> eventTypes, int version) {
            return new Outcome(null, eventIds, eventTypes, version);
        }

        public static Outcome denied(Denial denial) {
            return new Outcome(denial, List.of(), List.of(), 0);
        }

        public boolean accepted() {
            return denial == null;
        }
    }

    /** 单写者锁只覆盖一次提交（毫秒级），短退避即可错开。 */
    private static final long BUSY_BACKOFF_MS = 25L;

    /**
     * 同一对局的 AI 提交在进程内排队：投票阶段多名 Agent 并发决策，若各自乐观提交，
     * 瞬时返回的兜底动作会互相撞版本直到重试用尽。锁只包住"刷新版本 + 提交"，不含模型调用。
     */
    private static final ReentrantLock[] SUBMIT_STRIPES = new ReentrantLock[64];

    static {
        for (int index = 0; index < SUBMIT_STRIPES.length; index++) {
            SUBMIT_STRIPES[index] = new ReentrantLock();
        }
    }

    private final SubmitActionApplicationService submitService;
    private final ToolGatewayConfig config;

    public WriteActionHandler(SubmitActionApplicationService submitService, ToolGatewayConfig config) {
        if (submitService == null) {
            throw new IllegalArgumentException("写路径需要 SubmitActionApplicationService");
        }
        this.submitService = submitService;
        this.config = config == null ? ToolGatewayConfig.DEFAULT : config;
    }

    /**
     * 提交一次动作。
     *
     * @param freshVersion 冲突时刷新投影版本用："重新读一次当前视图的 version"
     * @param correlationId 与审计一致的事件关联 id（{@code traceId}）
     */
    public Outcome handle(
            ToolSession session,
            String toolName,
            String effectiveActionType,
            Map<String, Object> params,
            IntSupplier freshVersion,
            String correlationId) {
        ReentrantLock lock = SUBMIT_STRIPES[Math.floorMod(session.gameId().hashCode(), SUBMIT_STRIPES.length)];
        lock.lock();
        try {
            return submit(session, toolName, effectiveActionType, params, freshVersion, correlationId);
        } finally {
            lock.unlock();
        }
    }

    private Outcome submit(
            ToolSession session,
            String toolName,
            String effectiveActionType,
            Map<String, Object> params,
            IntSupplier freshVersion,
            String correlationId) {
        String idempotencyKey = session.idempotencyKey(toolName);
        int expectedVersion = session.turnVersion();
        int retries = 0;

        while (true) {
            SubmitActionCommand command = new SubmitActionCommand(
                    session.gameId(),
                    session.playerId(),
                    effectiveActionType,
                    params,
                    expectedVersion,
                    idempotencyKey,
                    correlationId, session.fence());
            try {
                ActionResult result = submitService.submit(command);
                if (!result.accepted()) {
                    // 领域接受了命令但没有产生事件：没有事件就不是"成功"（§1 的语义）
                    return Outcome.denied(Denial.of(DenialCodes.ACTION_NOT_ALLOWED,
                            "命令在当前状态不产生任何事件（无状态变化，INV-11）"));
                }
                return Outcome.accepted(result.eventIds(), result.eventTypes(), result.version());
            } catch (RuntimeException failure) {
                Denial denial = DomainRejectionTranslator.translate(failure);
                if (denial == null) {
                    // 真故障（不是领域/应用层的结构化拒绝）：原样抛出，不伪装成"换个动作就好"
                    throw failure;
                }
                boolean conflict = DomainRejectionTranslator.isVersionConflict(failure);
                boolean busy = DomainRejectionTranslator.isBusy(failure);
                if (!(conflict || busy) || retries >= config.versionRetry()) {
                    return Outcome.denied(denial);
                }
                retries++;
                if (busy) {
                    backOff(retries);
                }
                expectedVersion = freshVersion.getAsInt();
            }
        }
    }

    private static void backOff(int attempt) {
        try {
            Thread.sleep(BUSY_BACKOFF_MS * attempt);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }
}
