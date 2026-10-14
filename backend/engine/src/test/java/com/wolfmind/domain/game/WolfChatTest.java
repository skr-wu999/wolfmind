package com.wolfmind.domain.game;

import com.wolfmind.domain.game.event.DomainEvent;
import com.wolfmind.domain.game.event.EventType;
import com.wolfmind.domain.game.event.WolfChatSentEvent;
import com.wolfmind.domain.game.model.ActionContext;
import com.wolfmind.domain.game.model.DomainErrorCode;
import com.wolfmind.domain.game.model.Game;
import com.wolfmind.domain.game.model.GameException;
import com.wolfmind.domain.game.model.GameId;
import com.wolfmind.domain.game.model.GamePhase;
import com.wolfmind.domain.game.model.GameRules;
import com.wolfmind.domain.game.model.GameSnapshot;
import com.wolfmind.domain.game.model.GameStatus;
import com.wolfmind.domain.game.model.PlayerSnapshot;
import com.wolfmind.domain.game.model.Role;
import com.wolfmind.domain.game.model.RoleComposition;
import com.wolfmind.domain.game.model.SeatAssignment;
import com.wolfmind.domain.game.model.SkipAction;
import com.wolfmind.domain.game.model.StartGameCommand;
import com.wolfmind.domain.game.model.Visibility;
import com.wolfmind.domain.game.model.WolfChatAction;
import com.wolfmind.domain.game.model.WolfKillAction;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 狼人夜聊：只在 NIGHT_WOLF、只限存活狼人、每条 1..500 字、每人每晚 20 条。 */
class WolfChatTest {

    private static final long SEED = 20240501L;
    private static final long T0 = 1_730_000_000_000L;

    @Test
    @DisplayName("存活狼人在 NIGHT_WOLF 夜聊：一条 WOLF 事件，阶段与提名不变，stateVersion 不前进")
    void aliveWolfChatsWithoutAdvancing() {
        Game game = started("g_chat_1");
        int wolf = wolves(game).get(0);
        int before = game.version();

        List<DomainEvent> events = game.handle(WolfChatAction.of(wolf, "  今晚刀 5 号  "), ctx(before));

        assertEquals(1, events.size());
        WolfChatSentEvent chat = (WolfChatSentEvent) events.get(0);
        assertEquals(EventType.WOLF_CHAT_SENT, chat.type());
        assertEquals(Visibility.WOLF, chat.visibility());
        assertEquals("今晚刀 5 号", chat.content(), "首尾空白按发言同一口径去掉");
        assertEquals(wolf, chat.wolfSeatNo().value());
        assertEquals("g_chat_1:" + (before + 1), chat.chatId());
        assertEquals(before + 1, game.version());

        GameSnapshot snapshot = game.snapshot();
        assertEquals(GamePhase.NIGHT_WOLF, snapshot.phase());
        assertTrue(snapshot.wolfNominations().isEmpty(), "夜聊不是提名");
        assertEquals(1, snapshot.wolfChatCount(wolf));
        assertEquals(before, snapshot.stateVersion(), "只有夜聊时 stateVersion 停在聊天之前");

        game.handle(WolfKillAction.at(wolf, villagers(game).get(0)), ctx(game.version()));
        assertEquals(game.version(), game.snapshot().stateVersion(), "非夜聊事件把 stateVersion 推到当前版本");
    }

    @Test
    @DisplayName("非狼人不能夜聊")
    void nonWolfRejected() {
        Game game = started("g_chat_2");
        int villager = villagers(game).get(0);
        GameException error = assertThrows(GameException.class,
                () -> game.handle(WolfChatAction.of(villager, "我也想听"), ctx(game.version())));
        assertEquals(DomainErrorCode.ACTION_NOT_ALLOWED, error.code());
        assertTrue(game.pendingEvents().isEmpty());
    }

    @Test
    @DisplayName("狼刀定案后进入 NIGHT_SEER，夜聊随阶段关闭")
    void closedOutsideNightWolf() {
        Game game = started("g_chat_3");
        int victim = villagers(game).get(0);
        for (int wolf : wolves(game)) {
            game.handle(WolfKillAction.at(wolf, victim), ctx(game.version()));
        }
        assertEquals(GamePhase.NIGHT_SEER, game.phase());

        int wolf = wolves(game).get(0);
        GameException error = assertThrows(GameException.class,
                () -> game.handle(WolfChatAction.of(wolf, "还在吗"), ctx(game.version())));
        assertEquals(DomainErrorCode.ACTION_NOT_ALLOWED, error.code());
    }

    @Test
    @DisplayName("内容为空白或超过 500 字被拒绝")
    void contentLengthChecked() {
        Game game = started("g_chat_4");
        int wolf = wolves(game).get(0);

        GameException blank = assertThrows(GameException.class, () -> WolfChatAction.of(wolf, "   "));
        assertEquals(DomainErrorCode.SPEECH_INVALID, blank.code());

        GameException tooLong = assertThrows(GameException.class,
                () -> game.handle(WolfChatAction.of(wolf, "狼".repeat(501)), ctx(game.version())));
        assertEquals(DomainErrorCode.SPEECH_INVALID, tooLong.code());

        game.handle(WolfChatAction.of(wolf, "狼".repeat(500)), ctx(game.version()));
        assertEquals(1, game.snapshot().wolfChatCount(wolf));
    }

    @Test
    @DisplayName("每人每晚 20 条：第 21 条被拒绝，其他狼人不受影响")
    void perNightLimit() {
        Game game = started("g_chat_5");
        int wolf = wolves(game).get(0);
        int teammate = wolves(game).get(1);
        for (int index = 0; index < WolfChatAction.MAX_PER_NIGHT; index++) {
            game.handle(WolfChatAction.of(wolf, "第 " + index + " 条"), ctx(game.version()));
        }

        GameException error = assertThrows(GameException.class,
                () -> game.handle(WolfChatAction.of(wolf, "第 21 条"), ctx(game.version())));
        assertEquals(DomainErrorCode.ACTION_NOT_ALLOWED, error.code());

        game.handle(WolfChatAction.of(teammate, "我还能说"), ctx(game.version()));
        assertEquals(20, game.snapshot().wolfChatCount(wolf));
        assertEquals(1, game.snapshot().wolfChatCount(teammate));
    }

    @Test
    @DisplayName("计数在下一晚进入 NIGHT_WOLF 时清零")
    void countResetsNextNight() {
        Game game = started("g_chat_6");
        int wolf = wolves(game).get(0);
        game.handle(WolfChatAction.of(wolf, "第一晚"), ctx(game.version()));
        assertEquals(1, game.snapshot().wolfChatCount(wolf));

        skipUntilNight(game, 2);

        assertEquals(GamePhase.NIGHT_WOLF, game.phase());
        assertEquals(2, game.round());
        assertTrue(game.snapshot().wolfChatCounts().isEmpty(), "新的一晚重新计数");
    }

    @Test
    @DisplayName("夜聊可重放：全量重放与快照恢复得到同一状态")
    void replayAndRestoreAreEquivalent() {
        GameId gameId = GameId.of("g_chat_7");
        Game game = Game.create(gameId);
        List<DomainEvent> history = new ArrayList<>(game.start(command(gameId), ctx(0)));
        List<Integer> wolves = wolves(game);
        history.addAll(game.handle(WolfChatAction.of(wolves.get(0), "刀谁"), ctx(game.version())));
        history.addAll(game.handle(WolfChatAction.of(wolves.get(1), "刀预言家"), ctx(game.version())));
        history.addAll(game.handle(WolfKillAction.at(wolves.get(0), villagers(game).get(0)), ctx(game.version())));
        history.addAll(game.handle(WolfChatAction.of(wolves.get(2), "同意"), ctx(game.version())));

        Game replayed = Game.create(gameId);
        history.forEach(replayed::replay);
        assertEquals(game.snapshot(), replayed.snapshot());
        assertEquals(game.version() - 1, replayed.snapshot().stateVersion());

        Game restored = Game.restore(game.snapshot());
        assertEquals(game.snapshot(), restored.snapshot());
    }

    // ---------------------------------------------------------------- 夹具

    private static Game started(String id) {
        GameId gameId = GameId.of(id);
        Game game = Game.create(gameId);
        game.start(command(gameId), ctx(0));
        assertEquals(GamePhase.NIGHT_WOLF, game.phase());
        return game;
    }

    private static StartGameCommand command(GameId gameId) {
        List<SeatAssignment> seats = new ArrayList<>();
        for (int seat = 1; seat <= 9; seat++) {
            seats.add(SeatAssignment.ai(seat, "agent_" + seat));
        }
        return new StartGameCommand(gameId, "room_1", seats, RoleComposition.STANDARD_NINE, GameRules.DEFAULT);
    }

    private static ActionContext ctx(int expectedVersion) {
        return new ActionContext(
                "cmd_" + expectedVersion, T0 + expectedVersion, "SYSTEM",
                "cmd_" + expectedVersion, "cor_1", expectedVersion, SEED);
    }

    private static List<Integer> wolves(Game game) {
        return game.snapshot().players().stream()
                .filter(player -> player.alive() && player.role() == Role.WEREWOLF)
                .map(player -> player.seatNo().value())
                .sorted()
                .toList();
    }

    private static List<Integer> villagers(Game game) {
        return game.snapshot().players().stream()
                .filter(player -> player.alive() && !player.isWolf())
                .map(PlayerSnapshot::seatNo)
                .map(seat -> seat.value())
                .sorted()
                .toList();
    }

    /** 全员超时跳过直到第 {@code round} 晚的 NIGHT_WOLF。 */
    private static void skipUntilNight(Game game, int round) {
        for (int guard = 0; guard < 200; guard++) {
            if (game.status() != GameStatus.RUNNING) {
                throw new AssertionError("对局在第 " + round + " 晚之前结束");
            }
            if (game.phase() == GamePhase.NIGHT_WOLF && game.round() == round) {
                return;
            }
            var handling = game.timeoutSkips(Long.MAX_VALUE / 2, true);
            for (SkipAction skip : handling.skips()) {
                game.handle(skip, ctx(game.version()));
            }
            if (handling.skips().isEmpty()) {
                game.advance(ctx(game.version()));
            }
        }
        throw new AssertionError("200 步内没有到达第 " + round + " 晚");
    }
}
