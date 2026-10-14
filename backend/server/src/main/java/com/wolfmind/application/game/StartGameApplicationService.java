package com.wolfmind.application.game;

import com.wolfmind.application.port.EventPublisherPort;
import com.wolfmind.application.port.EventStorePort;
import com.wolfmind.application.port.GameLockPort;
import com.wolfmind.application.port.GameRepositoryPort;
import com.wolfmind.application.port.TimePort;
import com.wolfmind.domain.game.event.DomainEvent;
import com.wolfmind.domain.game.event.EventEnvelope;
import com.wolfmind.domain.game.model.ActionContext;
import com.wolfmind.domain.game.model.DomainErrorCode;
import com.wolfmind.domain.game.model.Game;
import com.wolfmind.domain.game.model.GameException;
import com.wolfmind.domain.game.model.StartGameCommand;

import java.util.List;
import java.util.Objects;

/**
 * 开始对局用例（api-spec.md §5.1、ddd-design.md §6.2）。
 *
 * <p>输入就是领域命令 {@link StartGameCommand}（座位分配 + {@code RoleComposition} + {@code GameRules} 的载体），
 * 不在应用层重复定义同形 DTO：座位数与角色配置的一致性由领域在构造期校验（INV-1），
 * "全部就绪才能开局"同样由领域判定（INV-R2）。
 *
 * <p>本服务只负责：新对局的单写者排他、构造 {@link ActionContext}、调用
 * {@code create + start}、从版本 0 起提交 11 条事件（{@code GAME_STARTED} + 9×{@code ROLE_ASSIGNED}
 * + {@code PHASE_CHANGED}，版本 1..11 连续）、提交后发布。
 */
public final class StartGameApplicationService {

    private final GameLockPort lock;
    private final EventStorePort eventStore;
    private final GameRepositoryPort repository;
    private final EventPublisherPort publisher;
    private final TimePort time;

    public StartGameApplicationService(
            GameLockPort lock,
            EventStorePort eventStore,
            GameRepositoryPort repository,
            EventPublisherPort publisher,
            TimePort time) {
        this.lock = Objects.requireNonNull(lock, "lock");
        this.eventStore = Objects.requireNonNull(eventStore, "eventStore");
        this.repository = Objects.requireNonNull(repository, "repository");
        this.publisher = Objects.requireNonNull(publisher, "publisher");
        this.time = Objects.requireNonNull(time, "time");
    }

    /**
     * @param actorId 发起开局的身份（房主 userId；实验室批量对局为 {@code SYSTEM}），用于事件审计
     */
    public ActionResult start(StartGameCommand command, String actorId, String correlationId) {
        Objects.requireNonNull(command, "command");
        String gameId = command.gameId().value();

        GameLockPort.LockToken token = lock.tryLock(gameId)
                .orElseThrow(() -> GameException.Factory.busy(command.gameId()));
        try {
            // 事件流已存在说明这个 gameId 已经开过局：角色分配不可重做（INV-2）。
            // 用领域错误码 GAME_ALREADY_STARTED 表达，而不是让存储层的版本冲突去解释。
            if (eventStore.exists(gameId)) {
                throw GameException.Factory.alreadyStarted();
            }

            long occurredAt = time.nowMillis();
            String commandId = "start:" + gameId;
            ActionContext ctx = ActionContext.of(
                            CommandIdentifiers.eventIdBase(gameId, 1),
                            occurredAt,
                            actorId,
                            commandId,
                            CommandIdentifiers.correlationId(correlationId, commandId),
                            0)
                    .withRandomSeed(CommandIdentifiers.randomSeed(gameId, commandId, occurredAt));

            Game game = Game.create(command.gameId());
            List<DomainEvent> events = game.start(command, ctx);
            if (events.isEmpty()) {
                // 开局必须产生事件（INV-11）：这里静默返回会让存储层留下一个"存在但没有事件"的对局。
                throw new GameException(
                        DomainErrorCode.INTERNAL_ERROR, "开局未产生任何事件，违反 INV-11");
            }

            List<EventEnvelope> envelopes = EventEnvelopes.wrap(gameId, 1, events);
            GameRepositoryPort.CommitResult committed =
                    repository.commit(gameId, 0, envelopes, GameRepositoryPort.SnapshotPolicy.INTERVAL);
            EventPublication.publish(publisher, envelopes);
            return ActionResult.accepted(
                    gameId, committed.version(), committed.eventIds(), committed.eventTypes());
        } finally {
            lock.unlock(token);
        }
    }
}
