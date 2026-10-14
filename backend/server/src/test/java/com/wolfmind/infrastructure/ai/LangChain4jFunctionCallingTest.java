package com.wolfmind.infrastructure.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import com.wolfmind.application.agent.llm.LlmClient;
import com.wolfmind.application.agent.llm.LlmSettings;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.*;

class LangChain4jFunctionCallingTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private HttpServer server;
    private final List<JsonNode> bodies = new CopyOnWriteArrayList<>();

    @BeforeEach
    void start() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/chat/completions", this::reply);
        server.start();
    }

    @AfterEach void stop() { server.stop(0); }

    private void reply(HttpExchange exchange) {
        try {
            JsonNode body = JSON.readTree(exchange.getRequestBody()); bodies.add(body);
            boolean streaming = body.path("stream").asBoolean();
            String reply = streaming ? """
                    data: {"id":"r","object":"chat.completion.chunk","created":1,"model":"fake","choices":[{"index":0,"delta":{"role":"assistant","reasoning_content":"private-continuation"},"finish_reason":null}]}

                    data: {"id":"r","object":"chat.completion.chunk","created":1,"model":"fake","choices":[{"index":0,"delta":{"tool_calls":[{"index":0,"id":"call_q","type":"function","function":{"name":"QueryGameStateTool","arguments":"{\\\"scope\\\":"}}]},"finish_reason":null}]}

                    data: {"id":"r","object":"chat.completion.chunk","created":1,"model":"fake","choices":[{"index":0,"delta":{"tool_calls":[{"index":0,"function":{"arguments":"\\\"SELF\\\"}"}}]},"finish_reason":"tool_calls"}]}

                    data: [DONE]

                    """ : """
                    {"id":"r","object":"chat.completion","created":1,"model":"fake",
                     "choices":[{"index":0,"message":{"role":"assistant","content":null,"reasoning_content":"private-continuation",
                      "tool_calls":[{"id":"call_q","type":"function","function":{"name":"QueryGameStateTool","arguments":"{\\\"scope\\\":\\\"SELF\\\"}"}}]},"finish_reason":"tool_calls"}],
                     "usage":{"prompt_tokens":10,"completion_tokens":5,"total_tokens":15}}
                    """;
            byte[] bytes = reply.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", streaming ? "text/event-stream" : "application/json");
            exchange.sendResponseHeaders(200, bytes.length);
            try (var output = exchange.getResponseBody()) { output.write(bytes); }
        } catch (Exception failure) { throw new RuntimeException(failure); }
    }

    private LangChain4jLlmClient client() {
        return new LangChain4jLlmClient(new LlmSettings("deepseek",
                "http://127.0.0.1:" + server.getAddress().getPort() + "/v1", "test-key", 5000, 0,
                "fake", LlmSettings.thinkingExtra("enabled")));
    }

    private static LlmClient.LlmRequest request(List<LlmClient.LlmRequest.Message> messages) {
        return new LlmClient.LlmRequest("override-model", 0.6, messages, 1000,
                List.of(new LlmClient.LlmRequest.ToolSpec("QueryGameStateTool", "查询授权状态",
                        Map.of("type", "object", "properties", Map.of("scope", Map.of("type", "string", "enum", List.of("SELF", "PUBLIC"))),
                                "required", List.of("scope"), "additionalProperties", false))),
                5000, false, LlmClient.InteractionMode.FUNCTION_CALLING);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void sendsRealSchemasAndRoundTripsCallsAndThinking(boolean streaming) {
        var client = client();
        var initial = request(List.of(LlmClient.LlmRequest.Message.user("检查状态")));
        List<LlmClient.StreamEvent> events = new CopyOnWriteArrayList<>();
        var response = streaming ? client.chatStreamingEvents(initial, events::add) : client.chat(initial);
        var success = assertInstanceOf(LlmClient.LlmResponse.Success.class, response);
        assertEquals("", success.text());
        assertEquals(List.of(new LlmClient.ToolCall("call_q", "QueryGameStateTool", "{\"scope\":\"SELF\"}")), success.toolCalls());
        assertNotNull(success.continuation());
        assertEquals("Continuation[redacted]", success.continuation().toString());
        assertEquals("override-model", bodies.get(0).path("model").asText());
        var schema = bodies.get(0).path("tools").get(0).path("function").path("parameters");
        assertEquals("SELF", schema.path("properties").path("scope").path("enum").get(0).asText());
        assertEquals(false, client.toChatRequest(initial).parameters().toolSpecifications().get(0)
                .parameters().additionalProperties());
        // SDK 在非 strict 请求中省略此字段，未知参数仍由网关原始参数校验拒绝。
        assertFalse(schema.has("additionalProperties"));
        List<LlmClient.LlmRequest.Message> history = new ArrayList<>(initial.messages());
        history.add(LlmClient.LlmRequest.Message.assistant(success.text(), success.toolCalls(), success.continuation()));
        history.add(LlmClient.LlmRequest.Message.tool(success.toolCalls().get(0), "{\"ok\":true}"));
        assertInstanceOf(LlmClient.LlmResponse.Success.class, client.chat(request(history)));
        var sent = bodies.get(1).path("messages");
        assertEquals("assistant", sent.get(1).path("role").asText());
        assertEquals("private-continuation", sent.get(1).path("reasoning_content").asText());
        assertEquals("tool", sent.get(2).path("role").asText());
        assertEquals("call_q", sent.get(2).path("tool_call_id").asText());
        if (streaming) {
            assertTrue(events.stream().anyMatch(e -> e instanceof LlmClient.StreamEvent.ToolArguments t && t.complete()));
            assertTrue(events.stream().noneMatch(e -> e instanceof LlmClient.StreamEvent.TextDelta));
        }
    }

    @Test
    void legacyModeDoesNotSendTools() {
        var original = request(List.of(LlmClient.LlmRequest.Message.user("检查")));
        var legacy = new LlmClient.LlmRequest(original.model(), original.temperature(), original.messages(),
                original.maxTokens(), original.tools(), 5000, false, LlmClient.InteractionMode.JSON_PROPOSAL);
        assertInstanceOf(LlmClient.LlmResponse.Success.class, client().chat(legacy));
        assertFalse(bodies.get(0).has("tools"));
    }

    @Test
    void modeAndBudgetCanBeConfiguredAndRolledBack() {
        var nativeSettings = LlmSettingsLoader.toSettings(Map.of("provider", "deepseek", "apiKey", "test-key", "model", "fake"));
        assertEquals(LlmClient.InteractionMode.FUNCTION_CALLING, nativeSettings.interactionMode());
        assertEquals(4, com.wolfmind.infrastructure.agent.wiring.Phase3AgentAssembly.defaultConfigFor(nativeSettings).budget().maxLlmCalls());
        var legacy = LlmSettingsLoader.toSettings(Map.of("provider", "deepseek", "apiKey", "test-key", "model", "fake",
                "interactionMode", "JSON_PROPOSAL"));
        assertEquals(2, com.wolfmind.infrastructure.agent.wiring.Phase3AgentAssembly.defaultConfigFor(legacy).budget().maxLlmCalls());
        assertEquals("JSON_PROPOSAL", LlmSettingsLoader.fromEnvironment(Map.of("WOLFMIND_LLM_INTERACTION_MODE", "JSON_PROPOSAL")).get("interactionMode"));
        assertThrows(IllegalArgumentException.class, () -> LlmSettingsLoader.toSettings(Map.of("provider", "deepseek", "interactionMode", "typo")));
    }
}
