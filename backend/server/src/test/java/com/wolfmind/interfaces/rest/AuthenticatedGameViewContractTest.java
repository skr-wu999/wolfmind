package com.wolfmind.interfaces.rest;

import com.wolfmind.application.port.GameRepositoryPort;
import com.wolfmind.application.user.AuthApplicationService;
import com.wolfmind.domain.game.event.EventEnvelope;
import com.wolfmind.domain.game.model.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"wolfmind.auth.pbkdf2-iterations=1", "wolfmind.auth.viewer-resolver=session"})
class AuthenticatedGameViewContractTest {

    @Autowired TestRestTemplate rest;
    @Autowired AuthApplicationService auth;
    @Autowired GameRepositoryPort repository;
    private String gameId;
    private String token;

    @BeforeEach
    void startHumanSeerGame() {
        String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 10);
        var account = auth.register("view_" + suffix, "correct-horse", null);
        token = auth.login(account.username(), "correct-horse").sessionToken();
        gameId = "g_view_" + suffix;
        List<SeatAssignment> seats = new ArrayList<>();
        seats.add(SeatAssignment.human(1, account.userId()));
        for (int seat = 2; seat <= 9; seat++) seats.add(SeatAssignment.ai(seat, "p_" + seat));
        Game game = Game.create(GameId.of(gameId));
        game.start(new StartGameCommand(game.gameId(), "room_view", seats,
                        RoleComposition.STANDARD_NINE, GameRules.DEFAULT),
                context(0));
        commit(game, 0);
        assertEquals(Role.SEER, game.snapshot().playerAt(SeatNo.of(1)).role());
        var wolves = game.snapshot().players().stream().filter(PlayerSnapshot::isWolf).toList();
        for (var wolf : wolves) {
            game = repository.load(gameId).game();
            int expectedVersion = game.version();
            game.handle(SkipAction.voluntary(wolf.seatNo().value()), context(expectedVersion));
            commit(game, expectedVersion);
        }
        assertEquals(GamePhase.NIGHT_SEER, repository.load(gameId).game().phase());
    }

    private ActionContext context(int version) {
        return new ActionContext("view:" + version, System.currentTimeMillis(), "SYSTEM",
                "view:" + version, "view", version, 20240501L);
    }

    private void commit(Game game, int version) {
        List<EventEnvelope> events = new ArrayList<>();
        for (var event : game.pendingEvents()) events.add(EventEnvelope.of(event, gameId, ++version));
        repository.commit(gameId, events.getFirst().version() - 1, events, GameRepositoryPort.SnapshotPolicy.INTERVAL);
    }

    private HttpHeaders headers(String bearer) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        if (bearer != null) headers.setBearerAuth(bearer);
        return headers;
    }

    private Map<?, ?> get(String path, String bearer) {
        var response = rest.exchange(path, HttpMethod.GET, new HttpEntity<>(headers(bearer)), Map.class);
        assertEquals(HttpStatus.OK, response.getStatusCode());
        return response.getBody();
    }

    @Test
    void sessionResolvesOwnSeatAndPermissionsWithoutRevealingOthers() {
        Map<?, ?> view = get("/api/v1/games/" + gameId, token);
        assertEquals(1, ((Map<?, ?>) view.get("me")).get("seatNo"));
        assertEquals("SEER", ((Map<?, ?>) view.get("me")).get("role"));
        List<?> permissions = (List<?>) view.get("permissions");
        assertEquals("SEER_INSPECT", ((Map<?, ?>) permissions.getFirst()).get("type"));
        for (Object entry : (List<?>) view.get("players")) {
            Map<?, ?> player = (Map<?, ?>) entry;
            if (!Integer.valueOf(1).equals(player.get("seatNo"))) {
                assertNull(player.get("role"));
                assertNull(player.get("skillState"), "技能库存不能暴露隐藏的女巫或猎人身份");
            }
        }
    }

    @Test
    void replayReturnsOnlyOwnPrivateRoleAndAnonymousHasNoPermissions() {
        Map<?, ?> replay = get("/api/v1/games/" + gameId + "/replay?from=1&to=11", token);
        List<?> roles = ((List<?>) replay.get("events")).stream()
                .filter(entry -> "ROLE_ASSIGNED".equals(((Map<?, ?>) entry).get("type"))).toList();
        assertEquals(1, roles.size());
        assertEquals("p_1", ((Map<?, ?>) ((Map<?, ?>) roles.getFirst()).get("payload")).get("playerId"));
        Map<?, ?> anonymous = get("/api/v1/games/" + gameId, null);
        assertNull(anonymous.get("me"));
        assertEquals(List.of(), anonymous.get("permissions"));
        for (Object entry : (List<?>) anonymous.get("players")) {
            assertNull(((Map<?, ?>) entry).get("skillState"));
        }
    }

    @Test
    void includeViewUsesTheSameAuthenticatedSeat() {
        int version = repository.load(gameId).version();
        HttpHeaders headers = headers(token);
        headers.set("Idempotency-Key", "inspect:" + gameId);
        var response = rest.exchange("/api/v1/games/" + gameId + "/actions?include=view", HttpMethod.POST,
                new HttpEntity<>(Map.of("actionType", "SEER_INSPECT", "expectedVersion", version,
                        "params", Map.of("targetSeatNo", 2)), headers), Map.class);
        assertEquals(HttpStatus.OK, response.getStatusCode());
        Map<?, ?> view = (Map<?, ?>) response.getBody().get("view");
        assertEquals("SEER", ((Map<?, ?>) view.get("me")).get("role"));
        assertTrue(((Number) view.get("version")).intValue() > version);
    }
}
