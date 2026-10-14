package com.wolfmind.infrastructure.memory;

import com.wolfmind.domain.game.event.EventEnvelope;
import com.wolfmind.domain.game.model.DomainErrorCode;
import com.wolfmind.domain.game.model.GameException;
import com.wolfmind.domain.game.model.PlayerId;
import com.wolfmind.domain.game.model.ViewerContext;
import com.wolfmind.domain.game.view.GameViewSnapshot;
import com.wolfmind.infrastructure.InfrastructureTestEvents;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 视图查询：读取者上下文的构造规则与可见性委托。
 *
 * <p>重点在"身份从哪来"。隐藏信息的读取权不能由请求方声明，因此这里守住两件事：
 * 参与者与观战者不能同时成立，且狼队身份只在读取者确实控制狼人座位时才填充。
 */
class InMemoryGameViewQueryTest {

    private static final String GAME_ID = "g_view_0001";

    private InMemoryEventStore eventStore;
    private InMemoryGameRepository repository;
    private InMemoryGameViewQuery query;

    @BeforeEach
    void seedStartedGame() {
        eventStore = new InMemoryEventStore();
        repository = new InMemoryGameRepository(
                eventStore, new InMemorySnapshotStore(), new InMemoryGameStateCache(), 50);
        query = new InMemoryGameViewQuery(eventStore, repository);
        List<EventEnvelope> events = InfrastructureTestEvents.startEventStream(GAME_ID);
        repository.commit(GAME_ID, 0, events,
                com.wolfmind.application.port.GameRepositoryPort.SnapshotPolicy.INTERVAL);
    }

    @Test
    @DisplayName("参与者视角：只看到自己的角色，其他玩家角色为 null")
    void participantSeesOnlyOwnRole() {
        ViewerContext villager = query.contextFor(GAME_ID, "u_1", Set.of(PlayerId.of("p_1")), false);

        assertFalse(villager.spectator());
        assertTrue(villager.isParticipant());
        assertTrue(villager.wolfPlayerIds().isEmpty(), "村民不得拿到狼队座位集合");

        GameViewSnapshot view = query.view(GAME_ID, villager);
        assertNotNull(view.me(), "参与者必须有 me");
        assertNotNull(view.me().role(), "自己的角色可见");
        long visibleRoles = view.players().stream().filter(player -> player.role() != null).count();
        assertEquals(1, visibleRoles, "只能看到自己的角色，其余为 null");
    }

    @Test
    @DisplayName("狼人视角：可见队友角色，且 wolfPlayerIds 被填充")
    void wolfSeesTeammates() {
        // 先找出一个真实狼人座位，避免把断言绑死在固定座位号上
        GameViewSnapshot asSpectator = query.view(GAME_ID, new ViewerContext("u_x", Set.of(), Set.of(), false, true));
        var wolfSeats = asSpectator.players().stream()
                .filter(player -> player.role() == com.wolfmind.domain.game.model.Role.WEREWOLF)
                .toList();
        assertEquals(3, wolfSeats.size(), "标准局应有 3 只狼");
        PlayerId wolfId = wolfSeats.get(0).playerId();

        ViewerContext wolf = query.contextFor(GAME_ID, "u_wolf", Set.of(wolfId), false);
        assertEquals(Set.of(wolfId), wolf.wolfPlayerIds(), "狼队集合只含该读取者控制的狼座位");

        GameViewSnapshot view = query.view(GAME_ID, wolf);
        long visibleRoles = view.players().stream().filter(player -> player.role() != null).count();
        assertEquals(3, visibleRoles, "狼人可见全部 3 只狼，看不到村民角色");
    }

    @Test
    @DisplayName("观战者与参与者不能同时成立：参数自相矛盾时直接拒绝")
    void rejectsParticipantAndSpectatorAtOnce() {
        // 这是提权路径：如果"我是观战者"可以客户端声明，同时又是参与者，就能拿到上帝视角。
        GameException error = assertThrows(GameException.class,
                () -> query.contextFor(GAME_ID, "u_1", Set.of(PlayerId.of("p_1")), true));
        assertEquals(DomainErrorCode.VALIDATION_ERROR, error.code());
        assertTrue(error.getMessage().contains("观战者"), "错误信息要说明冲突原因：" + error.getMessage());
    }

    @Test
    @DisplayName("进行中观战不披露角色与库存，且 me 为 null")
    void spectatorSeesNoRoles() {
        ViewerContext spectator = query.contextFor(GAME_ID, "u_watcher", Set.of(), true);

        assertTrue(spectator.spectator());
        GameViewSnapshot view = query.view(GAME_ID, spectator);
        assertTrue(view.spectator());
        assertNull(view.me(), "观战者没有座位，me 必须为 null");
        assertEquals(9, view.players().size());
        assertTrue(view.players().stream().allMatch(player -> player.role() == null && player.skillState() == null));
        var revealed = query.view(GAME_ID, new ViewerContext("u_watcher", Set.of(), Set.of(), true, true));
        assertTrue(revealed.players().stream().allMatch(player -> player.role() != null));
    }

    @Test
    @DisplayName("未登录读取者只能看 PUBLIC：匿名投影下全部角色为 null")
    void anonymousSeesNoRoles() {
        GameViewSnapshot view = query.view(GAME_ID, ViewerContext.anonymous());

        assertNull(view.me());
        assertTrue(view.players().stream().allMatch(player -> player.role() == null), "匿名读取者不得看到任何角色");
        assertFalse(view.spectator());
    }

    @Test
    @DisplayName("回放进行中匿名与观战者只看 PUBLIC，赛后全部可见，且遵守单次上限")
    void replayRespectsVisibilityAndLimit() {
        int publicEvents = query.replay(GAME_ID, ViewerContext.anonymous(), 1, Integer.MAX_VALUE, 500).size();
        assertEquals(2, publicEvents, "匿名读取者只能看到 2 条 PUBLIC 事件（GAME_STARTED 与 PHASE_CHANGED）");

        int allEvents = query.replay(GAME_ID, ViewerContext.spectator("u_x"), 1, Integer.MAX_VALUE, 500).size();
        assertEquals(publicEvents, allEvents, "进行中观战者只看 PUBLIC");

        var revealed = new ViewerContext("u_x", Set.of(), Set.of(), true, true);
        assertEquals(11, query.replay(GAME_ID, revealed, 1, Integer.MAX_VALUE, 500).size());
        int limited = query.replay(GAME_ID, revealed, 1, Integer.MAX_VALUE, 3).size();
        assertEquals(3, limited, "maxEvents 必须生效");
    }

    @Test
    @DisplayName("对局不存在时抛 GAME_NOT_RUNNING，而不是返回空视图")
    void unknownGameIsRejected() {
        GameException error = assertThrows(GameException.class, () -> query.view("g_missing", ViewerContext.anonymous()));
        assertEquals(DomainErrorCode.GAME_NOT_RUNNING, error.code());
        assertThrows(GameException.class,
                () -> query.replay("g_missing", ViewerContext.anonymous(), 1, 10, 500));
    }

    @Test
    @DisplayName("对局列表按状态过滤并分页")
    void listGamesFiltersAndPaginates() {
        List<java.util.Map<String, Object>> running = query.listGames("RUNNING", null, 1, 10);
        assertEquals(1, running.size());
        assertEquals(GAME_ID, running.get(0).get("gameId"));
        assertEquals("RUNNING", running.get(0).get("status"));

        assertTrue(query.listGames("FINISHED", null, 1, 10).isEmpty(), "状态过滤必须生效");
        assertTrue(query.listGames(null, null, 5, 10).isEmpty(), "超出范围的页返回空列表");
    }
}
