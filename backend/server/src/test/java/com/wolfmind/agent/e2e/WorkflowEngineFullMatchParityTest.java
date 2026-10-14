package com.wolfmind.agent.e2e;

import com.wolfmind.application.agent.llm.LlmClient;
import com.wolfmind.application.agent.model.AgentConfig;
import com.wolfmind.application.agent.model.TurnBudget;
import com.wolfmind.domain.game.event.EventCodec;
import com.wolfmind.domain.game.model.Game;
import com.wolfmind.domain.game.model.GameId;
import com.wolfmind.domain.game.model.GameStatus;
import com.wolfmind.infrastructure.agent.wiring.InfrastructureGameHarness;
import com.wolfmind.infrastructure.ai.WorkflowEngineSelection;
import com.wolfmind.infrastructure.json.Json;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/** Same seed, logical clock, dispatch order and model answers; compare every envelope field without normalization. */
class WorkflowEngineFullMatchParityTest {
    enum Scenario { RULE_BASELINE, NATIVE_A, NATIVE_B, INVALID_THEN_REPAIR }
    static Stream<org.junit.jupiter.params.provider.Arguments> matches() {
        return Stream.of(Scenario.values()).flatMap(s -> Stream.of(7L, 42L, 20261013L)
                .map(seed -> org.junit.jupiter.params.provider.Arguments.of(s, seed)));
    }

    private static final class FixedModel implements LlmClient {
        private final boolean invalidFirst;
        private int calls;
        private int queries;
        FixedModel(boolean invalidFirst) { this.invalidFirst = invalidFirst; }
        public boolean available() { return true; }
        public String providerId() { return "parity-fixed"; }
        public InteractionMode interactionMode() { return InteractionMode.FUNCTION_CALLING; }
        public LlmResponse chat(LlmRequest request) {
            calls++;
            if (invalidFirst && calls == 1) return tool("UnknownAction", Map.of());
            if (request.tools().stream().anyMatch(t -> t.name().equals("PrepareSpeechPlan")))
                return tool("PrepareSpeechPlan", Map.of("stance", "OBSERVE", "purpose", "CAUTION", "evidenceIds", List.of()));
            boolean readAllowed = request.tools().stream().anyMatch(t -> t.name().equals("QueryGameStateTool"));
            if (readAllowed && request.messages().stream().noneMatch(m -> m.role().equals("tool"))) {
                queries++;
                return tool("QueryGameStateTool", Map.of("scope", "SELF"));
            }
            if (request.tools().stream().allMatch(t -> t.name().equals("SpeakTool")))
                return tool("SpeakTool", Map.of("content", speech(request)));
            var candidate = Pattern.compile("(?m)^- ([A-Z_]+)(?: (\\d+)号)? \\| score=")
                    .matcher(request.messages().get(1).content());
            assertTrue(candidate.find(), "No candidate action supplied to fixed model");
            String action = candidate.group(1), target = candidate.group(2);
            Map<String, Object> args = new LinkedHashMap<>();
            if (target != null) args.put("targetId", "p_" + target);
            String tool = switch (action) {
                case "SPEAK" -> { args.put("content", speech(request)); yield "SpeakTool"; }
                case "VOTE" -> { if (target == null) args.put("abstain", true); yield "SubmitVoteTool"; }
                case "WOLF_KILL" -> { if (target == null) args.put("empty", true); yield "KillPlayerTool"; }
                case "SKIP" -> { args.put("skip", true); yield "SpeakTool"; }
                default -> { args.put("skill", action); yield "UseSkillTool"; }
            };
            return tool(tool, args);
        }
        private String speech(LlmRequest request) {
            String system = request.messages().getFirst().content();
            var seat = Pattern.compile("(?:座位\\s*|你是)(\\d+)").matcher(system);
            assertTrue(seat.find());
            List<String> statements = List.of("我关注发言前后的变化，请解释立场变化的原因。", "我的判断以公开投票为依据，票型可以辅助观察。",
                    "我先保留意见，把没有得到回应的问题记下来。", "请给出具体依据，直接下结论说服力不够。", "各位表达的观点需要结合发生顺序理解。",
                    "目前线索不够充分，我会听完其他人的解释。", "我想比较不同人的判断依据，再决定下一步。", "不要把推测当事实，我希望看到明确的证据。", "本轮我继续观察公开发言中相互矛盾的地方。");
            return statements.get(Integer.parseInt(seat.group(1)) - 1) + "记录" + calls + "。";
        }
        private LlmResponse tool(String name, Map<String, Object> args) {
            return new LlmResponse.Success("", "fixed", 10, 5, 1,
                    List.of(new ToolCall("call_" + calls, name, Json.write(args))), null);
        }
    }

    @ParameterizedTest(name = "{0}, seed={1}") @MethodSource("matches")
    void bothEnginesProduceIdenticalFullMatchEvents(Scenario scenario, long seed) {
        var sequentialModel = new FixedModel(scenario == Scenario.INVALID_THEN_REPAIR);
        var graphModel = new FixedModel(scenario == Scenario.INVALID_THEN_REPAIR);
        var config = scenario == Scenario.RULE_BASELINE ? AgentConfig.ruleBaseline("parity")
                : new AgentConfig("parity", "parity", "fixed", .6, .2, AgentConfig.StrategyProfile.BALANCED, "冷静简洁",
                scenario == Scenario.NATIVE_B ? AgentConfig.WorkflowMode.B : AgentConfig.WorkflowMode.A,
                60, .5, 8, 3, "default", TurnBudget.FUNCTION_CALLING);
        var sequential = play("SEQUENTIAL", config, sequentialModel, seed);
        var graph = play("LANGGRAPH4J", config, graphModel, seed);
        assertEquals(sequential.eventStream().size(), graph.eventStream().size());
        for (int index = 0; index < sequential.eventStream().size(); index++) {
            assertEquals(sequential.eventStream().get(index), graph.eventStream().get(index), "Event differs at version " + (index + 1));
        }
        assertEquals(sequential.snapshot(), graph.snapshot());
        assertEquals(sequential.agentTurns(), graph.agentTurns());
        assertEquals(sequential.fallbackTurns(), graph.fallbackTurns());
        assertEquals(sequentialModel.calls, graphModel.calls);
        assertEquals(sequentialModel.queries, graphModel.queries);
        if (scenario != Scenario.RULE_BASELINE) assertTrue(graphModel.calls > 9);
        if (scenario == Scenario.NATIVE_A) assertTrue(graphModel.queries > 9);
        if (scenario == Scenario.INVALID_THEN_REPAIR) assertTrue(graph.trace().all().stream()
                .anyMatch(e -> e.payload().get("repairRounds") instanceof Number number && number.intValue() > 0));
        System.out.printf("PARITY scenario=%s seed=%d events=%d turns=%d modelCalls=%d%n", scenario, seed,
                graph.eventStream().size(), graph.agentTurns(), graphModel.calls);
    }

    private NineAiGameRunner play(String engine, AgentConfig config, FixedModel model, long seed) {
        var harness = new InfrastructureGameHarness(1_730_000_000_000L);
        var runner = NineAiGameRunner.start(harness, "g_parity", model, config, seed, new WorkflowEngineSelection(engine));
        runner.runUntilFinished(2000);
        assertEquals(GameStatus.FINISHED, runner.snapshot().status(), engine + ": " + runner.lastTurnStatus());
        assertEquals(9, runner.snapshot().players().size());
        assertTrue(runner.trace().all().stream().filter(e -> e.wireType().equals("AGENT_TURN_STARTED"))
                .allMatch(e -> engine.equals(e.payload().get("engine"))));
        var replay = Game.create(GameId.of("g_parity"));
        runner.eventStream().forEach(e -> replay.replay(EventCodec.decode(e)));
        assertEquals(runner.snapshot(), replay.snapshot());
        return runner;
    }
}
