package com.wolfmind.application.user;

import com.wolfmind.application.port.GameViewQueryPort;
import com.wolfmind.domain.game.event.EventEnvelope;
import com.wolfmind.domain.game.model.PlayerId;
import com.wolfmind.domain.game.model.ViewerContext;
import com.wolfmind.domain.game.view.GameViewSnapshot;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ActiveGameLookupTest {

    /** 运行中对局按页返回；参与关系按 (gameId, userId) 给出。 */
    private static final class FakeViews implements GameViewQueryPort {
        final List<String> running = new ArrayList<>();
        final Map<String, String> seatedUserByGame;
        final List<String> requestedStatuses = new ArrayList<>();

        FakeViews(Map<String, String> seatedUserByGame) {
            this.seatedUserByGame = seatedUserByGame;
        }

        @Override
        public List<Map<String, Object>> listGames(String status, String roomId, int page, int pageSize) {
            requestedStatuses.add(status);
            int from = Math.min((page - 1) * pageSize, running.size());
            int to = Math.min(from + pageSize, running.size());
            return running.subList(from, to).stream()
                    .map(id -> Map.<String, Object>of("gameId", id, "status", "RUNNING"))
                    .toList();
        }

        @Override
        public ViewerContext viewerContextFor(String gameId, String viewerUserId) {
            return viewerUserId.equals(seatedUserByGame.get(gameId))
                    ? ViewerContext.participant(viewerUserId, Set.of(PlayerId.of("p_1")), Set.of(), false)
                    : ViewerContext.participant(viewerUserId, Set.of(), Set.of(), false);
        }

        @Override
        public GameViewSnapshot view(String gameId, ViewerContext viewer) {
            throw new UnsupportedOperationException();
        }

        @Override
        public List<EventEnvelope> replay(String gameId, ViewerContext viewer, int from, int to, int max) {
            throw new UnsupportedOperationException();
        }

        @Override
        public GameViewSnapshot viewAt(String gameId, ViewerContext viewer, int atVersion) {
            throw new UnsupportedOperationException();
        }

        @Override
        public int currentVersion(String gameId) {
            throw new UnsupportedOperationException();
        }
    }

    @Test
    void findsTheRunningGameWhereTheUserControlsASeat() {
        FakeViews views = new FakeViews(Map.of("g_2", "u_alice", "g_3", "u_bob"));
        views.running.addAll(List.of("g_1", "g_2", "g_3"));
        ActiveGameLookup lookup = ActiveGameLookup.fromRunningGames(views);

        assertEquals(Optional.of("g_2"), lookup.activeGameOf("u_alice"));
        assertEquals(Optional.of("g_3"), lookup.activeGameOf("u_bob"));
        assertEquals(Optional.empty(), lookup.activeGameOf("u_carol"));
        assertTrue(views.requestedStatuses.stream().allMatch("RUNNING"::equals),
                "只能在进行中的对局里找，已结束的对局不能成为继续对局入口");
    }

    @Test
    void scansPastTheFirstPage() {
        FakeViews views = new FakeViews(Map.of("g_150", "u_alice"));
        for (int i = 1; i <= 150; i++) {
            views.running.add("g_" + i);
        }

        assertEquals(Optional.of("g_150"), ActiveGameLookup.fromRunningGames(views).activeGameOf("u_alice"));
    }

    @Test
    void blankUserHasNoActiveGame() {
        FakeViews views = new FakeViews(Map.of());
        views.running.add("g_1");

        assertEquals(Optional.empty(), ActiveGameLookup.fromRunningGames(views).activeGameOf(" "));
        assertTrue(views.requestedStatuses.isEmpty());
    }
}
