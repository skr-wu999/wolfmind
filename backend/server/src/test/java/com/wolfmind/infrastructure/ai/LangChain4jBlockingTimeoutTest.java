package com.wolfmind.infrastructure.ai;

import com.wolfmind.application.agent.llm.LlmClient;
import com.wolfmind.application.agent.llm.LlmSettings;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 阻塞调用必须按回合剩余时间截断：驱动同步等待 Agent，SDK 超时含重试，可能远超回合时限。 */
class LangChain4jBlockingTimeoutTest {

    private static final LlmSettings SETTINGS =
            new LlmSettings("openai-compatible", "", "test-key", 30_000L, 0, "fake-model", Map.of());

    private static LangChain4jLlmClient client(ChatModel model) {
        return new LangChain4jLlmClient(SETTINGS, model, null);
    }

    private static LlmClient.LlmRequest request(long timeoutMs) {
        return new LlmClient.LlmRequest("fake-model", 0.6,
                List.of(LlmClient.LlmRequest.Message.user("轮到你")), 256, List.of(), timeoutMs);
    }

    @Test
    @DisplayName("模型迟迟不返回时按请求超时返回 TIMEOUT，而不是等 SDK 自己的超时")
    void slowModelTimesOutAtRequestDeadline() {
        LangChain4jLlmClient client = client(new ChatModel() {
            @Override
            public ChatResponse chat(ChatRequest request) {
                try {
                    Thread.sleep(10_000L);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                }
                return ChatResponse.builder().aiMessage(AiMessage.from("迟到")).build();
            }
        });
        long startedAt = System.currentTimeMillis();

        LlmClient.LlmResponse response = client.chat(request(300L));

        long elapsed = System.currentTimeMillis() - startedAt;
        LlmClient.LlmResponse.Failure failure = assertInstanceOf(LlmClient.LlmResponse.Failure.class, response);
        assertEquals("TIMEOUT", failure.errorCode());
        assertTrue(failure.retryable());
        assertTrue(elapsed < 3_000L, "应在请求超时附近返回，实际 " + elapsed + "ms");
    }

    @Test
    @DisplayName("按时返回的结果照常成功，供应商异常仍归一为结构化失败")
    void normalResultsAndErrorsAreUnchanged() {
        LlmClient.LlmResponse ok = client(new ChatModel() {
            @Override
            public ChatResponse chat(ChatRequest request) {
                return ChatResponse.builder().aiMessage(AiMessage.from("可达")).build();
            }
        }).chat(request(5_000L));
        assertEquals("可达", assertInstanceOf(LlmClient.LlmResponse.Success.class, ok).text());

        LlmClient.LlmResponse failed = client(new ChatModel() {
            @Override
            public ChatResponse chat(ChatRequest request) {
                throw new RuntimeException("HTTP 429 too many requests");
            }
        }).chat(request(5_000L));
        LlmClient.LlmResponse.Failure failure = assertInstanceOf(LlmClient.LlmResponse.Failure.class, failed);
        assertEquals("RATE_LIMITED", failure.errorCode());
    }
}
