package com.wolfmind.application.game;

import com.wolfmind.application.fake.FakeGameViewQuery;
import com.wolfmind.domain.game.model.GamePhase;
import com.wolfmind.domain.game.model.PlayerId;
import com.wolfmind.domain.game.model.ViewerContext;
import com.wolfmind.domain.game.view.GameViewSnapshot;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** {@link GetGameViewApplicationService}：只做委托，分页参数按 api-spec.md §1 归一化。 */
class GetGameViewApplicationServiceTest {

    private static final String GAME_ID = "g_view_1";

    private FakeGameViewQuery query;
    private GetGameViewApplicationService service;

    @BeforeEach
    void setUp() {
        query = new FakeGameViewQuery();
        service = new GetGameViewApplicationService(query);
    }

    @Test
    @DisplayName("view：原样委托并把读取者身份传下去（脱敏不在应用层重复实现）")
    void viewDelegates() {
        GameViewSnapshot expected = FakeGameViewQuery.sampleView(GAME_ID, 12);
        query.setView(expected);
        ViewerContext viewer =
                ViewerContext.participant("u_1", Set.of(PlayerId.of("p_1")), Set.of(), false);

        GameViewSnapshot actual = service.view(GAME_ID, viewer);

        assertSame(expected, actual);
        assertEquals(List.of(GAME_ID), query.viewCalls());
        assertSame(viewer, query.lastViewer());
    }

    @Test
    @DisplayName("replay：区间与上限原样传给查询端口")
    void replayDelegates() {
        ViewerContext viewer = ViewerContext.spectator("u_9");

        service.replay(GAME_ID, viewer, 3, 20, 500);

        assertEquals(List.of(GAME_ID + ":3-20:500"), query.replayCalls());
        assertSame(viewer, query.lastViewer());
    }

    @Test
    @DisplayName("listGames：缺省分页补 20，超过上限收敛到 100，页码最小为 1")
    void listGamesNormalizesPaging() {
        service.listGames("RUNNING", null, 0, 0);
        service.listGames("RUNNING", null, 2, 5000);
        service.listGames(null, "room-1", 1, 20);

        assertEquals("RUNNING/null/1/20", query.listCalls().get(0));
        assertEquals("RUNNING/null/2/100", query.listCalls().get(1));
        assertEquals("null/room-1/1/20", query.listCalls().get(2));
        assertEquals(1, service.listGames("RUNNING", null, 1, 20).size());
    }

    @Test
    @DisplayName("示例视图给出 api-spec.md §5.2 的字段名（编译期即契约）")
    void sampleViewShape() {
        GameViewSnapshot view = FakeGameViewQuery.sampleView(GAME_ID, 12);

        assertEquals(GAME_ID, view.gameId());
        assertEquals(12, view.version());
        assertEquals(GamePhase.NIGHT_WOLF, view.phase());
        assertEquals(List.of(1, 2), view.turnOrder());
        assertEquals(Map.of("speechMaxLength", 500), view.rules());
        assertTrue(view.players().isEmpty());
    }
}
