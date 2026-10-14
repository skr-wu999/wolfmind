package com.wolfmind.application.agent.runtime;

import com.wolfmind.application.agent.llm.LlmClient;
import com.wolfmind.application.agent.llm.ScriptedLlmClient;
import com.wolfmind.application.agent.model.AgentConfig;
import com.wolfmind.application.agent.model.TurnBudget;
import com.wolfmind.application.agent.model.TurnRequest;
import com.wolfmind.application.agent.fake.FakeAgentView;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link LlmInvoker} 单测：验证"模型失败被归一为结构化结果"，
 * 因为 agent-runtime.md §10 的整条降级链都依赖它。
 */
class LlmInvokerTest {

    private static TurnRequest request(String model) {
        AgentConfig config = new AgentConfig(
                "cfg", "test", model, 0.6, 0.2, AgentConfig.StrategyProfile.BALANCED,
                "中性", AgentConfig.WorkflowMode.A, 60, 0.5, 8, 3, "tpl", TurnBudget.DEFAULT);
        return new TurnRequest(
                "g1", "p_1", 1, "VILLAGER", "VILLAGE", "DAY_DISCUSS", 1, 0,
                10, System.currentTimeMillis() + 30_000L, FakeAgentView.of("VILLAGER"),
                List.of("SpeakTool"), TurnBudget.DEFAULT, config);
    }

    private static LlmClient.LlmRequest prompt() {
        return new LlmClient.LlmRequest(
                "test-model", 0.6,
                List.of(LlmClient.LlmRequest.Message.system("sys"),
                        LlmClient.LlmRequest.Message.user("usr")),
                256, List.of(), 5_000L);
    }

    @Test
    @DisplayName("未配置模型时 available() 为 false，调用返回 UNAVAILABLE 且不抛异常")
    void unavailableClientIsSafe() {
        LlmInvoker invoker = new LlmInvoker(LangChain4jDisabled.client());
        assertFalse(invoker.available(), "未配置时不应声称可用");

        LlmInvoker.Outcome outcome = invoker.invoke(prompt(), 0.6, request(""));
        assertFalse(outcome.ok());
        assertEquals("UNAVAILABLE", outcome.errorCode());
    }

    @Test
    @DisplayName("成功调用返回文本与 token 用量，并携带供应商标识")
    void successCarriesTokens() {
        ScriptedLlmClient client = new ScriptedLlmClient("scripted-test");
        client.enqueueText("ACTION:VOTE:3", 120, 40);
        LlmInvoker invoker = new LlmInvoker(client);

        LlmInvoker.Outcome outcome = invoker.invoke(prompt(), 0.6, request("m"));
        assertTrue(outcome.ok());
        assertEquals("ACTION:VOTE:3", outcome.text());
        assertEquals(120, outcome.promptTokens());
        assertEquals(40, outcome.completionTokens());
        assertEquals("scripted-test", invoker.providerId());
        assertEquals(1, client.callCount());
    }

    @Test
    @DisplayName("供应商失败被归一为错误码，重试性由实现给出")
    void failuresBecomeStructuredOutcomes() {
        ScriptedLlmClient client = new ScriptedLlmClient();
        client.enqueueFailure("TIMEOUT", "调用超时", true);
        LlmInvoker invoker = new LlmInvoker(client);

        LlmInvoker.Outcome outcome = invoker.invoke(prompt(), 0.6, request("m"));
        assertFalse(outcome.ok());
        assertEquals("TIMEOUT", outcome.errorCode());
    }

    @Test
    @DisplayName("供应商抛出的非受检异常不会让整局崩溃（agent-runtime.md §10）")
    void runtimeExceptionsDoNotPropagate() {
        ScriptedLlmClient client = new ScriptedLlmClient();
        client.enqueueThrow("BOOM", "供应商 SDK 异常");
        LlmInvoker invoker = new LlmInvoker(client);

        LlmInvoker.Outcome outcome = invoker.invoke(prompt(), 0.6, request("m"));
        assertFalse(outcome.ok(), "异常必须被收敛为失败结果");
        assertEquals("UNKNOWN", outcome.errorCode());
    }

    @Test
    @DisplayName("温度按节点覆盖：decide 用主温度，repair/rewrite 用修复温度")
    void temperatureIsOverriddenPerNode() {
        ScriptedLlmClient client = new ScriptedLlmClient();
        client.enqueueText("x");
        LlmInvoker invoker = new LlmInvoker(client);

        invoker.invoke(prompt(), 0.2, request("m"));
        assertEquals(0.2, client.receivedRequests().get(0).temperature());
    }

    @Test
    @DisplayName("未配置 provider 的 settings 报告 configured=false 且日志不含密钥")
    void settingsDescribeWithoutSecrets() {
        assertEquals(false, com.wolfmind.application.agent.llm.LlmSettings.DISABLED.configured());
        com.wolfmind.application.agent.llm.LlmSettings settings =
                new com.wolfmind.application.agent.llm.LlmSettings(
                        "openai", "https://api.example.com", "sk-secret-value", 1000L, 0, "gpt-x", Map.of());
        assertTrue(settings.configured());
        String described = settings.describeForLog();
        assertFalse(described.contains("sk-secret-value"), "日志不得包含密钥内容");
        assertTrue(described.contains("(set)"));
    }

    /** 极小的 LlmClient：模拟"未配置"。 */
    private static final class LangChain4jDisabled implements LlmClient {
        static LangChain4jDisabled client() {
            return new LangChain4jDisabled();
        }

        @Override
        public LlmResponse chat(LlmRequest request) {
            return new LlmResponse.Failure("UNAVAILABLE", "未配置", false);
        }

        @Override
        public boolean available() {
            return false;
        }

        @Override
        public String providerId() {
            return "disabled";
        }
    }
}
