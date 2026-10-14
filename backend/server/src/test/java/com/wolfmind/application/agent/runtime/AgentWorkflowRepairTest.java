package com.wolfmind.application.agent.runtime;

import com.wolfmind.application.agent.AgentRuntime;
import com.wolfmind.application.agent.fake.FakeAgentView;
import com.wolfmind.application.agent.fake.FakeStrategy;
import com.wolfmind.application.agent.fake.FakeToolGateway;
import com.wolfmind.application.agent.fake.FixedTime;
import com.wolfmind.application.agent.fake.InMemoryAgentTrace;
import com.wolfmind.application.agent.fake.InMemoryMemory;
import com.wolfmind.application.agent.llm.LlmClient;
import com.wolfmind.application.agent.model.AgentConfig;
import com.wolfmind.application.agent.model.TurnBudget;
import com.wolfmind.application.agent.model.TurnRequest;
import com.wolfmind.application.agent.model.TurnResult;
import com.wolfmind.application.agent.speech.SpeechPreviewPort;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AgentWorkflowRepairTest {

    private static final long NOW = 1_730_000_000_000L;

    private static final class ScriptedClient implements LlmClient {

        private final Deque<String> replies;
        final List<Boolean> speechTurns = new ArrayList<>();
        int calls;

        ScriptedClient(String... replies) {
            this.replies = new ArrayDeque<>(List.of(replies));
        }

        @Override
        public LlmResponse chat(LlmRequest request) {
            calls++;
            speechTurns.add(request.speechTurn());
            String text = replies.size() > 1 ? replies.poll() : replies.peek();
            return new LlmResponse.Success(text, request.model(), 10, 5, 1L);
        }

        @Override
        public boolean available() {
            return true;
        }

        @Override
        public String providerId() {
            return "scripted";
        }
    }

    @Test
    @DisplayName("修复成功且未泄密的发言直接提交，不再进入重写")
    void repairedSpeechIsSubmittedWithoutRewrite() {
        ScriptedClient client = new ScriptedClient("不是约定格式", "ACTION:SPEAK:5");
        AgentConfig config = new AgentConfig(
                "repair-cfg", "修复测试", "fake-model", 0.6, 0.2,
                AgentConfig.StrategyProfile.BALANCED, "中性", AgentConfig.WorkflowMode.A,
                60, 0.5, 8, 3, "tpl", TurnBudget.DEFAULT);
        TurnRequest request = new TurnRequest(
                "g_repair", "p_4", 4, "VILLAGER", "VILLAGE", "DAY_DISCUSS", 1, 0,
                17, NOW + 30_000L, FakeAgentView.of("VILLAGER"),
                List.of("SpeakTool"), TurnBudget.DEFAULT, config);
        AgentWorkflow workflow = new AgentWorkflow(
                new FakeStrategy().withCandidates(FakeStrategy.candidate("SPEAK", 5, 0.9)),
                new FakeToolGateway(), new InMemoryMemory(), new InMemoryAgentTrace(),
                new PromptBuilder(), new LlmInvoker(client), new BudgetGuard(NOW), new FixedTime(NOW), null,
                SpeechPreviewPort.NONE);

        AgentWorkflow.FlowContext state = workflow.run(
                request, new AgentRuntime.AgentHandle("g_repair", "p_4", 4, "repair-cfg"));

        assertTrue(state.submitted(), "errorCode=" + state.errorCode());
        assertEquals("SCRIPTED", state.proposal().reasonCode());
        assertEquals(2, client.calls, "决策 + 修复两次调用；校验失败的理由不能让合规的修复结果再被重写");
        assertNull(workflow.result(state, null).errorCode(), "正常提交不带原因码");
        assertEquals(List.of(true, true), client.speechTurns, "发言回合的决策与修复都应标记为发言请求");
    }

    @Test
    @DisplayName("非发言回合的模型请求不标记为发言，推理模式不受 speech-off 影响")
    void nonSpeechTurnRequestsAreNotMarkedAsSpeech() {
        ScriptedClient client = new ScriptedClient("不是约定格式");

        workflow(client).run(request(NOW + 30_000L, "DAY_VOTE"), handle());

        assertTrue(client.calls > 0);
        assertTrue(client.speechTurns.stream().noneMatch(Boolean::booleanValue), "speechTurns=" + client.speechTurns);
    }

    @Test
    @DisplayName("修复仍不合规而走兜底时，回合结果带上触发兜底的原因")
    void fallbackAfterFailedRepairReportsValidationReason() {
        ScriptedClient client = new ScriptedClient("不是约定格式");
        AgentWorkflow workflow = workflow(client);

        AgentWorkflow.FlowContext state = workflow.run(request(NOW + 30_000L), handle());
        TurnResult result = workflow.result(state, null);

        assertEquals(TurnResult.Status.FALLBACK, result.status());
        assertEquals("OUTPUT_UNPARSABLE", result.errorCode());
        assertEquals(2, result.llmCalls());
    }

    @Test
    @DisplayName("起步已过软截止时不调模型，兜底原因记为 SOFT_DEADLINE")
    void fallbackPastSoftDeadlineReportsSoftDeadline() {
        ScriptedClient client = new ScriptedClient("ACTION:SPEAK:5");
        AgentWorkflow workflow = workflow(client);

        AgentWorkflow.FlowContext state = workflow.run(request(NOW + 1_000L), handle());
        TurnResult result = workflow.result(state, null);

        assertEquals(TurnResult.Status.FALLBACK, result.status());
        assertEquals("SOFT_DEADLINE", result.errorCode());
        assertEquals(0, client.calls);
    }

    @Test
    @DisplayName("兜底目标被判非法（夜里刚死未公布）时换下一个候选，不让猎人丢掉这一枪")
    void fallbackMovesToNextCandidateWhenTargetIsInvalid() {
        FakeToolGateway gateway = new FakeToolGateway().rejectingTargets("p_2", "p_7");
        AgentWorkflow workflow = new AgentWorkflow(
                new FakeStrategy()
                        .withCandidates(
                                FakeStrategy.candidate("HUNTER_SHOOT", 2, 0.9),
                                FakeStrategy.candidate("HUNTER_SHOOT", 7, 0.8),
                                FakeStrategy.candidate("HUNTER_SHOOT", 5, 0.7))
                        .fallbackToFirstCandidate(),
                gateway, new InMemoryMemory(), new InMemoryAgentTrace(),
                new PromptBuilder(), new LlmInvoker(new ScriptedClient("ACTION:HUNTER_SHOOT:2")),
                new BudgetGuard(NOW), new FixedTime(NOW), null, SpeechPreviewPort.NONE);

        AgentWorkflow.FlowContext state = workflow.run(request(NOW + 1_000L, "HUNTER_SHOOT"), handle());
        TurnResult result = workflow.result(state, null);

        assertEquals(TurnResult.Status.FALLBACK, result.status(), "errorCode=" + result.errorCode());
        assertEquals("p_5", gateway.submittedProposals().get(0).targetId());
        assertEquals(3, gateway.sessions().size(), "两次非法目标 + 一次成功");
    }

    @Test
    @DisplayName("目标非法以外的拒绝不换目标重试")
    void fallbackDoesNotRetryOtherDenials() {
        FakeToolGateway gateway = new FakeToolGateway().denying(null, "BUDGET_EXCEEDED");
        AgentWorkflow workflow = new AgentWorkflow(
                new FakeStrategy()
                        .withCandidates(
                                FakeStrategy.candidate("HUNTER_SHOOT", 2, 0.9),
                                FakeStrategy.candidate("HUNTER_SHOOT", 5, 0.7))
                        .fallbackToFirstCandidate(),
                gateway, new InMemoryMemory(), new InMemoryAgentTrace(),
                new PromptBuilder(), new LlmInvoker(new ScriptedClient("ACTION:HUNTER_SHOOT:2")),
                new BudgetGuard(NOW), new FixedTime(NOW), null, SpeechPreviewPort.NONE);

        TurnResult result = workflow.result(
                workflow.run(request(NOW + 1_000L, "HUNTER_SHOOT"), handle()), null);

        assertEquals(TurnResult.Status.TIMEOUT, result.status());
        assertEquals("BUDGET_EXCEEDED", result.errorCode());
        assertEquals(1, gateway.sessions().size());
    }

    private static AgentWorkflow workflow(LlmClient client) {
        return new AgentWorkflow(
                new FakeStrategy().withCandidates(FakeStrategy.candidate("SPEAK", 5, 0.9)),
                new FakeToolGateway(), new InMemoryMemory(), new InMemoryAgentTrace(),
                new PromptBuilder(), new LlmInvoker(client), new BudgetGuard(NOW), new FixedTime(NOW), null,
                SpeechPreviewPort.NONE);
    }

    private static TurnRequest request(long deadline) {
        return request(deadline, "DAY_DISCUSS");
    }

    private static TurnRequest request(long deadline, String phase) {
        AgentConfig config = new AgentConfig(
                "repair-cfg", "修复测试", "fake-model", 0.6, 0.2,
                AgentConfig.StrategyProfile.BALANCED, "中性", AgentConfig.WorkflowMode.A,
                60, 0.5, 8, 3, "tpl", TurnBudget.DEFAULT);
        return new TurnRequest(
                "g_repair", "p_4", 4, "VILLAGER", "VILLAGE", phase, 1, 0,
                17, deadline, FakeAgentView.of("VILLAGER"),
                List.of("SpeakTool"), TurnBudget.DEFAULT, config);
    }

    private static AgentRuntime.AgentHandle handle() {
        return new AgentRuntime.AgentHandle("g_repair", "p_4", 4, "repair-cfg");
    }
}
