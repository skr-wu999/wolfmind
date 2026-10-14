package com.wolfmind.infrastructure.ai;

import com.wolfmind.application.agent.llm.LlmClient;
import com.wolfmind.application.agent.llm.LlmSettings;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.chat.response.StreamingChatResponseHandler;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BiConsumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** LangChain4j 流式适配：用假模型离线验证片段回调、完成、失败与超时，不发任何网络请求。 */
class LangChain4jStreamingTest {

    private static final LlmSettings SETTINGS =
            new LlmSettings("openai-compatible", "", "test-key", 30_000L, 0, "fake-model", Map.of());

    private static final ChatModel UNUSED_BLOCKING_MODEL = new ChatModel() {
        @Override
        public ChatResponse chat(ChatRequest request) {
            throw new AssertionError("流式调用不应退回阻塞模型");
        }
    };

    private static StreamingChatModel streaming(BiConsumer<ChatRequest, StreamingChatResponseHandler> behaviour) {
        return new StreamingChatModel() {
            @Override
            public void chat(ChatRequest request, StreamingChatResponseHandler handler) {
                behaviour.accept(request, handler);
            }
        };
    }

    private static LlmClient.LlmRequest request(long timeoutMs) {
        return new LlmClient.LlmRequest("fake-model", 0.6,
                List.of(LlmClient.LlmRequest.Message.system("规则"), LlmClient.LlmRequest.Message.user("轮到你")),
                256, List.of(), timeoutMs);
    }

    @Test
    @DisplayName("片段按到达顺序回调，返回值是完整文本")
    void forwardsPartialsAndReturnsFullText() {
        LangChain4jLlmClient client = new LangChain4jLlmClient(SETTINGS, UNUSED_BLOCKING_MODEL,
                streaming((request, handler) -> new Thread(() -> {
                    handler.onPartialResponse("{\"speech\":\"");
                    handler.onPartialResponse("你好\"}");
                    handler.onCompleteResponse(ChatResponse.builder()
                            .aiMessage(AiMessage.from("{\"speech\":\"你好\"}")).build());
                }).start()));
        List<String> partials = new CopyOnWriteArrayList<>();

        LlmClient.LlmResponse response = client.chatStreaming(request(5_000L), partials::add);

        LlmClient.LlmResponse.Success success = assertInstanceOf(LlmClient.LlmResponse.Success.class, response);
        assertEquals("{\"speech\":\"你好\"}", success.text());
        assertEquals(List.of("{\"speech\":\"", "你好\"}"), partials);
    }

    @Test
    @DisplayName("供应商报错归一为结构化失败")
    void errorBecomesStructuredFailure() {
        LangChain4jLlmClient client = new LangChain4jLlmClient(SETTINGS, UNUSED_BLOCKING_MODEL,
                streaming((request, handler) -> handler.onError(new RuntimeException("HTTP 429 too many requests"))));

        LlmClient.LlmResponse response = client.chatStreaming(request(5_000L), text -> { });

        LlmClient.LlmResponse.Failure failure = assertInstanceOf(LlmClient.LlmResponse.Failure.class, response);
        assertEquals("RATE_LIMITED", failure.errorCode());
        assertTrue(failure.retryable());
    }

    @Test
    @DisplayName("超时返回 TIMEOUT，之后迟到的片段不再回调")
    void timeoutStopsForwarding() throws Exception {
        StreamingChatResponseHandler[] captured = new StreamingChatResponseHandler[1];
        LangChain4jLlmClient client = new LangChain4jLlmClient(SETTINGS, UNUSED_BLOCKING_MODEL,
                streaming((request, handler) -> captured[0] = handler));
        List<String> partials = new CopyOnWriteArrayList<>();

        LlmClient.LlmResponse response = client.chatStreaming(request(200L), partials::add);
        captured[0].onPartialResponse("迟到的片段");

        LlmClient.LlmResponse.Failure failure = assertInstanceOf(LlmClient.LlmResponse.Failure.class, response);
        assertEquals("TIMEOUT", failure.errorCode());
        assertTrue(partials.isEmpty(), "超时后的片段不得再进入预览：" + partials);
    }
}
