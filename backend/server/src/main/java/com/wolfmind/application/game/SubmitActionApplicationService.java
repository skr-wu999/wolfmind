package com.wolfmind.application.game;

import com.wolfmind.application.port.EventPublisherPort;
import com.wolfmind.application.port.GameLockPort;
import com.wolfmind.application.port.GameRepositoryPort;
import com.wolfmind.application.port.IdempotencyPort;
import com.wolfmind.application.port.TimePort;
import com.wolfmind.domain.game.event.DomainEvent;
import com.wolfmind.domain.game.event.EventEnvelope;
import com.wolfmind.domain.game.model.Action;
import com.wolfmind.domain.game.model.ActionContext;
import com.wolfmind.domain.game.model.ActionType;
import com.wolfmind.domain.game.model.Game;
import com.wolfmind.domain.game.model.GameException;
import com.wolfmind.domain.game.model.GameId;
import com.wolfmind.domain.game.model.GameSnapshot;
import com.wolfmind.domain.game.model.PlayerSnapshot;
import com.wolfmind.domain.game.model.SeatNo;
import com.wolfmind.domain.game.model.SkipAction;
import com.wolfmind.domain.game.model.SkipReason;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * 提交行动用例（api-spec.md §5.3，ddd-design.md §6.1 的命令处理模板）。
 *
 * <p>步骤顺序固定，不得调换：
 * <ol>
 *   <li>鉴权：由接口层完成，本服务只接受已认证的 {@code actorId}</li>
 *   <li>幂等：命中即返回首次结果，<b>不加载聚合、不加锁</b></li>
 *   <li>行动类型与参数形态校验（{@link ActionCodec}）</li>
 *   <li>座位控制权：{@code actorId} 必须匹配某个座位的 {@code controller.refId}</li>
 *   <li>单写者锁：失败快速返回 {@code GAME_BUSY}，由客户端退避重试</li>
 *   <li>加载聚合（最新快照 + 其后增量事件）并前置校验 {@code expectedVersion}</li>
 *   <li>{@code Game.handle(action, ctx)}——所有合法性判定都在领域</li>
 *   <li>提交：{@code GameRepositoryPort.commit}（事件连续追加，无版本空洞）</li>
 *   <li>发布：先持久化后发布，发布失败不影响返回的状态一致性</li>
 * </ol>
 *
 * <p>本类不判断"这一步该不该允许"：它只提供身份、版本、锁与时间，规则判断全部在 {@link Game}（C-3）。
 */
public final class SubmitActionApplicationService {

    private final ActionCodec codec;
    private final IdempotencyPort idempotency;
    private final GameLockPort lock;
    private final GameRepositoryPort repository;
    private final EventPublisherPort publisher;
    private final TimePort time;
    private com.wolfmind.application.scheduling.TaskSubmissionGuard taskGuard;

    public void bindTaskGuard(com.wolfmind.application.scheduling.TaskSubmissionGuard guard) { this.taskGuard = guard; }

    public SubmitActionApplicationService(
            ActionCodec codec,
            IdempotencyPort idempotency,
            GameLockPort lock,
            GameRepositoryPort repository,
            EventPublisherPort publisher,
            TimePort time) {
        this.codec = Objects.requireNonNull(codec, "codec");
        this.idempotency = Objects.requireNonNull(idempotency, "idempotency");
        this.lock = Objects.requireNonNull(lock, "lock");
        this.repository = Objects.requireNonNull(repository, "repository");
        this.publisher = Objects.requireNonNull(publisher, "publisher");
        this.time = Objects.requireNonNull(time, "time");
    }

    public ActionResult submit(SubmitActionCommand command) {
        Objects.requireNonNull(command, "command");
        String gameId = command.gameId();

        // 第 2 步：幂等键命中说明这条意图已经处理过。此时既不加载聚合也不加锁，
        // 否则重复提交会无谓地占用单写者锁（api-spec.md §1：同一个键返回首次结果并带 replayed=true）。
        Optional<IdempotencyPort.RecordedResult> recorded =
                idempotency.find(gameId, command.idempotencyKey());
        if (recorded.isPresent()) {
            return ActionResult.replayed(recorded.get());
        }

        // 第 3 步（前半）：枚举值是纯输入契约，先于加锁失败可以让明显非法的请求不碰锁。
        // 参数到 Action 的完整还原依赖提交座位，而座位来自聚合，因此放在加载之后（见下）。
        ActionType type = codec.parseType(command.actionType());

        // 第 5 步：单写者锁。任何出口都必须释放（finally），否则三秒 TTL 会拖慢整局。
        GameId id = GameId.of(gameId);
        GameLockPort.LockToken token = lock.tryLock(gameId)
                .orElseThrow(() -> GameException.Factory.busy(id));
        try {
            // 第 6 步：加载聚合。版本取自加载结果，而不是客户端声称的版本。
            GameRepositoryPort.LoadedGame loaded = repository.load(gameId);
            int version = loaded.version();

            // 第 4 步：座位控制权。客户端不提交座位号，座位只能由身份反查得到（INV-3 的授权部分）；
            // 存活判定属于规则，留给领域。
            GameSnapshot snapshot = loaded.game().snapshot();
            SeatNo seat = seatOf(snapshot, command.actorId());

            // 第 3 步（后半）：还原 Action，参数形态非法抛 VALIDATION_ERROR。
            Action action = codec.decode(type, command.params(), seat);
            if (action instanceof SkipAction skip && skip.reason() == SkipReason.TIMEOUT) {
                throw GameException.Factory.notAllowed("TIMEOUT 只能由服务端超时看护提交");
            }

            // 第 6b 步：expectedVersion 前置校验。夜聊不改变任何规则状态，所以客户端版本之后
            // 只追加了 WOLF_CHAT_SENT 时不算过期（stateVersion ≤ expectedVersion ≤ version）；
            // 领域按当前版本构造上下文，handle 内的同一校验仍然成立。
            if (!isFresh(command.expectedVersion(), snapshot.stateVersion(), version)) {
                throw GameException.Factory.versionConflict(command.expectedVersion(), version);
            }

            // 第 7 步：领域判定与状态迁移。
            ActionContext ctx = context(command, version);
            if (command.fence() != null) {
                if (taskGuard == null) throw GameException.Factory.validation("Task guard unavailable");
                taskGuard.validate(command.fence(), snapshot, time.nowMillis());
            }
            List<DomainEvent> events = loaded.game().handle(action, ctx);
            if (events.isEmpty()) {
                // INV-11 的反面：没有状态变化就不允许写事件。不记幂等，客户端可以安全重试。
                return ActionResult.unchanged(gameId, version);
            }

            // 第 8 步：持久化。事件版本从 expectedVersion + 1 起连续递增。
            List<EventEnvelope> envelopes = EventEnvelopes.wrap(gameId, version + 1, events);
            GameRepositoryPort.CommitResult committed =
                    repository.commit(gameId, version, envelopes, GameRepositoryPort.SnapshotPolicy.INTERVAL);

            ActionResult result = ActionResult.accepted(
                    gameId, committed.version(), committed.eventIds(), committed.eventTypes());
            // 幂等记录紧随事件写入（system-design.md §5：结果可取回）；放在发布之前，
            // 因为推送可能很慢，而幂等结果必须与已提交的事件同时可读。
            idempotency.record(gameId, command.idempotencyKey(), result.toRecordedResult(command.idempotencyKey()));

            // 第 9 步：发布。先持久化后发布，失败由事件存留 + 消费组位点补偿（DESIGN.md §6）。
            EventPublication.publish(publisher, envelopes);
            return result;
        } finally {
            lock.unlock(token);
        }
    }

    static boolean isFresh(int expectedVersion, int stateVersion, int version) {
        return expectedVersion >= stateVersion && expectedVersion <= version;
    }

    private ActionContext context(SubmitActionCommand command, int version) {
        long occurredAt = time.nowMillis();
        String commandId = command.commandId();
        return ActionContext.of(
                        CommandIdentifiers.eventIdBase(command.gameId(), version + 1),
                        occurredAt,
                        command.actorId(),
                        commandId,
                        command.correlationId(),
                        version)
                .withRandomSeed(CommandIdentifiers.randomSeed(command.gameId(), commandId, occurredAt));
    }

    /** 身份 → 座位：只有控制者本人（真人 userId / AI agentId / BOT strategyKey）能代表该座位行动。 */
    private SeatNo seatOf(GameSnapshot snapshot, String actorId) {
        return snapshot.players().stream()
                .filter(player -> player.controller() != null && actorId.equals(player.controller().refId()))
                .map(PlayerSnapshot::seatNo)
                .findFirst()
                .orElseThrow(() -> GameException.Factory.notYourSeat(
                        "提交者 " + actorId + " 不是对局 " + snapshot.gameId() + " 任何座位的控制者"));
    }
}
