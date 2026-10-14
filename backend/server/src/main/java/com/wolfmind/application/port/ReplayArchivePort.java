package com.wolfmind.application.port;

import com.wolfmind.domain.game.event.EventEnvelope;

import java.util.List;
import java.util.Optional;

/**
 * 归档回放端口（api-spec.md §6）：事件流已过期时，从 SQLite 归档读回放数据。
 *
 * <p><b>为什么回放需要它。</b>进行中的对局在 Redis 事件流里，而流有 TTL——
 * 归档完成后事件流 7 天过期、未归档不自动过期。没有这个端口的话，一个月前的对局
 * 在回放页上会变成 404，而它明明是永久归档的。
 *
 * <p><b>它只提供"读得回来"，不改变可见性。</b>过滤仍然只能走 {@code VisibilityFilter}
 * （AP-6）；归档里的事件信封带 {@code visibility}，因此过滤逻辑一行都不用重写。
 * 归档只收终局对局，因此读出来的读取者上下文必然是"赛后公开"——
 * 但这条判断仍由调用方按归档到的状态决定，而不是在这里假定。
 */
public interface ReplayArchivePort {

    /** 对局是否已归档；未归档（或归档未完成）时为空。 */
    Optional<ArchivedGame> game(String gameId);

    /** 恢复使用的已完成归档水位；实现还需核对持久化的完成标记。 */
    default Optional<ArchivedGame> completedGame(String gameId) {
        return game(gameId).filter(ArchivedGame::terminal);
    }
    default List<String> completedGameIds() { return List.of(); }

    /**
     * 读取归档事件的版本区间。
     *
     * @param maxEvents 单次上限；{@code <= 0} 时由实现取默认上限
     */
    List<EventEnvelope> events(String gameId, int fromVersion, int toVersion, int maxEvents);

    /**
     * 已归档对局的头部信息。
     *
     * @param seats 来自 {@code GAME_STARTED.seats}：回放接口要据此把账号映射回座位，
     *              否则"参与者能看到自己的私有事件"这条规则在归档数据上无法成立
     * @param version 已归档的最大版本（回放区间的水位）
     */
    record ArchivedGame(
            String gameId,
            String status,
            String phase,
            int round,
            int version,
            String winner,
            List<Seat> seats) {

        public ArchivedGame {
            seats = List.copyOf(seats == null ? List.of() : seats);
        }

        /** 是否已到终态；终态意味着回放按赛后公开处理。 */
        public boolean terminal() {
            return "FINISHED".equals(status) || "ABORTED".equals(status);
        }
    }

    /**
     * {@code GAME_STARTED.seats} 的一行。
     *
     * @param controllerType {@code HUMAN} / {@code AI} / {@code BOT}
     * @param refId          HUMAN 时是账号 id，AI 时是 {@code p_{seat}}；
     *                       只有前者能用来反查"这个账号坐了哪个座位"
     */
    record Seat(int seatNo, String playerId, String controllerType, String refId) {}
}
