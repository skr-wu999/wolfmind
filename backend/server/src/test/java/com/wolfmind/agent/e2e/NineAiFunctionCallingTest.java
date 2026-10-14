package com.wolfmind.agent.e2e;

import com.wolfmind.application.agent.llm.LlmClient;
import com.wolfmind.application.agent.model.AgentConfig;
import com.wolfmind.application.agent.model.TurnBudget;
import com.wolfmind.domain.game.model.GameStatus;
import com.wolfmind.infrastructure.agent.wiring.InfrastructureGameHarness;
import com.wolfmind.infrastructure.json.Json;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;

class NineAiFunctionCallingTest {
    private static final class CandidateModel implements LlmClient {
        private static final Pattern CANDIDATE = Pattern.compile("(?m)^- ([A-Z_]+)(?: (\\d+)号)? \\| score=");
        int calls;
        int queries;
        @Override public boolean available() { return true; }
        @Override public String providerId() { return "native-fake"; }
        @Override public InteractionMode interactionMode() { return InteractionMode.FUNCTION_CALLING; }
        @Override public LlmResponse chat(LlmRequest request) {
            calls++;
            assertEquals(InteractionMode.FUNCTION_CALLING, request.interactionMode());
            assertFalse(request.tools().isEmpty());
            if (request.messages().stream().noneMatch(message -> message.role().equals("tool"))) {
                queries++;
                return tools("QueryGameStateTool", Map.of("scope", "SELF"));
            }
            String user = request.messages().get(1).content();
            var candidate = CANDIDATE.matcher(user);
            assertTrue(candidate.find(), "必须有候选动作");
            String action = candidate.group(1);
            String target = candidate.group(2);
            Map<String, Object> arguments = new LinkedHashMap<>();
            if (target != null) arguments.put("targetId", "p_" + target);
            String tool = switch (action) {
                case "SPEAK" -> { arguments.put("content", "我会结合本轮公开证据再判断。记录序号" + calls + "。"); yield "SpeakTool"; }
                case "VOTE" -> { if (target == null) arguments.put("abstain", true); yield "SubmitVoteTool"; }
                case "WOLF_KILL" -> { if (target == null) arguments.put("empty", true); yield "KillPlayerTool"; }
                case "SKIP" -> { arguments.put("skip", true); yield "SpeakTool"; }
                default -> { arguments.put("skill", action); yield "UseSkillTool"; }
            };
            return tools(tool, arguments);
        }
        private LlmResponse tools(String name, Map<String, Object> arguments) {
            return new LlmResponse.Success("", "fake", 10, 5, 1,
                    List.of(new ToolCall("call_" + calls, name, Json.write(arguments))),
                    new Continuation("private-model-continuation"));
        }
    }

    @Test
    void nineNativeAgentsQueryAndFinishThroughRealGateway() {
        var harness = new InfrastructureGameHarness(1_730_000_000_000L);
        var model = new CandidateModel();
        var config = new AgentConfig("native", "native", "fake", 0.6, 0.2,
                AgentConfig.StrategyProfile.BALANCED, "中性", AgentConfig.WorkflowMode.A,
                60, 0.5, 8, 3, "default", TurnBudget.FUNCTION_CALLING);
        var runner = NineAiGameRunner.start(harness, "g_native_e2e", model, config, 20261013L);
        runner.runUntilFinished(2000);
        assertEquals(GameStatus.FINISHED, runner.snapshot().status(), runner.lastTurnStatus());
        assertTrue(model.queries > 9);
        assertTrue(model.calls > model.queries);
        assertTrue(runner.submittedTurns() > 9);
        assertTrue(runner.eventTypes().stream().noneMatch(type -> type.startsWith("AGENT_")));
        assertTrue(runner.trace().all().stream().noneMatch(event -> event.payload().toString().contains("private-model-continuation")));
    }
}
