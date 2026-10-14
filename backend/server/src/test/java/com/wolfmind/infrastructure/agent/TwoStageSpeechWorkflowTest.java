package com.wolfmind.infrastructure.agent;

import com.wolfmind.application.agent.AgentRuntime;
import com.wolfmind.application.agent.llm.LlmClient;
import com.wolfmind.application.agent.llm.ScriptedLlmClient;
import com.wolfmind.application.agent.model.*;
import com.wolfmind.application.agent.runtime.*;
import com.wolfmind.application.agent.speech.SpeechPreviewPort;
import com.wolfmind.application.agent.strategy.WolfMindStrategy;
import com.wolfmind.domain.game.model.GamePhase;
import com.wolfmind.infrastructure.ai.LangGraph4jWorkflowEngine;
import com.wolfmind.infrastructure.memory.InMemoryMemoryPort;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class TwoStageSpeechWorkflowTest {
    private static long speechCount(GatewayHarness h) { return h.events().stream().filter(e -> e.type().equals("PLAYER_SPOKE")).count(); }
    static final String PLAN = "{\"stance\":\"OBSERVE\",\"purpose\":\"CAUTION\",\"evidenceIds\":[]}";
    static final String SPEECH = "这轮我先听各位的判断，等公开证据更充分再表态。";

    private static ScriptedLlmClient nativeSuccess() {
        return new ScriptedLlmClient().functionCalling()
                .enqueueTools(new LlmClient.ToolCall("plan", SpeechPlan.TOOL_NAME, PLAN))
                .enqueueTools(new LlmClient.ToolCall("speech", "SpeakTool", "{\"content\":\"" + SPEECH + "\"}"));
    }

    @ParameterizedTest
    @org.junit.jupiter.params.provider.CsvSource({"false,false", "false,true", "true,false", "true,true"})
    void stageHeaderIsRemovedBeforeCommitAndPreviewWithoutAnotherModelCall(boolean graph, boolean nativeMode) {
        var h = new GatewayHarness(); h.driveTo(GamePhase.DAY_DISCUSS);
        String draft = "第 1 天，DAY_DISCUSS。" + SPEECH;
        var client = new ScriptedLlmClient();
        if (nativeMode) client.functionCalling()
                .enqueueTools(new LlmClient.ToolCall("plan", SpeechPlan.TOOL_NAME, PLAN))
                .enqueueTools(new LlmClient.ToolCall("speech", "SpeakTool", "{\"content\":\"" + draft + "\"}"));
        else client.enqueueText(PLAN).enqueueText("{\"actionType\":\"SPEAK\",\"speech\":\"" + draft + "\"}");
        var previews = new ArrayList<SpeechPreviewPort.SpeechPreview>();
        long before = speechCount(h);
        var state = run(h, client, graph, TurnBudget.FUNCTION_CALLING, previews::add);
        assertTrue(state.submitted(), () -> state.errorCode());
        assertFalse(state.fallbackUsed());
        assertEquals(2, state.llmCalls());
        assertEquals(SPEECH, state.proposal().speech());
        assertEquals(before + 1, speechCount(h));
        assertEquals(SPEECH, previews.stream().map(SpeechPreviewPort.SpeechPreview::delta)
                .collect(java.util.stream.Collectors.joining()));
        String expressionSystem = client.receivedRequests().get(1).messages().get(0).content();
        assertTrue(expressionSystem.contains("不播报当前阶段"));
        assertFalse(expressionSystem.contains("DAY_DISCUSS"));
    }

    private static AgentWorkflow.FlowContext run(GatewayHarness h, ScriptedLlmClient client, boolean graph,
                                                 TurnBudget budget, SpeechPreviewPort preview) {
        return run(h, client, graph, budget, preview, h.view(h.state().currentTurn().seatNo().value()));
    }

    private static AgentWorkflow.FlowContext run(GatewayHarness h, LlmClient client, boolean graph,
                                                 TurnBudget budget, SpeechPreviewPort preview, AgentView view) {
        return run(h, client, graph, budget, preview, view, h.time);
    }

    private static AgentWorkflow.FlowContext run(GatewayHarness h, LlmClient client, boolean graph,
                                                 TurnBudget budget, SpeechPreviewPort preview, AgentView view,
                                                 com.wolfmind.application.port.TimePort clock) {
        var gateway = h.gateway();
        var cfg = new AgentConfig("b", "b", "fake-model", 0.6, 0.2, AgentConfig.StrategyProfile.BALANCED,
                "冷静简洁", AgentConfig.WorkflowMode.B, 60, 0.5, 8, 3, "default", budget);
        var request = new TurnRequest(view.gameId(), view.me().playerId(), view.me().seatNo(), view.me().role(), view.me().camp(),
                view.phase(), view.round(), view.turnIndex(), view.version(), h.time.nowMillis() + 20000,
                view, gateway.availableTools(view.me().role(), view.phase()), budget, cfg);
        var workflow = new AgentWorkflow(new WolfMindStrategy(gateway), gateway, new InMemoryMemoryPort(), h.trace,
                new PromptBuilder(), new LlmInvoker(client, "test", clock), new BudgetGuard(clock), clock,
                graph ? LangGraph4jWorkflowEngine::new : SequentialWorkflowEngine::new, preview);
        return workflow.run(request, new AgentRuntime.AgentHandle(view.gameId(), view.me().playerId(), view.me().seatNo(), "b"));
    }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void twoCallsOneCommittedSpeechWithNoPlanningPreview(boolean graph) {
        var h = new GatewayHarness(); h.driveTo(GamePhase.DAY_DISCUSS);
        var client = nativeSuccess(); var previews = new ArrayList<SpeechPreviewPort.SpeechPreview>();
        long before = speechCount(h);
        var state = run(h, client, graph, TurnBudget.FUNCTION_CALLING, previews::add);
        assertTrue(state.submitted(), () -> state.errorCode()); assertFalse(state.fallbackUsed());
        assertEquals(2, state.llmCalls()); assertEquals(before + 1, speechCount(h));
        assertEquals(SPEECH, state.proposal().speech()); assertNotNull(state.speechPlan());
        assertEquals(0.3, client.receivedRequests().get(0).temperature());
        assertEquals(0.85, client.receivedRequests().get(1).temperature());
        assertTrue(client.receivedRequests().get(0).tools().stream().noneMatch(t -> t.name().equals("SpeakTool")));
        assertEquals(List.of("SpeakTool"), client.receivedRequests().get(1).tools().stream().map(LlmClient.LlmRequest.ToolSpec::name).toList());
        assertTrue(previews.stream().anyMatch(p -> p.delta().contains("公开证据")));
        assertTrue(previews.stream().noneMatch(p -> p.delta().contains("OBSERVE")));
    }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void legacyJsonAlsoSupportsTwoStages(boolean graph) {
        var h = new GatewayHarness(); h.driveTo(GamePhase.DAY_DISCUSS);
        var client = new ScriptedLlmClient().enqueueText(PLAN)
                .enqueueText("{\"actionType\":\"SPEAK\",\"speech\":\"" + SPEECH + "\"}");
        var state = run(h, client, graph, TurnBudget.DEFAULT, SpeechPreviewPort.NONE);
        assertTrue(state.submitted()); assertFalse(state.fallbackUsed()); assertEquals(2, state.llmCalls());
    }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void lastWordsAlsoUseTwoStages(boolean graph) {
        var h = new GatewayHarness(); h.driveTo(GamePhase.LAST_WORDS);
        var state = run(h, nativeSuccess(), graph, TurnBudget.FUNCTION_CALLING, SpeechPreviewPort.NONE);
        assertTrue(state.submitted()); assertFalse(state.fallbackUsed()); assertEquals(2, state.llmCalls());
    }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void voteInModeBStillUsesOneCall(boolean graph) {
        var h = new GatewayHarness(); h.driveTo(GamePhase.DAY_VOTE);
        var view = h.view(h.game().seatsAwaitingVote().get(0).value());
        var client = new ScriptedLlmClient().functionCalling()
                .enqueueTools(new LlmClient.ToolCall("vote", "SubmitVoteTool", "{\"abstain\":true}"));
        var state = run(h, client, graph, TurnBudget.FUNCTION_CALLING, SpeechPreviewPort.NONE, view);
        assertTrue(state.submitted()); assertEquals(1, state.llmCalls()); assertNull(state.speechPlan());
        assertTrue(client.receivedRequests().get(0).tools().stream().noneMatch(t -> t.name().equals(SpeechPlan.TOOL_NAME)));
    }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void queryThenPlanLeavesOneCallForExpression(boolean graph) {
        var h = new GatewayHarness(); h.driveTo(GamePhase.DAY_DISCUSS);
        var client = new ScriptedLlmClient().functionCalling()
                .enqueueTools(new LlmClient.ToolCall("q", "QueryHistoryTool", "{\"limit\":3}"))
                .enqueueTools(new LlmClient.ToolCall("plan", SpeechPlan.TOOL_NAME, PLAN))
                .enqueueTools(new LlmClient.ToolCall("speech", "SpeakTool", "{\"content\":\"" + SPEECH + "\"}"));
        var state = run(h, client, graph, TurnBudget.defaultFor(client.interactionMode()), SpeechPreviewPort.NONE);
        assertTrue(state.submitted()); assertFalse(state.fallbackUsed()); assertEquals(3, state.llmCalls());
        assertTrue(state.toolCalls().stream().anyMatch(t -> t.tool().equals("QueryHistoryTool")));
        assertTrue(client.receivedRequests().get(1).messages().stream().anyMatch(m -> m.role().equals("tool")));
        assertTrue(client.receivedRequests().get(2).messages().stream().allMatch(m -> m.role().equals("system") || m.role().equals("user")));
    }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void repeatedQueriesCannotConsumeExpressionReserve(boolean graph) {
        var h = new GatewayHarness(); h.driveTo(GamePhase.DAY_DISCUSS);
        var client = new ScriptedLlmClient().functionCalling();
        for (int i = 0; i < 5; i++) client.enqueueTools(new LlmClient.ToolCall("q" + i, "QueryHistoryTool", "{\"limit\":3}"));
        var state = run(h, client, graph, TurnBudget.FUNCTION_CALLING, SpeechPreviewPort.NONE);
        assertTrue(state.submitted()); assertTrue(state.fallbackUsed()); assertEquals(3, state.llmCalls());
        assertTrue(client.receivedRequests().get(2).tools().stream().noneMatch(t -> t.name().startsWith("Query")));
    }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void nonexistentOrPrivateEvidenceCannotBecomeAPlan(boolean graph) {
        var h = new GatewayHarness(); h.driveTo(GamePhase.DAY_DISCUSS);
        String bad = PLAN.replace("[]", "[\"private:WOLF_TEAMMATE:6\"]");
        var client = new ScriptedLlmClient().functionCalling()
                .enqueueTools(new LlmClient.ToolCall("plan1", SpeechPlan.TOOL_NAME, bad))
                .enqueueTools(new LlmClient.ToolCall("plan2", SpeechPlan.TOOL_NAME, bad));
        long before = speechCount(h);
        var state = run(h, client, graph, TurnBudget.FUNCTION_CALLING, SpeechPreviewPort.NONE);
        assertTrue(state.submitted()); assertTrue(state.fallbackUsed()); assertNull(state.speechPlan());
        assertEquals(2, state.llmCalls()); assertEquals(before + 1, speechCount(h));
    }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void publicEvidenceIsResolvedFromActualEvents(boolean graph) {
        var h = new GatewayHarness(); h.driveTo(GamePhase.DAY_DISCUSS);
        var view = h.view(h.state().currentTurn().seatNo().value());
        var evidence = SpeechPlan.publicEvidence(view);
        assertFalse(evidence.isEmpty());
        String id = evidence.keySet().iterator().next();
        var client = new ScriptedLlmClient().functionCalling()
                .enqueueTools(new LlmClient.ToolCall("plan", SpeechPlan.TOOL_NAME, PLAN.replace("[]", "[\"" + id + "\"]")))
                .enqueueTools(new LlmClient.ToolCall("speech", "SpeakTool", "{\"content\":\"" + SPEECH + "\"}"));
        var state = run(h, client, graph, TurnBudget.FUNCTION_CALLING, SpeechPreviewPort.NONE);
        assertTrue(state.submitted()); assertFalse(state.fallbackUsed());
        assertTrue(client.receivedRequests().get(1).messages().get(1).content().contains(evidence.get(id)));
    }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void planningContextAndPrivateFactsNeverReachExpressionOrRepair(boolean graph) {
        var h = new GatewayHarness(); h.driveTo(GamePhase.DAY_DISCUSS);
        var base = h.view(h.state().currentTurn().seatNo().value());
        var view = new AgentView(base.gameId(), base.version(), base.status(), base.phase(), base.round(), base.turnIndex(),
                base.turnOrder(), base.currentTurn(), base.rules(), base.me(), base.other(), base.votes(), base.deaths(),
                base.speeches(), List.of(new AgentView.PrivateFact("SEER_INSPECT", 6, "PRIVATE_SENTINEL", 1)), base.winner(),
                List.of(new AgentView.WolfChatView(6, 1, "PRIVATE_CHAT_SENTINEL")));
        var client = new ScriptedLlmClient().functionCalling()
                .enqueueTools(new LlmClient.ToolCall("plan", SpeechPlan.TOOL_NAME, PLAN))
                .enqueueTools(new LlmClient.ToolCall("bad", "SubmitVoteTool", "{\"abstain\":true}"))
                .enqueueTools(new LlmClient.ToolCall("speech", "SpeakTool", "{\"content\":\"" + SPEECH + "\"}"));
        var state = run(h, client, graph, TurnBudget.FUNCTION_CALLING, SpeechPreviewPort.NONE, view);
        assertTrue(state.submitted()); assertFalse(state.fallbackUsed()); assertEquals(1, state.repairRounds());
        String planning = client.receivedRequests().get(0).messages().toString();
        assertTrue(planning.contains("PRIVATE_SENTINEL"));
        for (var request : client.receivedRequests().subList(1, client.callCount())) {
            assertFalse(request.messages().toString().contains("PRIVATE_SENTINEL"));
            assertFalse(request.messages().toString().contains("PRIVATE_CHAT_SENTINEL"));
            assertFalse(request.messages().toString().contains("=== 只有你知道的信息 ==="));
            assertEquals(List.of("SpeakTool"), request.tools().stream().map(LlmClient.LlmRequest.ToolSpec::name).toList());
        }
        assertTrue(h.trace.traces(base.gameId(), base.me().playerId()).stream()
                .noneMatch(t -> t.payload().toString().contains("PRIVATE_SENTINEL")));
    }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void expressionCannotQueryAndMixedBatchDoesNotExecute(boolean graph) {
        var h = new GatewayHarness(); h.driveTo(GamePhase.DAY_DISCUSS);
        var client = new ScriptedLlmClient().functionCalling()
                .enqueueTools(new LlmClient.ToolCall("plan", SpeechPlan.TOOL_NAME, PLAN))
                .enqueueTools(new LlmClient.ToolCall("query", "QueryHistoryTool", "{\"limit\":3}"),
                        new LlmClient.ToolCall("speech", "SpeakTool", "{\"content\":\"" + SPEECH + "\"}"))
                .enqueueTools(new LlmClient.ToolCall("fixed", "SpeakTool", "{\"content\":\"" + SPEECH + "\"}"));
        long before = speechCount(h);
        var state = run(h, client, graph, TurnBudget.FUNCTION_CALLING, SpeechPreviewPort.NONE);
        assertTrue(state.submitted()); assertFalse(state.fallbackUsed()); assertEquals(before + 1, speechCount(h));
        assertTrue(state.toolCalls().stream().noneMatch(t -> t.tool().equals("QueryHistoryTool")));
    }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void focusAndStanceArePreservedDuringExpressionRepair(boolean graph) {
        var h = new GatewayHarness(); h.driveTo(GamePhase.DAY_DISCUSS);
        var base = h.view(h.state().currentTurn().seatNo().value()); int focus = base.other().get(0).seatNo();
        String plan = "{\"stance\":\"SUSPECT\",\"focusSeat\":" + focus + ",\"purpose\":\"REQUEST_EXPLANATION\",\"evidenceIds\":[]}";
        var client = new ScriptedLlmClient().functionCalling()
                .enqueueTools(new LlmClient.ToolCall("plan", SpeechPlan.TOOL_NAME, plan))
                .enqueueTools(new LlmClient.ToolCall("bad", "SpeakTool", "{\"content\":\"我相信" + focus + "号。\"}"))
                .enqueueTools(new LlmClient.ToolCall("fixed", "SpeakTool", "{\"content\":\"我怀疑" + focus + "号，请解释你的立场。\"}"));
        var state = run(h, client, graph, TurnBudget.FUNCTION_CALLING, SpeechPreviewPort.NONE);
        assertTrue(state.submitted()); assertFalse(state.fallbackUsed()); assertEquals(3, state.llmCalls());
        assertTrue(state.proposal().speech().contains("怀疑"));
        assertEquals(SpeechPlan.Stance.SUSPECT, state.speechPlan().stance());
    }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void insufficientBudgetDoesNotStartPlanning(boolean graph) {
        var h = new GatewayHarness(); h.driveTo(GamePhase.DAY_DISCUSS); var client = nativeSuccess();
        var state = run(h, client, graph, TurnBudget.FUNCTION_CALLING.withMaxLlmCalls(1), SpeechPreviewPort.NONE);
        assertTrue(state.submitted()); assertTrue(state.fallbackUsed()); assertEquals(0, client.callCount());
    }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void failedExpressionRepairsOnlyExpression(boolean graph) {
        var h = new GatewayHarness(); h.driveTo(GamePhase.DAY_DISCUSS);
        var client = new ScriptedLlmClient().functionCalling()
                .enqueueTools(new LlmClient.ToolCall("plan", SpeechPlan.TOOL_NAME, PLAN))
                .enqueueFailure("TIMEOUT", "timeout", false)
                .enqueueTools(new LlmClient.ToolCall("fixed", "SpeakTool", "{\"content\":\"" + SPEECH + "\"}"));
        var state = run(h, client, graph, TurnBudget.FUNCTION_CALLING, SpeechPreviewPort.NONE);
        assertTrue(state.submitted()); assertFalse(state.fallbackUsed()); assertEquals(3, state.llmCalls());
        assertEquals(1, state.repairRounds());
        assertTrue(client.receivedRequests().get(2).tools().stream().noneMatch(t -> t.name().equals(SpeechPlan.TOOL_NAME)));
    }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void exhaustedExpressionBudgetFallsBackWithoutRestartingPlan(boolean graph) {
        var h = new GatewayHarness(); h.driveTo(GamePhase.DAY_DISCUSS);
        var client = new ScriptedLlmClient().functionCalling()
                .enqueueTools(new LlmClient.ToolCall("plan", SpeechPlan.TOOL_NAME, PLAN))
                .enqueueFailure("TIMEOUT", "timeout", false);
        long before = speechCount(h);
        var state = run(h, client, graph, TurnBudget.DEFAULT, SpeechPreviewPort.NONE);
        assertTrue(state.submitted()); assertTrue(state.fallbackUsed()); assertEquals(2, client.callCount());
        assertEquals(before + 1, speechCount(h));
    }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void fourCallDefaultExhaustionCommitsOneFallbackWithoutRestartingPlan(boolean graph) {
        var h = new GatewayHarness(); h.driveTo(GamePhase.DAY_DISCUSS);
        var client = new ScriptedLlmClient().functionCalling()
                .enqueueTools(new LlmClient.ToolCall("query", "QueryHistoryTool", "{\"limit\":3}"))
                .enqueueTools(new LlmClient.ToolCall("plan", SpeechPlan.TOOL_NAME, PLAN))
                .enqueueTools(new LlmClient.ToolCall("bad", "SubmitVoteTool", "{\"abstain\":true}"))
                .enqueueTools(new LlmClient.ToolCall("bad-repair", "SubmitVoteTool", "{\"abstain\":true}"));
        long before = speechCount(h);
        var state = run(h, client, graph, TurnBudget.defaultFor(client.interactionMode()), SpeechPreviewPort.NONE);
        assertTrue(state.submitted()); assertTrue(state.fallbackUsed());
        assertEquals(4, client.callCount()); assertEquals(4, state.llmCalls());
        assertEquals(before + 1, speechCount(h));
        assertEquals(1, client.receivedRequests().stream().filter(request -> request.tools().stream()
                .anyMatch(tool -> tool.name().equals(SpeechPlan.TOOL_NAME))
                && request.messages().stream().anyMatch(message -> message.role().equals("tool"))).count());
        assertTrue(client.receivedRequests().subList(2, 4).stream().allMatch(request ->
                request.tools().stream().allMatch(tool -> tool.name().equals("SpeakTool"))));
    }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void illegalPlanningWriteNeverCommits(boolean graph) {
        var h = new GatewayHarness(); h.driveTo(GamePhase.DAY_DISCUSS);
        var client = new ScriptedLlmClient().functionCalling()
                .enqueueTools(new LlmClient.ToolCall("bad", "SubmitVoteTool", "{\"abstain\":true}"))
                .enqueueTools(new LlmClient.ToolCall("plan", SpeechPlan.TOOL_NAME, PLAN))
                .enqueueTools(new LlmClient.ToolCall("speech", "SpeakTool", "{\"content\":\"" + SPEECH + "\"}"));
        long before = speechCount(h);
        var state = run(h, client, graph, TurnBudget.FUNCTION_CALLING, SpeechPreviewPort.NONE);
        assertTrue(state.submitted()); assertFalse(state.fallbackUsed()); assertEquals(before + 1, speechCount(h));
        assertTrue(state.toolCalls().stream().noneMatch(t -> t.tool().equals("SubmitVoteTool")));
    }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void privateAssertionIsRewrittenBeforeAnyPreview(boolean graph) {
        var h = new GatewayHarness(); h.driveTo(GamePhase.DAY_DISCUSS);
        var client = new ScriptedLlmClient().functionCalling()
                .enqueueTools(new LlmClient.ToolCall("plan", SpeechPlan.TOOL_NAME, PLAN))
                .enqueueTools(new LlmClient.ToolCall("leak", "SpeakTool", "{\"content\":\"6号是我的狼队友。\"}"))
                .enqueueTools(new LlmClient.ToolCall("fixed", "SpeakTool", "{\"content\":\"" + SPEECH + "\"}"));
        var previews = new ArrayList<SpeechPreviewPort.SpeechPreview>();
        var state = run(h, client, graph, TurnBudget.FUNCTION_CALLING, previews::add);
        assertTrue(state.submitted()); assertFalse(state.fallbackUsed()); assertEquals(1, state.rewriteRounds());
        assertTrue(previews.stream().noneMatch(p -> p.delta().contains("狼队友")));
        assertTrue(h.trace.traces(state.request().gameId(), state.request().playerId()).stream()
                .noneMatch(t -> t.payload().toString().contains("6号是我的狼队友")));
    }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void planningTimeoutNeverRestartsTheDecision(boolean graph) {
        var h = new GatewayHarness(); h.driveTo(GamePhase.DAY_DISCUSS);
        var client = new ScriptedLlmClient().functionCalling().enqueueFailure("TIMEOUT", "timeout", false);
        var state = run(h, client, graph, TurnBudget.FUNCTION_CALLING, SpeechPreviewPort.NONE);
        assertTrue(state.submitted()); assertTrue(state.fallbackUsed()); assertEquals(1, client.callCount());
    }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void softDeadlineAfterPlanPreventsExpressionAndStillAllowsLegalFallback(boolean graph) {
        var h = new GatewayHarness(); h.driveTo(GamePhase.DAY_DISCUSS);
        var now = new java.util.concurrent.atomic.AtomicLong(h.time.nowMillis());
        var scripted = nativeSuccess();
        LlmClient client = new LlmClient() {
            public boolean available() { return true; }
            public String providerId() { return "test"; }
            public InteractionMode interactionMode() { return InteractionMode.FUNCTION_CALLING; }
            public LlmResponse chat(LlmRequest request) {
                var response = scripted.chat(request); now.addAndGet(19000); return response;
            }
        };
        var view = h.view(h.state().currentTurn().seatNo().value());
        var state = run(h, client, graph, TurnBudget.FUNCTION_CALLING, SpeechPreviewPort.NONE, view, now::get);
        assertTrue(state.submitted()); assertTrue(state.fallbackUsed()); assertEquals(1, scripted.callCount());
        assertTrue(scripted.receivedRequests().get(0).timeoutMs() <= 9250);
    }
}
