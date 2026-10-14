package com.wolfmind.application.lab;

import com.wolfmind.application.agent.AgentRuntime;
import com.wolfmind.application.agent.llm.LlmSettings;
import com.wolfmind.application.agent.model.AgentConfig;
import com.wolfmind.application.agent.runtime.AIControllerAdapter;
import com.wolfmind.application.agent.runtime.AgentRuntimeImpl;
import com.wolfmind.application.agent.strategy.WolfMindStrategy;
import com.wolfmind.application.game.StartGameApplicationService;
import com.wolfmind.domain.game.model.GameStatus;
import com.wolfmind.infrastructure.agent.AgentViewAdapter;
import com.wolfmind.infrastructure.agent.ToolGatewayAdapter;
import com.wolfmind.infrastructure.agent.wiring.InfrastructureGameHarness;
import com.wolfmind.infrastructure.memory.InMemoryAgentTrace;
import com.wolfmind.infrastructure.memory.InMemoryLabStore;
import com.wolfmind.infrastructure.memory.InMemoryMemoryPort;
import com.wolfmind.testsupport.ControlledTurnDriver;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import com.wolfmind.application.agent.llm.LlmClient;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * AI 实验室的端到端验收（api-spec.md §8、AGENTS.md §11 Phase 5）。
 *
 * <p>批次创建的对局复用应用层规划/超时用例，由测试夹具
 * {@code ControlledTurnDriver} 推进、产生同样的领域事件、遵守同样的规则校验。一旦为实验室开一条捷径，
 * 这里的结论对真人对局就不再有效——而那正是实验室存在的意义。
 */
class LabBatchRunnerTest {

    private static final long START_MILLIS = 1_730_000_000_000L;

    private static final class Fixture {
        final InfrastructureGameHarness harness = new InfrastructureGameHarness(START_MILLIS);
        final AgentViewAdapter views = new AgentViewAdapter(harness.viewQuery());
        final InMemoryAgentTrace trace = new InMemoryAgentTrace();
        final ToolGatewayAdapter gateway = new ToolGatewayAdapter(
                views, harness.viewQuery(), harness.submitService(), harness.idempotency(), trace, harness.time());
        final AgentRuntimeImpl runtime = new AgentRuntimeImpl(
                views, new WolfMindStrategy(gateway), gateway, new InMemoryMemoryPort(), trace, null, harness.time());
        final ControlledTurnDriver driver = new ControlledTurnDriver(harness.repository(),
                new AIControllerAdapter(runtime, views, gateway, harness.time(),
                        AgentConfig.ruleBaseline("lab-test")),
                harness.time(), harness.publisher(), harness.eventStore(), harness.lock());
        final InMemoryLabStore store = new InMemoryLabStore();
        final StartGameApplicationService startGame = new StartGameApplicationService(
                harness.lock(), harness.eventStore(), harness.repository(), harness.publisher(), harness.time());
        final LabApplicationService commands =
                new LabApplicationService(store, harness.time(), 8);
        final LabQueryService queries = new LabQueryService(store, trace, LlmSettings.DISABLED);
        final LabBatchRunner runner = new LabBatchRunner(
                store, startGame, harness.repository(), runtime, LlmSettings.DISABLED, harness.time());

        /**
         * 推进所有运行中的对局到终态。
         *
         * <p>用共享应用层规划/超时用例的同步测试夹具 {@code ControlledTurnDriver} 而不是 runner 自己推：runner 的职责只是"把对局开出来"，
         * 推进对局是驱动的事——这条边界本身就是被验证的对象。
         */
        void playAll(int maxTicks) {
            for (int tick = 0; tick < maxTicks; tick++) {
                harness.advanceClock(200L);
                runner.tick();
                List<Map<String, Object>> running = harness.viewQuery().listGames("RUNNING", null, 1, 100);
                for (Map<String, Object> item : running) {
                    if (item.get("gameId") instanceof String id) {
                        driver.step(id);
                    }
                }
                boolean allSettled = store.batches(null, 1, 100).stream().allMatch(LabBatch::settled);
                if (allSettled && harness.viewQuery().listGames("RUNNING", null, 1, 100).isEmpty()) {
                    return;
                }
            }
        }
    }

    private static Map<String, Object> draft(String name, String role) {
        return Map.of(
                "name", name,
                "role", role,
                "model", "deepseek-flash",
                "temperature", 0.6,
                "strategyProfile", "BALANCED",
                "persona", "冷静、简洁",
                "memoryMode", "short",
                "promptTemplateId", "default");
    }

    @ParameterizedTest
    @EnumSource(LlmClient.InteractionMode.class)
    void launchedLabSeatsReceiveTheModeBudget(LlmClient.InteractionMode mode) {
        Fixture fixture = new Fixture();
        LabAgentConfig config = fixture.commands.saveConfig(draft("budget-test", "VILLAGER"), "u_1");
        var batch = fixture.commands.startBatch(Map.of("name", "budget-test", "gameCount", 1,
                "agentConfigIds", List.of(config.agentConfigId())), "u_1");
        var settings = new LlmSettings("mock", "", "", 30000L, 0, "fixed-model",
                Map.of(LlmSettings.INTERACTION_KEY, mode.name()));
        var runner = new LabBatchRunner(fixture.store, fixture.startGame, fixture.harness.repository(),
                fixture.runtime, settings, fixture.harness.time());
        runner.tick();
        var launched = fixture.store.batch(batch.batchId()).orElseThrow();
        var game = launched.games().get(0);
        var assigned = game.roleAssignments().get("VILLAGER");
        var actual = fixture.runtime.configOf(game.gameId(), "p_" + assigned.seatNo()).orElseThrow();
        assertEquals(config.agentConfigId(), actual.agentConfigId());
        assertEquals(config.model(), actual.model());
        assertEquals(mode == LlmClient.InteractionMode.FUNCTION_CALLING ? 4 : 2, actual.budget().maxLlmCalls());
        assertEquals(6, actual.budget().maxToolCalls());
        assertEquals(8000, actual.budget().maxTokens());
        assertEquals(1500L, actual.budget().softDeadlineMs());
        assertEquals(500L, actual.budget().submitReserveMs());
        fixture.harness.repository().load(game.gameId()).game().snapshot().players().stream()
                .filter(player -> player.role() != com.wolfmind.domain.game.model.Role.VILLAGER)
                .forEach(player -> assertFalse(fixture.runtime.configOf(game.gameId(), player.playerId().value())
                        .orElseThrow().usesLlm()));
    }

    @Test
    @DisplayName("保存配置：非法字段一次全部返回，合法配置拿到 id 且可读回")
    void saveConfigValidatesAndPersists() {
        Fixture fixture = new Fixture();

        var error = assertThrows(com.wolfmind.domain.game.model.GameException.class,
                () -> fixture.commands.saveConfig(Map.of("name", "", "role", "GUARD", "temperature", 5), "u_1"));
        assertEquals(com.wolfmind.domain.game.model.DomainErrorCode.VALIDATION_ERROR, error.code());
        assertTrue(error.details().size() >= 4,
                "一次返回全部字段问题，避免客户端改一个报一个：" + error.details());

        LabAgentConfig saved = fixture.commands.saveConfig(draft("狼人A", "WEREWOLF"), "u_1");
        assertNotNull(saved.agentConfigId());
        assertEquals("u_1", saved.ownerId());
        assertEquals(1, fixture.queries.catalog("u_1", 1, 20).get("total"));
        assertEquals(saved.agentConfigId(), fixture.store.config(saved.agentConfigId()).orElseThrow().agentConfigId());
    }

    @Test
    @DisplayName("别人的配置不能覆盖")
    void otherOwnersConfigsAreNotOverwritable() {
        Fixture fixture = new Fixture();
        LabAgentConfig mine = fixture.commands.saveConfig(draft("村民A", "VILLAGER"), "u_1");
        Map<String, Object> overwrite = new java.util.LinkedHashMap<>(draft("村民A", "VILLAGER"));
        overwrite.put("agentConfigId", mine.agentConfigId());

        assertThrows(com.wolfmind.domain.game.model.GameException.class,
                () -> fixture.commands.saveConfig(overwrite, "u_2"));
    }

    @Test
    @DisplayName("自定义角色配置明确拒绝，而不是静默用经典九人开局")
    void customRoleCompositionIsRejected() {
        Fixture fixture = new Fixture();
        LabAgentConfig config = fixture.commands.saveConfig(draft("配置A", "SEER"), "u_1");
        Map<String, Object> body = new java.util.LinkedHashMap<>();
        body.put("name", "批次");
        body.put("gameCount", 1);
        body.put("agentConfigIds", List.of(config.agentConfigId()));
        body.put("roleComposition", Map.of("playerCount", 9));

        var error = assertThrows(com.wolfmind.domain.game.model.GameException.class,
                () -> fixture.commands.startBatch(body, "u_1"));
        assertTrue(error.details().stream().anyMatch(detail -> "roleComposition".equals(detail.field())),
                "沉默地用默认配置会让'我明明配了 4 狼'变成一句无凭无据的抱怨");
    }

    @Test
    @DisplayName("一批对局真的跑完：角色绑定被记录、结果可汇总、胜率可重算")
    void batchRunsToCompletionAndProducesComparableRows() {
        Fixture fixture = new Fixture();
        LabAgentConfig seerConfig = fixture.commands.saveConfig(draft("预言家配置", "SEER"), "u_1");
        LabAgentConfig wolfConfig = fixture.commands.saveConfig(draft("狼人配置", "WEREWOLF"), "u_1");

        LabBatch batch = fixture.commands.startBatch(Map.of(
                "name", "两次对照",
                "gameCount", 2,
                "agentConfigIds", List.of(seerConfig.agentConfigId(), wolfConfig.agentConfigId())),
                "u_1");
        assertEquals(LabBatch.RUNNING, batch.status());
        assertEquals(2, batch.total());

        fixture.playAll(2_000);

        LabBatch done = fixture.queries.batch("u_1", batch.batchId());
        assertEquals(LabBatch.COMPLETED, done.status(), "批次必须在全部对局结束后收尾");
        assertEquals(2, done.completed());
        assertEquals(2, done.games().size());
        assertEquals(2, done.games().stream().map(LabBatch.GameRef::gameId).distinct().count(),
                "两局必须是不同的对局");

        for (LabBatch.GameRef game : done.games()) {
            assertEquals(LabBatch.GAME_FINISHED, game.status());
            assertNotNull(game.winner());
            assertFalse(game.roleAssignments().isEmpty(), "必须记录'哪份配置坐了哪个座位'");
            assertTrue(game.roleAssignments().containsKey("WEREWOLF"));
            if (game.roleAssignments().containsKey("SEER")) {
                assertEquals(seerConfig.agentConfigId(), game.roleAssignments().get("SEER").agentConfigId());
            }
            assertEquals(wolfConfig.agentConfigId(), game.roleAssignments().get("WEREWOLF").agentConfigId());
            // 对局仍是普通对局：走的是同一条事件流
            assertTrue(fixture.harness.eventTypes(game.gameId()).contains("GAME_FINISHED"));
        }

        Map<String, Object> statistics = done.statistics();
        assertEquals(2, statistics.get("finished"));
        assertEquals(2, ((Number) statistics.get("wolfWins")).intValue()
                + ((Number) statistics.get("villageWins")).intValue());

        Map<String, Object> comparisons = fixture.queries.comparisons("u_1", 
                List.of(seerConfig.agentConfigId(), wolfConfig.agentConfigId()), null);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> rows = (List<Map<String, Object>>) comparisons.get("rows");
        assertFalse(rows.isEmpty(), "比对表必须给出可读的行");
        for (Map<String, Object> row : rows) {
            assertNotNull(row.get("winRate"));
            assertNotNull(row.get("games"));
            assertTrue(String.valueOf(comparisons.get("provenance")).contains("已结束"),
                    "样本量与口径必须写清楚，否则 3/50 局的胜率会被当成结论");
        }
    }

    @Test
    @DisplayName("并发上限：达到服务端上限时以准入异常拒绝，而不是排队")
    void concurrencyBudgetIsEnforced() {
        Fixture fixture = new Fixture();
        LabAgentConfig config = fixture.commands.saveConfig(draft("配置B", "VILLAGER"), "u_1");
        // 服务端上限 8：先占满 8 局，再要 1 局就必须被拒
        fixture.commands.startBatch(Map.of(
                "name", "占满并发",
                "gameCount", 8,
                "agentConfigIds", List.of(config.agentConfigId()),
                "maxConcurrentGames", 8), "u_1");

        assertThrows(LabApplicationService.LabAdmissionException.class,
                () -> fixture.commands.startBatch(Map.of(
                        "name", "再来一批",
                        "gameCount", 1,
                        "agentConfigIds", List.of(config.agentConfigId())), "u_1"));
    }

    @Test
    @DisplayName("批次与配置的标识唯一")
    void identifiersAreUnique() {
        Fixture fixture = new Fixture();
        LabAgentConfig first = fixture.commands.saveConfig(draft("A", "SEER"), "u_1");
        LabAgentConfig second = fixture.commands.saveConfig(draft("B", "SEER"), "u_1");
        assertNotNull(first.agentConfigId());
        assertFalse(first.agentConfigId().equals(second.agentConfigId()));

        LabAgentConfig config = fixture.commands.saveConfig(draft("C", "VILLAGER"), "u_1");
        LabBatch one = fixture.commands.startBatch(Map.of(
                "name", "批次一", "gameCount", 1, "agentConfigIds", List.of(config.agentConfigId())), "u_1");
        LabBatch two = fixture.commands.startBatch(Map.of(
                "name", "批次二", "gameCount", 1, "agentConfigIds", List.of(config.agentConfigId())), "u_1");
        assertFalse(one.batchId().equals(two.batchId()));
        // 配置覆盖保存保留 createdAt：它是"这份配置存在了多久"，不该被判成新的
        assertEquals(first.createdAt(), fixture.store.config(first.agentConfigId()).orElseThrow().createdAt());
    }

    @Test
    @DisplayName("对局是普通对局：状态由仓储给出，不靠批次记录自说自话")
    void gameStatusComesFromTheRepository() {
        Fixture fixture = new Fixture();
        LabAgentConfig config = fixture.commands.saveConfig(draft("配置C", "SEER"), "u_1");
        LabBatch batch = fixture.commands.startBatch(Map.of(
                "name", "单局",
                "gameCount", 1,
                "agentConfigIds", List.of(config.agentConfigId())), "u_1");

        fixture.runner.tick();
        LabBatch launched = fixture.queries.batch("u_1", batch.batchId());
        assertEquals(1, launched.games().size());
        String gameId = launched.games().get(0).gameId();
        assertNotNull(gameId);
        assertEquals(GameStatus.RUNNING, fixture.harness.repository().load(gameId).game().status());
    }
}

