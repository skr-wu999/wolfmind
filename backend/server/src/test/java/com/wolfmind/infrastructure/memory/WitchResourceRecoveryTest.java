package com.wolfmind.infrastructure.memory;

import com.wolfmind.application.game.SubmitActionCommand;
import com.wolfmind.application.port.SnapshotStorePort;
import com.wolfmind.domain.game.model.*;
import com.wolfmind.infrastructure.agent.wiring.InfrastructureGameHarness;
import com.wolfmind.infrastructure.json.Json;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class WitchResourceRecoveryTest {
    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void legacySnapshotsRebuildConsumedMedicineAndNewSnapshotsRestoreIncrementally(boolean legacy) {
        var harness = new InfrastructureGameHarness(1_730_000_000_000L);
        String id = "g_witch_resource";
        harness.startNineAiGame(id, RoleComposition.STANDARD_NINE, GameRules.DEFAULT, 42);
        while (harness.repository().load(id).game().phase() != GamePhase.NIGHT_WITCH) {
            var game = harness.repository().load(id).game();
            var state = game.snapshot();
            int seat = game.phase() == GamePhase.NIGHT_WOLF ? game.wolvesAwaitingNomination().getFirst().value()
                    : state.players().stream().filter(p -> p.role() == Role.SEER).findFirst().orElseThrow().seatNo().value();
            Map<String, Object> params = game.phase() == GamePhase.NIGHT_WOLF ? Map.of("targetSeatNo",
                    state.players().stream().filter(p -> p.role() == Role.VILLAGER).findFirst().orElseThrow().seatNo().value()) : Map.of();
            harness.submitService().submit(new SubmitActionCommand(id, "p_" + seat,
                    game.phase() == GamePhase.NIGHT_WOLF ? "WOLF_KILL" : "SKIP", params, game.version(), "prepare-" + game.version(), "prepare"));
        }
        var game = harness.repository().load(id).game();
        int witch = game.snapshot().players().stream().filter(p -> p.role() == Role.WITCH).findFirst().orElseThrow().seatNo().value();
        harness.submitService().submit(new SubmitActionCommand(id, "p_" + witch, "WITCH_SAVE",
                Map.of("targetSeatNo", game.snapshot().wolfKillTarget()), game.version(), "save", "save"));
        var expected = harness.repository().load(id).game().snapshot();
        var encoded = Json.readObject(GameSnapshotCodec.encode(expected));
        if (legacy) {
            encoded.remove("skillStateSchemaVersion");
            for (Object raw : (List<?>) encoded.get("players")) {
                @SuppressWarnings("unchecked") var player = (Map<String, Object>) raw;
                if ("WITCH".equals(player.get("role"))) {
                    @SuppressWarnings("unchecked") var skill = (Map<String, Object>) player.get("skillState");
                    skill.put("antidoteAvailable", true);
                }
            }
        }
        String text = Json.write(encoded);
        var snapshots = new InMemorySnapshotStore();
        snapshots.save(new SnapshotStorePort.Snapshot(id, expected.version(), text, GameSnapshotCodec.checksum(text)));
        var repository = new InMemoryGameRepository(harness.eventStore(), snapshots, new InMemoryGameStateCache());
        var restored = repository.load(id).game().snapshot();
        assertEquals(expected, restored);
        assertFalse(restored.playerAt(SeatNo.of(witch)).skillState().antidoteAvailable());
        assertEquals(legacy ? 1 : 0, repository.fullReplayLoadCount());
        assertEquals(legacy ? 0 : 1, repository.incrementalLoadCount());
    }
}
