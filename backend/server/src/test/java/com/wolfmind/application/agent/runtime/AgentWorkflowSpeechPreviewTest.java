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
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code decide} 节点把模型流式片段接到发言预览口，且预览不改变提交路径。
 *
 * <p>{@link FakeStrategy} 只认 {@code ACTION:...} 格式，因此脚本客户端把"流式片段"与"最终文本"分开给出：
 * 片段是带 {@code speech} 的 JSON，最终文本交给假策略校验。
 */
class AgentWorkflowSpeechPreviewTest {

    private static final long NOW = 1_730_000_000_000L;

    /** 流式脚本客户端：先按片段回调，再返回最终文本。 */
    private static final class StreamingScript implements LlmClient {

        private final List<String> partials;
        private final String finalText;
        int streamingCalls;
        int plainCalls;

        StreamingScript(List<String> partials, String finalText) {
            this.partials = partials;
            this.finalText = finalText;
        }

        @Override
        public LlmResponse chat(LlmRequest request) {
            plainCalls++;
            return new LlmResponse.Success(finalText, request.model(), 10, 5, 1L);
        }

        @Override
        public LlmResponse chatStreaming(LlmRequest request, Consumer<String> onPartialText) {
            streamingCalls++;
            partials.forEach(onPartialText);
            return new LlmResponse.Success(finalText, request.model(), 10, 5, 1L);
        }

        @Override
        public boolean available() {
            return true;
        }

        @Override
        public String providerId() {
            return "streaming-script";
        }
    }

    private static AgentConfig llmConfig() {
        return new AgentConfig(
                "stream-cfg", "流式测试", "fake-model", 0.6, 0.2,
                AgentConfig.StrategyProfile.BALANCED, "中性", AgentConfig.WorkflowMode.A,
                60, 0.5, 8, 3, "tpl", TurnBudget.DEFAULT);
    }

    private static TurnRequest request(String phase) {
        return new TurnRequest(
                "g_stream", "p_4", 4, "VILLAGER", "VILLAGE", phase, 1, 0,
                17, NOW + 30_000L, FakeAgentView.of("VILLAGER"),
                List.of("SpeakTool"), TurnBudget.DEFAULT, llmConfig());
    }

    private static AgentWorkflow.FlowContext run(
            StreamingScript client, FakeStrategy strategy, SpeechPreviewPort preview, String phase) {
        AgentWorkflow workflow = new AgentWorkflow(
                strategy, new FakeToolGateway(), new InMemoryMemory(), new InMemoryAgentTrace(),
                new PromptBuilder(), new LlmInvoker(client), new BudgetGuard(NOW), new FixedTime(NOW), null, preview);
        return workflow.run(request(phase), new AgentRuntime.AgentHandle("g_stream", "p_4", 4, "stream-cfg"));
    }

    private static final List<String> SPEECH_TOKENS = List.of(
            "{\"actionType\":\"SPEAK\",\"spe", "ech\":\"我是村民，", "5 号发言很", "奇怪。\"}");

    @Test
    @DisplayName("发言阶段：片段边生成边推送，带回合版本；动作仍经网关提交一次")
    void speechPhaseStreamsPreviewAndStillSubmits() {
        List<SpeechPreviewPort.SpeechPreview> published = new ArrayList<>();
        StreamingScript client = new StreamingScript(SPEECH_TOKENS, "ACTION:SPEAK:5");
        AgentWorkflow.FlowContext state = run(client, new FakeStrategy()
                .withCandidates(FakeStrategy.candidate("SPEAK", 5, 0.9)), published::add, "DAY_DISCUSS");

        assertEquals(1, client.streamingCalls);
        assertEquals(0, client.plainCalls);
        assertTrue(state.submitted(), "预览不得影响提交，errorCode=" + state.errorCode());
        assertEquals("我是村民，5 号发言很奇怪。",
                published.stream().map(SpeechPreviewPort.SpeechPreview::delta).reduce("", String::concat));
        assertTrue(published.get(published.size() - 1).last());
        assertTrue(published.stream().allMatch(p -> p.expectedVersion() == 17 && p.seatNo() == 4
                && "p_4".equals(p.playerId()) && "g_stream".equals(p.gameId())));
    }

    @Test
    @DisplayName("非发言阶段不走流式：普通调用、零预览")
    void nonSpeechPhaseUsesPlainCall() {
        List<SpeechPreviewPort.SpeechPreview> published = new ArrayList<>();
        StreamingScript client = new StreamingScript(SPEECH_TOKENS, "ACTION:VOTE:5");
        run(client, new FakeStrategy().withCandidates(FakeStrategy.candidate("VOTE", 5, 0.9)),
                published::add, "DAY_VOTE");

        assertEquals(0, client.streamingCalls);
        assertEquals(1, client.plainCalls);
        assertTrue(published.isEmpty());
    }

    @Test
    @DisplayName("泄密检查命中：一段预览都不推，后续照常进入重写")
    void leakingSpeechIsNeverPreviewed() {
        List<SpeechPreviewPort.SpeechPreview> published = new ArrayList<>();
        StreamingScript client = new StreamingScript(SPEECH_TOKENS, "ACTION:SPEAK:5");
        AgentWorkflow.FlowContext state = run(client, new FakeStrategy()
                .withCandidates(FakeStrategy.candidate("SPEAK", 5, 0.9)).leaking(), published::add, "DAY_DISCUSS");

        assertTrue(published.isEmpty(), "泄密的发言不得出现在预览里：" + published);
        assertTrue(state.submitted() || state.fallbackUsed());
    }

    @Test
    @DisplayName("没有预览出口（NONE）时保持原来的非流式调用")
    void noPortKeepsPlainCall() {
        StreamingScript client = new StreamingScript(SPEECH_TOKENS, "ACTION:SPEAK:5");
        AgentWorkflow.FlowContext state = run(client, new FakeStrategy()
                .withCandidates(FakeStrategy.candidate("SPEAK", 5, 0.9)), SpeechPreviewPort.NONE, "DAY_DISCUSS");

        assertEquals(0, client.streamingCalls);
        assertEquals(1, client.plainCalls);
        assertFalse(state.fallbackUsed());
    }

    @Test
    @DisplayName("预览出口抛异常不影响回合结果")
    void brokenPreviewDoesNotBreakTurn() {
        StreamingScript client = new StreamingScript(SPEECH_TOKENS, "ACTION:SPEAK:5");
        AgentWorkflow.FlowContext state = run(client, new FakeStrategy()
                        .withCandidates(FakeStrategy.candidate("SPEAK", 5, 0.9)),
                preview -> { throw new IllegalStateException("连接已断"); }, "DAY_DISCUSS");

        assertTrue(state.submitted(), "errorCode=" + state.errorCode());
    }
}
