package com.wolfmind.application.game;

import com.wolfmind.application.port.ArchivedEventRestorePort;
import com.wolfmind.application.port.ArchivedGameRecoveryPort;
import com.wolfmind.application.port.EventStorePort;
import com.wolfmind.application.port.ReplayArchivePort;
import com.wolfmind.domain.game.event.EventEnvelope;
import com.wolfmind.domain.game.event.EventType;
import com.wolfmind.domain.game.model.GameException;
import com.wolfmind.domain.game.model.GameId;
import com.wolfmind.domain.game.model.DomainErrorCode;

import java.util.List;
import java.util.Objects;

/** 恢复存储副本，不产生新的领域事实；终局状态由完整事件重放验证。 */
public final class RestoreArchivedGameApplicationService implements ArchivedGameRecoveryPort {
    private final EventStorePort store;
    private final ReplayArchivePort archive;
    private final ArchivedEventRestorePort restore;

    public RestoreArchivedGameApplicationService(EventStorePort store, ReplayArchivePort archive,
                                                 ArchivedEventRestorePort restore) {
        this.store = Objects.requireNonNull(store, "store");
        this.archive = Objects.requireNonNull(archive, "archive");
        this.restore = Objects.requireNonNull(restore, "restore");
    }

    @Override
    public boolean restoreMissing(String gameId) {
        GameId id = GameId.of(gameId);
        if (store.exists(gameId)) return true;
        var header = archive.game(gameId).orElse(null);
        if (header == null) return false;
        if (!gameId.equals(header.gameId()) || !header.terminal() || header.version() < 1
                || archive.completedGame(gameId).filter(header::equals).isEmpty()) {
            throw invalid(gameId, "归档没有完整终局标记");
        }
        List<EventEnvelope> events = archive.events(gameId, 1, Integer.MAX_VALUE, Integer.MAX_VALUE);
        if (events.size() != header.version() || events.isEmpty()
                || events.getFirst().eventType() != EventType.GAME_STARTED
                || (events.getLast().eventType() != EventType.GAME_FINISHED
                    && events.getLast().eventType() != EventType.GAME_ABORTED)) {
            throw invalid(gameId, "事件条数或首尾事件与归档不一致");
        }
        var state = CommittedEventReplay.state(gameId,events,header.version());
        if (state.version() != header.version() || !state.status().name().equals(header.status())
                || !state.phase().name().equals(header.phase()) || state.round() != header.round()
                || !Objects.equals(state.winner() == null ? null : state.winner().name(), header.winner())) {
            throw invalid(gameId, "重放状态与归档头部不一致");
        }
        restore.restoreAbsent(gameId, events);
        if (!store.exists(gameId)) throw new IllegalStateException("归档恢复后事件流仍不存在：" + gameId);
        return true;
    }

    private static GameException invalid(String gameId, String reason) {
        return new GameException(DomainErrorCode.REPLAY_OUT_OF_ORDER,
                "拒绝恢复对局 " + gameId + "：" + reason);
    }
}
