package com.wolfmind.infrastructure.archive;

import com.wolfmind.application.port.ReplayArchivePort;
import com.wolfmind.domain.game.event.EventEnvelope;
import com.wolfmind.infrastructure.sqlite.SqliteArchiveRepository;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 归档回放的 SQLite 实现（api-spec.md §6）：把 {@code games} 与 {@code game_events} 组合成
 * 回放接口需要的样子。
 *
 * <p><b>不新增 SQL。</b>它复用 {@link SqliteArchiveRepository} 已有的
 * {@code game(...)} 与 {@code readRange(...)}：座位表来自归档里的第一条事件
 * （{@code GAME_STARTED} 的 {@code seats}），版本水位来自 {@code games.version}。
 * 为一个只读适配器另写一份查询，会让"归档列的含义"出现第二个解释。
 *
 * <p><b>归档不完整时返回空。</b>{@code games} 行只在"事件全部写入且核对通过"之后才写
 * （见 {@code TerminalGameArchiver}），因此"有行"就等于"这一局的归档是完整的"；
 * 没有行时回放必须继续走事件流或报 404，而不是拿半份归档渲染出一局不完整的对局。
 */
public final class SqliteReplayArchive implements ReplayArchivePort {

    private final SqliteArchiveRepository archive;

    public SqliteReplayArchive(SqliteArchiveRepository archive) {
        this.archive = archive;
    }

    @Override
    public Optional<ArchivedGame> game(String gameId) {
        if (gameId == null || gameId.isBlank()) {
            return Optional.empty();
        }
        return archive.game(gameId).map(record -> new ArchivedGame(
                record.gameId(),
                record.status(),
                record.phase(),
                record.round(),
                record.version(),
                record.winner(),
                seatsOf(gameId)));
    }

    @Override
    public Optional<ArchivedGame> completedGame(String gameId) {
        return archive.game(gameId).filter(record -> record.archivedThrough(record.version()))
                .filter(record -> archive.archivedCount(gameId) == record.version()
                        && archive.versionGaps(gameId).isEmpty()
                        && !archive.readRange(gameId, 1, 1).isEmpty())
                .flatMap(record -> game(gameId));
    }

    @Override public List<String> completedGameIds() { return archive.completedGameIds(); }

    @Override
    public List<EventEnvelope> events(String gameId, int fromVersion, int toVersion, int maxEvents) {
        int limit = maxEvents <= 0 ? SqliteArchiveRepository.MAX_BATCH_SIZE : maxEvents;
        int to = toVersion <= 0 ? Integer.MAX_VALUE : toVersion;
        List<EventEnvelope> all = archive.readRange(gameId, Math.max(1, fromVersion), to);
        return all.size() <= limit ? all : List.copyOf(all.subList(0, limit));
    }

    /**
     * 从 {@code GAME_STARTED} 读出座位表。
     *
     * <p>只读版本 1：{@code GAME_STARTED} 的契约是"开局的第一条事件"，
     * 全域扫描会把一次 O(1) 的读取变成 O(整局)。
     */
    private List<Seat> seatsOf(String gameId) {
        List<EventEnvelope> start = archive.readRange(gameId, 1, 1);
        if (start.isEmpty() || !"GAME_STARTED".equals(start.get(0).type())) {
            return List.of();
        }
        Object value = start.get(0).payload().get("seats");
        if (!(value instanceof List<?> list)) {
            return List.of();
        }
        List<Seat> seats = new ArrayList<>(list.size());
        for (Object item : list) {
            if (!(item instanceof Map<?, ?> seat)) {
                continue;
            }
            seats.add(new Seat(
                    intOf(seat.get("seatNo")),
                    stringOf(seat.get("playerId")),
                    stringOf(seat.get("controllerType")),
                    stringOf(seat.get("refId"))));
        }
        return List.copyOf(seats);
    }

    private static int intOf(Object value) {
        return value instanceof Number number ? number.intValue() : 0;
    }

    private static String stringOf(Object value) {
        return value instanceof String text ? text : null;
    }
}
