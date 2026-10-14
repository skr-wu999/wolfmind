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
import com.wolfmind.application.agent.speech.SpeechPreviewPort;
import com.wolfmind.application.agent.trace.AgentTracePort;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 复读重写：命中后用一次重写；重写仍复读则提交相似度较低的一版，不退成安全模板；
 * 被拦的稿子写进轨迹供赛后分析；非发言回合不检查。
 */
class AgentWorkflowEchoTest {

    private static final long NOW = 1_730_000_000_000L;
    private static final String FIRST_DRAFT = "ACTION:SPEAK:5:首稿";
    private static final String REWRITTEN = "ACTION:SPEAK:5:改写稿";

    private static final class RecordingClient implements LlmClient {

        final List<LlmRequest> requests = new ArrayList<>();
        private final List<String> outputs;

        /** {@code null} 表示这一次调用失败。 */
        RecordingClient(String... outputs) {
            this.outputs = java.util.Arrays.asList(outputs);
        }

        @Override
        public LlmResponse chat(LlmRequest request) {
            requests.add(request);
            if (outputs.isEmpty()) {
                return new LlmResponse.Success("ACTION:SPEAK:5", request.model(), 10, 5, 1L);
            }
            String text = outputs.get(Math.min(requests.size(), outputs.size()) - 1);
            return text == null
                    ? new LlmResponse.Failure("TIMEOUT", "模拟超时", false)
                    : new LlmResponse.Success(text, request.model(), 10, 5, 1L);
        }

        @Override
        public boolean available() {
            return true;
        }

        @Override
        public String providerId() {
            return "recording";
        }

        String lastUserMessage() {
            List<LlmRequest.Message> messages = requests.get(requests.size() - 1).messages();
            return messages.get(messages.size() - 1).content();
        }
    }

    private record Run(AgentWorkflow.FlowContext state, InMemoryAgentTrace trace) {

        List<Map<String, Object>> rejections() {
            return trace.traces("g_echo", "p_4").stream()
                    .filter(event -> event.type() == AgentTracePort.TraceEventType.AGENT_OUTPUT_REJECTED)
                    .map(AgentTracePort.AgentTraceEvent::payload)
                    .toList();
        }
    }

    private static Run run(FakeStrategy strategy, LlmClient client, String phase) {
        AgentConfig config = new AgentConfig(
                "echo-cfg", "复读测试", "fake-model", 0.6, 0.2,
                AgentConfig.StrategyProfile.BALANCED, "中性", AgentConfig.WorkflowMode.A,
                60, 0.5, 8, 3, "tpl", TurnBudget.DEFAULT);
        TurnRequest request = new TurnRequest(
                "g_echo", "p_4", 4, "VILLAGER", "VILLAGE", phase, 1, 0,
                17, NOW + 30_000L, FakeAgentView.of("VILLAGER"),
                List.of("SpeakTool"), TurnBudget.DEFAULT, config);
        InMemoryAgentTrace trace = new InMemoryAgentTrace();
        AgentWorkflow workflow = new AgentWorkflow(
                strategy, new FakeToolGateway(), new InMemoryMemory(), trace,
                new PromptBuilder(), new LlmInvoker(client), new BudgetGuard(NOW), new FixedTime(NOW), null,
                SpeechPreviewPort.NONE);
        return new Run(workflow.run(request, new AgentRuntime.AgentHandle("g_echo", "p_4", 4, "echo-cfg")), trace);
    }

    private static FakeStrategy speaking() {
        return new FakeStrategy().withCandidates(FakeStrategy.candidate("SPEAK", 5, 0.9));
    }

    @Test
    @DisplayName("复读命中后走一次复读重写，重写通过即提交模型发言")
    void echoTriggersOneRewrite() {
        FakeStrategy strategy = speaking().echoing(1);
        RecordingClient client = new RecordingClient(FIRST_DRAFT, REWRITTEN);

        AgentWorkflow.FlowContext state = run(strategy, client, "DAY_DISCUSS").state();

        assertTrue(state.submitted());
        assertEquals("改写稿", state.proposal().speech());
        assertEquals(2, client.requests.size(), "决策 + 复读重写");
        assertTrue(client.lastUserMessage().contains("3 号第 1 轮的发言"), client.lastUserMessage());
        assertTrue(client.lastUserMessage().contains("高度相似"), client.lastUserMessage());
        assertTrue(client.requests.get(1).speechTurn());
    }

    @Test
    @DisplayName("重写后仍复读：首稿相似度更低就提交首稿，不退成安全模板")
    void persistentEchoKeepsLessSimilarOriginal() {
        FakeStrategy strategy = speaking().echoingWith(0.72, 0.9);
        RecordingClient client = new RecordingClient(FIRST_DRAFT, REWRITTEN);

        Run run = run(strategy, client, "DAY_DISCUSS");

        assertTrue(run.state().submitted());
        assertEquals("首稿", run.state().proposal().speech());
        assertEquals(2, client.requests.size());
        Map<String, Object> second = run.rejections().get(1);
        assertEquals("ECHO_CHECK_REWRITE", second.get("stage"));
        assertEquals("ORIGINAL", second.get("kept"));
    }

    @Test
    @DisplayName("重写后仍复读：改写稿相似度更低就提交改写稿")
    void persistentEchoKeepsLessSimilarRewrite() {
        FakeStrategy strategy = speaking().echoingWith(0.9, 0.75);
        RecordingClient client = new RecordingClient(FIRST_DRAFT, REWRITTEN);

        Run run = run(strategy, client, "DAY_DISCUSS");

        assertEquals("改写稿", run.state().proposal().speech());
        assertEquals("REWRITE", run.rejections().get(1).get("kept"));
    }

    @Test
    @DisplayName("复读重写调用失败时提交首稿")
    void failedEchoRewriteKeepsOriginal() {
        FakeStrategy strategy = speaking().echoing(1);
        RecordingClient client = new RecordingClient(FIRST_DRAFT, null);

        AgentWorkflow.FlowContext state = run(strategy, client, "DAY_DISCUSS").state();

        assertTrue(state.submitted());
        assertEquals("首稿", state.proposal().speech());
    }

    @Test
    @DisplayName("泄密拦截的退路不变：重写仍泄密时用安全模板")
    void leakRewriteStillFallsBackToTemplate() {
        FakeStrategy strategy = speaking().leaking();
        RecordingClient client = new RecordingClient(FIRST_DRAFT, REWRITTEN);

        Run run = run(strategy, client, "DAY_DISCUSS");

        assertEquals("SAFE_TEMPLATE", run.state().proposal().reasonCode());
        assertEquals("LEAK_CHECK_REWRITE", run.rejections().get(1).get("stage"));
        run.rejections().forEach(payload -> assertFalse(payload.containsKey("draft"), payload.toString()));
    }

    @Test
    @DisplayName("复读拦截的轨迹带被拦的稿子、被比对的座位与相似度")
    void echoTraceCarriesDraftAndMatch() {
        FakeStrategy strategy = speaking().echoingWith(0.81, 0.86);
        RecordingClient client = new RecordingClient(FIRST_DRAFT, REWRITTEN);

        List<Map<String, Object>> rejections = run(strategy, client, "DAY_DISCUSS").rejections();

        assertEquals(2, rejections.size());
        Map<String, Object> first = rejections.get(0);
        assertEquals("ECHO_CHECK", first.get("stage"));
        assertEquals("首稿", first.get("draft"));
        assertEquals(3, first.get("seatNo"));
        assertEquals(1, first.get("round"));
        assertEquals(0.81, first.get("similarity"));
        assertEquals("改写稿", rejections.get(1).get("draft"));
    }

    @Test
    @DisplayName("非发言回合不做复读检查")
    void nonSpeechTurnsSkipEchoCheck() {
        FakeStrategy strategy = speaking().echoing(5);
        RecordingClient client = new RecordingClient();

        run(strategy, client, "DAY_VOTE");

        assertEquals(0, strategy.echoCheckCalls());
        assertEquals(1, client.requests.size());
    }
}
