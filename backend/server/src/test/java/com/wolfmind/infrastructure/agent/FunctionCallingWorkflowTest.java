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
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class FunctionCallingWorkflowTest {
    private static AgentConfig config(TurnBudget budget) {
        return new AgentConfig("native", "native", "fake-model", 0.6, 0.2,
                AgentConfig.StrategyProfile.BALANCED, "中性", AgentConfig.WorkflowMode.A,
                60, 0.5, 8, 3, "default", budget);
    }

    private static AgentWorkflow.FlowContext run(GatewayHarness harness, int seat, LlmClient client,
                                                TurnBudget budget, boolean graph, SpeechPreviewPort preview) {
        var gateway = harness.gateway();
        var view = harness.view(seat);
        var cfg = config(budget);
        var request = new TurnRequest(view.gameId(), view.me().playerId(), seat, view.me().role(), view.me().camp(),
                view.phase(), view.round(), view.turnIndex(), view.version(), harness.time.nowMillis() + 20000,
                view, gateway.availableTools(view.me().role(), view.phase()), budget, cfg);
        var workflow = new AgentWorkflow(new WolfMindStrategy(gateway), gateway, new InMemoryMemoryPort(),
                harness.trace, new PromptBuilder(), new LlmInvoker(client, "test", harness.time),
                new BudgetGuard(harness.time), harness.time, graph ? LangGraph4jWorkflowEngine::new : null, preview);
        return workflow.run(request, new AgentRuntime.AgentHandle(view.gameId(), view.me().playerId(), seat, "native"));
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void queryThenVoteCommitsOnceAndReturnsToolResults(boolean graph) {
        var harness = new GatewayHarness();
        harness.driveTo(GamePhase.DAY_VOTE);
        int seat = harness.game().seatsAwaitingVote().get(0).value();
        var client = new ScriptedLlmClient().functionCalling()
                .enqueueTools(new LlmClient.ToolCall("q", "QueryHistoryTool", "{\"limit\":3}"))
                .enqueueTools(new LlmClient.ToolCall("v", "SubmitVoteTool", "{\"abstain\":true}"));
        int before = harness.eventCount();
        var state = run(harness, seat, client, TurnBudget.FUNCTION_CALLING, graph, SpeechPreviewPort.NONE);
        assertTrue(state.submitted(), () -> state.errorCode());
        assertFalse(state.fallbackUsed());
        assertEquals(2, state.llmCalls());
        assertEquals(before + 1, harness.eventCount());
        var messages = client.receivedRequests().get(1).messages();
        assertTrue(messages.stream().anyMatch(m -> m.role().equals("assistant") && m.toolCalls().get(0).callId().equals("q")));
        assertTrue(messages.stream().anyMatch(m -> m.role().equals("tool") && m.callId().equals("q") && m.content().contains("payload")));
        assertTrue(client.receivedRequests().get(0).tools().stream().anyMatch(s -> s.name().equals("SubmitVoteTool")
                && s.jsonSchema().containsKey("properties")));
    }

    @Test
    void multipleWritesAreRejectedBeforeAnyExecutionAndRepaired() {
        var harness = new GatewayHarness(); harness.driveTo(GamePhase.DAY_VOTE);
        int seat = harness.game().seatsAwaitingVote().get(0).value();
        var client = new ScriptedLlmClient().functionCalling()
                .enqueueTools(new LlmClient.ToolCall("a", "SubmitVoteTool", "{\"abstain\":true}"),
                        new LlmClient.ToolCall("b", "SubmitVoteTool", "{\"abstain\":true}"))
                .enqueueTools(new LlmClient.ToolCall("c", "SubmitVoteTool", "{\"abstain\":true}"));
        int before = harness.eventCount();
        var state = run(harness, seat, client, TurnBudget.FUNCTION_CALLING, false, SpeechPreviewPort.NONE);
        assertTrue(state.submitted()); assertFalse(state.fallbackUsed());
        assertEquals(1, state.repairRounds());
        assertEquals(before + 1, harness.eventCount());
        assertEquals(2, client.receivedRequests().get(1).messages().stream().filter(m -> m.role().equals("tool")).count());
    }

    @Test
    void rawIdentityInjectionIsRejectedAndFallbackRemainsLegal() {
        var harness = new GatewayHarness(); harness.driveTo(GamePhase.DAY_VOTE);
        int seat = harness.game().seatsAwaitingVote().get(0).value();
        var client = new ScriptedLlmClient().functionCalling()
                .enqueueTools(new LlmClient.ToolCall("a", "SubmitVoteTool", "{\"abstain\":true,\"playerId\":\"p_other\"}"))
                .enqueueTools(new LlmClient.ToolCall("b", "SubmitVoteTool", "{\"abstain\":true,\"gameId\":\"other\"}"));
        var state = run(harness, seat, client, TurnBudget.FUNCTION_CALLING, false, SpeechPreviewPort.NONE);
        assertTrue(state.submitted()); assertTrue(state.fallbackUsed());
        assertEquals(2, state.llmCalls());
        assertTrue(client.receivedRequests().get(1).messages().stream().anyMatch(message ->
                message.role().equals("tool") && message.content().contains("TOOL_IDENTITY_MISMATCH")));
        assertTrue(client.receivedRequests().get(1).messages().stream().anyMatch(message ->
                message.role().equals("user") && message.content().contains("TOOL_IDENTITY_MISMATCH")));
    }

    @Test
    void queryLoopIsBoundedAndLeavesBudgetForFallback() {
        var harness = new GatewayHarness(); harness.driveTo(GamePhase.DAY_VOTE);
        int seat = harness.game().seatsAwaitingVote().get(0).value();
        var client = new ScriptedLlmClient().functionCalling();
        for (int i = 0; i < 10; i++) client.enqueueTools(new LlmClient.ToolCall("q" + i, "QueryGameStateTool", "{\"scope\":\"SELF\"}"));
        var state = run(harness, seat, client, TurnBudget.FUNCTION_CALLING, false, SpeechPreviewPort.NONE);
        assertTrue(state.submitted()); assertTrue(state.fallbackUsed());
        assertEquals(4, state.llmCalls());
        assertTrue(client.receivedRequests().get(2).tools().stream().noneMatch(s -> s.name().startsWith("Query")));
    }

    @Test
    void speechComesOnlyFromCompletedSpeakToolParameters() {
        var harness = new GatewayHarness(); harness.driveTo(GamePhase.DAY_DISCUSS);
        int seat = harness.state().currentTurn().seatNo().value();
        var client = new ScriptedLlmClient().functionCalling()
                .enqueueTools(new LlmClient.ToolCall("s", "SpeakTool", "{\"content\":\"先听大家的证据。\",\"notes\":\"不公开备注\"}"));
        List<SpeechPreviewPort.SpeechPreview> previews = new ArrayList<>();
        var state = run(harness, seat, client, TurnBudget.FUNCTION_CALLING, false, previews::add);
        assertTrue(state.submitted()); assertFalse(state.fallbackUsed());
        assertEquals("先听大家的证据。", state.proposal().speech());
        assertTrue(previews.stream().anyMatch(p -> p.delta().contains("证据")));
        assertTrue(previews.stream().noneMatch(p -> p.delta().contains("备注")));
    }

    @Test
    void mixedBatchDoesNotReadOrWriteBeforeRepair() {
        var harness = new GatewayHarness(); harness.driveTo(GamePhase.DAY_VOTE);
        int seat = harness.game().seatsAwaitingVote().get(0).value();
        var client = new ScriptedLlmClient().functionCalling()
                .enqueueTools(new LlmClient.ToolCall("q", "QueryGameStateTool", "{\"scope\":\"SELF\"}"),
                        new LlmClient.ToolCall("v", "SubmitVoteTool", "{\"abstain\":true}"))
                .enqueueTools(new LlmClient.ToolCall("r", "SubmitVoteTool", "{\"abstain\":true}"));
        var state = run(harness, seat, client, TurnBudget.FUNCTION_CALLING, false, SpeechPreviewPort.NONE);
        assertTrue(state.submitted()); assertFalse(state.fallbackUsed());
        assertTrue(state.toolCalls().stream().noneMatch(record -> record.tool().equals("QueryGameStateTool")));
    }

    @Test
    void proseCannotSubmitAnActionAndRepeatedCallIdCannotExecuteAgain() {
        var harness = new GatewayHarness(); harness.driveTo(GamePhase.DAY_VOTE);
        int seat = harness.game().seatsAwaitingVote().get(0).value();
        var client = new ScriptedLlmClient().functionCalling()
                .enqueueTools(new LlmClient.ToolCall("same", "QueryHistoryTool", "{\"limit\":2}"))
                .enqueueTools(new LlmClient.ToolCall("same", "SubmitVoteTool", "{\"abstain\":true}"))
                .enqueueText("{\"actionType\":\"VOTE\",\"abstain\":true}");
        int before = harness.eventCount();
        var state = run(harness, seat, client, TurnBudget.FUNCTION_CALLING, false, SpeechPreviewPort.NONE);
        assertTrue(state.submitted()); assertTrue(state.fallbackUsed());
        assertEquals(before + 1, harness.eventCount());
        assertEquals(3, state.llmCalls());
    }

    @Test
    void oldRoundCannotBeReboundByRefreshingAggregateVersion() {
        var harness = new GatewayHarness(); harness.driveTo(GamePhase.DAY_VOTE);
        int seat = harness.game().seatsAwaitingVote().get(0).value();
        var view = harness.view(seat);
        var session = new ToolSession(view.gameId(), view.me().playerId(), seat, view.me().role(), view.phase(),
                view.version(), harness.time.nowMillis() + 20000, 6, view.round() - 1);
        var gateway = harness.gateway();
        int before = harness.eventCount();
        assertFalse(gateway.prepareCall(session, new LlmClient.ToolCall("v", "SubmitVoteTool", "{\"abstain\":true}")).valid());
        assertFalse(gateway.submit(session, "SubmitVoteTool", ActionProposal.of("VOTE", null, null)).ok());
        assertEquals(before, harness.eventCount());
    }

    @Test
    void expiredNativeCallNeverProducesAnEvent() {
        var harness = new GatewayHarness(); harness.driveTo(GamePhase.DAY_VOTE);
        int seat = harness.game().seatsAwaitingVote().get(0).value();
        var session = harness.session(seat);
        var expired = new ToolSession(session.gameId(), session.playerId(), seat, session.role(), session.phase(),
                session.turnVersion(), harness.time.nowMillis() - 1, 6, harness.view(seat).round());
        int before = harness.eventCount();
        assertFalse(harness.gateway().prepareCall(expired,
                new LlmClient.ToolCall("v", "SubmitVoteTool", "{\"abstain\":true}")).valid());
        assertEquals(before, harness.eventCount());
    }

    @Test
    void committedActionRemainsAcceptedWhenAuditStorageFails() {
        var harness = new GatewayHarness(); harness.driveTo(GamePhase.DAY_VOTE);
        int seat = harness.game().seatsAwaitingVote().get(0).value();
        var audit = org.mockito.Mockito.mock(com.wolfmind.application.agent.trace.AgentTracePort.class);
        org.mockito.Mockito.doThrow(new IllegalStateException("audit unavailable")).when(audit).record(org.mockito.Mockito.any());
        var gateway = new ToolGatewayAdapter(harness.views, harness.viewQuery, harness.submitService,
                harness.idempotency, audit, harness.time);
        var view = harness.view(seat);
        var session = new ToolSession(view.gameId(), view.me().playerId(), seat, view.me().role(), view.phase(),
                view.version(), harness.time.nowMillis() + 20000, 6, view.round());
        int before = harness.eventCount();
        assertTrue(gateway.submit(session, "SubmitVoteTool", ActionProposal.of("VOTE", null, null)).ok());
        assertEquals(before + 1, harness.eventCount());
    }
}
