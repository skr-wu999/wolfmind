package com.wolfmind.application.game;

import com.wolfmind.application.port.EventPublisherPort;
import com.wolfmind.application.port.EventStorePort;
import com.wolfmind.application.port.GameLockPort;
import com.wolfmind.application.port.GameRepositoryPort;
import com.wolfmind.application.port.SnapshotStorePort;
import com.wolfmind.application.port.TimePort;
import com.wolfmind.domain.game.event.DomainEvent;
import com.wolfmind.domain.game.event.EventEnvelope;
import com.wolfmind.domain.game.event.EventMeta;
import com.wolfmind.domain.game.event.GameRecoveredEvent;
import com.wolfmind.domain.game.model.ActionContext;
import com.wolfmind.domain.game.model.DomainErrorCode;
import com.wolfmind.domain.game.model.GameException;
import com.wolfmind.domain.game.model.GameId;
import com.wolfmind.domain.game.model.GameSnapshot;

import java.util.List;
import java.util.Objects;

/**
 * 恢复对局用例（event-design.md §11、system-design.md §3.6）。
 *
 * <p>恢复的真相来源仍然是事件流：聚合的加载语义固定为"最新快照 + 其后增量事件"，
 * 由 {@link GameRepositoryPort#load} 承担；本服务在此之上补三件事：
 *
 * <ol>
 *   <li>用单写者锁把恢复与并发写请求隔开（拿不到锁说明有写者正在处理，返回 {@code GAME_BUSY}）</li>
 *   <li>校验快照之后的增量事件版本连续、且与事件流尾版本一致；不一致宁可失败也不"带病恢复"</li>
 *   <li>产生 {@code GAME_RECOVERED}（{@code visibility=JUDGE}）：恢复是可追溯的历史事实，
 *       不是服务端的隐式修补，因此它同样落在事件流里（C-4）</li>
 * </ol>
 *
 * <p>快照不是真相（INV-15）：快照缺失、校验失败时由仓储退化为从版本 1 全量重放，
 * 本服务不因此改变结果。
 */
public final class RecoverGameApplicationService {

    /** 恢复来源标识之一：崩溃后的自动恢复。 */
    public static final String SOURCE_CRASH_RECOVERY = "CRASH_RECOVERY";
    /** 恢复来源标识之一：运维接口手动触发（api-spec.md §9）。 */
    public static final String SOURCE_ADMIN = "ADMIN";

    private final GameLockPort lock;
    private final EventStorePort eventStore;
    private final SnapshotStorePort snapshots;
    private final GameRepositoryPort repository;
    private final EventPublisherPort publisher;
    private final TimePort time;

    public RecoverGameApplicationService(
            GameLockPort lock,
            EventStorePort eventStore,
            SnapshotStorePort snapshots,
            GameRepositoryPort repository,
            EventPublisherPort publisher,
            TimePort time) {
        this.lock = Objects.requireNonNull(lock, "lock");
        this.eventStore = Objects.requireNonNull(eventStore, "eventStore");
        this.snapshots = Objects.requireNonNull(snapshots, "snapshots");
        this.repository = Objects.requireNonNull(repository, "repository");
        this.publisher = Objects.requireNonNull(publisher, "publisher");
        this.time = Objects.requireNonNull(time, "time");
    }

    /**
     * @param source 恢复来源，写入 {@code GAME_RECOVERED.source}，便于区分崩溃恢复与人工干预
     */
    public ActionResult recover(String gameId, String source, String correlationId) {
        if (source == null || source.isBlank()) {
            throw new GameException(DomainErrorCode.VALIDATION_ERROR, "source 不能为空");
        }
        GameLockPort.LockToken token = lock.tryLock(gameId)
                .orElseThrow(() -> GameException.Factory.busy(GameId.of(gameId)));
        try {
            if (!eventStore.exists(gameId)) {
                // 本用例只处理已有流的恢复记录；缺流由仓储的 RestoreArchivedGameApplicationService 重建。
                throw new GameException(
                        DomainErrorCode.GAME_NOT_RUNNING,
                        "对局 " + gameId + " 的事件流不存在，请先通过归档恢复加载对局（event-design.md §11）");
            }

            int snapshotVersion = snapshots.latest(gameId)
                    .map(SnapshotStorePort.Snapshot::version)
                    .orElse(0);
            int streamVersion = eventStore.currentVersion(gameId);
            if (snapshotVersion > streamVersion) {
                throw new GameException(
                        DomainErrorCode.REPLAY_OUT_OF_ORDER,
                        "快照版本 " + snapshotVersion + " 超过事件流尾版本 " + streamVersion + "，状态不可信");
            }
            assertTailContiguous(gameId, snapshotVersion, streamVersion);

            GameRepositoryPort.LoadedGame loaded = repository.load(gameId);
            if (loaded.version() != streamVersion) {
                throw new GameException(
                        DomainErrorCode.REPLAY_OUT_OF_ORDER,
                        "聚合恢复到的版本 " + loaded.version() + " 与事件流尾版本 " + streamVersion + " 不一致");
            }

            GameSnapshot state = loaded.game().snapshot();
            int version = loaded.version() + 1;
            long occurredAt = time.nowMillis();
            String commandId = CommandIdentifiers.eventIdBase(gameId, version);
            DomainEvent recovered = new GameRecoveredEvent(
                    new EventMeta(
                            commandId,
                            version,
                            occurredAt,
                            state.phase(),
                            state.round(),
                            ActionContext.SYSTEM_ACTOR,
                            commandId,
                            CommandIdentifiers.correlationId(correlationId, commandId)),
                    snapshotVersion,
                    loaded.version(),
                    source);

            List<EventEnvelope> envelopes = EventEnvelopes.wrap(gameId, version, List.of(recovered));
            // 恢复后强制落快照：下一次恢复可以从这里起步，而不必再全量重放。
            GameRepositoryPort.CommitResult committed = repository.commit(
                    gameId, loaded.version(), envelopes, GameRepositoryPort.SnapshotPolicy.FORCE);
            EventPublication.publish(publisher, envelopes);
            return ActionResult.accepted(
                    gameId, committed.version(), committed.eventIds(), committed.eventTypes());
        } finally {
            lock.unlock(token);
        }
    }

    /** 快照之后的事件必须严格连续：出现空洞说明事件流被裁剪或并发写坏了，宁可失败也不继续。 */
    private void assertTailContiguous(String gameId, int snapshotVersion, int streamVersion) {
        if (streamVersion == snapshotVersion) {
            return;
        }
        List<EventEnvelope> tail = eventStore.read(gameId, snapshotVersion + 1, streamVersion);
        int expected = snapshotVersion + 1;
        for (EventEnvelope envelope : tail) {
            envelope.assertFollows(expected);
            expected++;
        }
        if (expected != streamVersion + 1) {
            throw GameException.Factory.replayOutOfOrder(streamVersion, expected - 1);
        }
    }
}
