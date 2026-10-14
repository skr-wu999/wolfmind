package com.wolfmind.infrastructure.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import com.wolfmind.application.agent.llm.LlmClient;
import com.wolfmind.application.agent.llm.LlmSettings;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.openai.OpenAiChatRequestParameters;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 推理模式开关：发言回合可关闭推理，并在关闭时带上 temperature 与 max_tokens。
 * 线路级用例连本机假服务器，检查真正发出的请求体，不发任何外部请求。
 */
class LangChain4jThinkingTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    private static final String COMPLETION = """
            {"id":"c1","object":"chat.completion","created":1,"model":"fake-model",
             "choices":[{"index":0,"message":{"role":"assistant","content":"可达"},"finish_reason":"stop"}],
             "usage":{"prompt_tokens":3,"completion_tokens":2,"total_tokens":5}}
            """;

    private static final String STREAM = """
            data: {"id":"c1","object":"chat.completion.chunk","created":1,"model":"fake-model","choices":[{"index":0,"delta":{"role":"assistant","content":"可"},"finish_reason":null}]}

            data: {"id":"c1","object":"chat.completion.chunk","created":1,"model":"fake-model","choices":[{"index":0,"delta":{"content":"达"},"finish_reason":"stop"}]}

            data: [DONE]

            """;

    private HttpServer server;
    private final List<String> bodies = new CopyOnWriteArrayList<>();

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/chat/completions", this::handle);
        server.start();
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    private void handle(HttpExchange exchange) throws IOException {
        String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        bodies.add(body);
        boolean stream = JSON.readTree(body).path("stream").asBoolean(false);
        byte[] reply = (stream ? STREAM : COMPLETION).getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", stream ? "text/event-stream" : "application/json");
        exchange.sendResponseHeaders(200, reply.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(reply);
        }
    }

    private LangChain4jLlmClient wiredClient(String thinking) {
        String baseUrl = "http://127.0.0.1:" + server.getAddress().getPort() + "/v1";
        return new LangChain4jLlmClient(new LlmSettings("openai-compatible", baseUrl, "test-key", 10_000L, 0,
                "fake-model", LlmSettings.thinkingExtra(thinking)));
    }

    private static LangChain4jLlmClient offlineClient(String thinking) {
        return new LangChain4jLlmClient(new LlmSettings("openai-compatible", "", "test-key", 10_000L, 0,
                "fake-model", LlmSettings.thinkingExtra(thinking)), null, null);
    }

    private static LlmClient.LlmRequest request(boolean speechTurn) {
        return new LlmClient.LlmRequest("fake-model", 0.6,
                List.of(LlmClient.LlmRequest.Message.system("规则"), LlmClient.LlmRequest.Message.user("轮到你")),
                256, List.of(), 5_000L, speechTurn);
    }

    private JsonNode lastBody() throws IOException {
        assertEquals(1, bodies.size(), "应恰好发出一次请求");
        return JSON.readTree(bodies.get(0));
    }

    @Test
    @DisplayName("speech-off：发言回合关闭推理，并带上 temperature 与 max_tokens")
    void speechOffDisablesThinkingOnSpeechTurn() {
        ChatRequest chatRequest = offlineClient("speech-off").toChatRequest(request(true));

        OpenAiChatRequestParameters parameters =
                assertInstanceOf(OpenAiChatRequestParameters.class, chatRequest.parameters());
        assertEquals(Map.of("thinking", Map.of("type", "disabled")), parameters.customParameters());
        assertEquals(0.6, parameters.temperature());
        assertEquals(256, parameters.maxOutputTokens());
    }

    @Test
    @DisplayName("speech-off：非发言回合不带任何额外参数，沿用供应商默认推理")
    void speechOffKeepsThinkingOnOtherTurns() {
        ChatRequest chatRequest = offlineClient("speech-off").toChatRequest(request(false));

        assertNull(chatRequest.parameters().temperature());
        assertNull(chatRequest.parameters().maxOutputTokens());
        assertFalse(chatRequest.parameters() instanceof OpenAiChatRequestParameters openAi
                && openAi.customParameters() != null && !openAi.customParameters().isEmpty());
    }

    @Test
    @DisplayName("speech-low：发言回合低强度推理且不带 temperature / max_tokens，其余回合不带额外参数")
    void speechLowLowersEffortOnSpeechTurnOnly() {
        OpenAiChatRequestParameters speech = assertInstanceOf(OpenAiChatRequestParameters.class,
                offlineClient("speech-low").toChatRequest(request(true)).parameters());
        assertEquals(Map.of("thinking", Map.of("type", "enabled")), speech.customParameters());
        assertEquals("low", speech.reasoningEffort());
        assertNull(speech.temperature());
        assertNull(speech.maxOutputTokens());

        ChatRequest other = offlineClient("speech-low").toChatRequest(request(false));
        assertFalse(other.parameters() instanceof OpenAiChatRequestParameters openAi
                && (openAi.reasoningEffort() != null
                || openAi.customParameters() != null && !openAi.customParameters().isEmpty()));
    }

    @Test
    @DisplayName("线路级：speech-low 的发言请求体带 reasoning_effort=low 与 thinking=enabled")
    void speechLowWireBody() throws IOException {
        wiredClient("speech-low").chatStreaming(request(true), text -> { });

        JsonNode body = lastBody();
        assertEquals("low", body.path("reasoning_effort").asText());
        assertEquals("enabled", body.path("thinking").path("type").asText());
        assertTrue(body.path("max_tokens").isMissingNode(), body.toString());
    }

    @Test
    @DisplayName("disabled 对所有回合关闭推理；enabled 只发送开启字段")
    void disabledAndEnabledModes() {
        OpenAiChatRequestParameters disabled = assertInstanceOf(OpenAiChatRequestParameters.class,
                offlineClient("disabled").toChatRequest(request(false)).parameters());
        assertEquals(Map.of("thinking", Map.of("type", "disabled")), disabled.customParameters());

        OpenAiChatRequestParameters enabled = assertInstanceOf(OpenAiChatRequestParameters.class,
                offlineClient("enabled").toChatRequest(request(true)).parameters());
        assertEquals(Map.of("thinking", Map.of("type", "enabled")), enabled.customParameters());
        assertNull(enabled.maxOutputTokens());
    }

    @Test
    @DisplayName("线路级：阻塞调用的请求体里有 thinking=disabled、temperature、max_tokens")
    void blockingWireBodyCarriesThinkingField() throws IOException {
        LlmClient.LlmResponse response = wiredClient("speech-off").chat(request(true));

        LlmClient.LlmResponse.Success success = assertInstanceOf(LlmClient.LlmResponse.Success.class, response);
        assertEquals("可达", success.text());
        JsonNode body = lastBody();
        assertEquals("disabled", body.path("thinking").path("type").asText());
        assertEquals(0.6, body.path("temperature").asDouble());
        assertEquals(256, body.path("max_tokens").asInt());
    }

    @Test
    @DisplayName("线路级：流式调用同样带上 thinking=disabled")
    void streamingWireBodyCarriesThinkingField() throws IOException {
        List<String> partials = new CopyOnWriteArrayList<>();

        LlmClient.LlmResponse response = wiredClient("speech-off").chatStreaming(request(true), partials::add);

        LlmClient.LlmResponse.Success success = assertInstanceOf(LlmClient.LlmResponse.Success.class, response);
        assertEquals("可达", success.text());
        assertEquals(List.of("可", "达"), partials);
        JsonNode body = lastBody();
        assertTrue(body.path("stream").asBoolean());
        assertEquals("disabled", body.path("thinking").path("type").asText());
        assertEquals(256, body.path("max_tokens").asInt());
    }

    @Test
    @DisplayName("线路级：未配置推理模式时请求体与改动前一致（无 thinking、无 max_tokens）")
    void defaultModeSendsNoExtraFields() throws IOException {
        wiredClient(null).chat(request(true));

        JsonNode body = lastBody();
        assertTrue(body.path("thinking").isMissingNode(), body.toString());
        assertTrue(body.path("max_tokens").isMissingNode(), body.toString());
    }

    @Test
    @DisplayName("配置载入：环境变量、系统属性与文件键都能设置推理模式，系统属性优先")
    void loaderReadsThinkingFromAllSources() {
        Map<String, String> merged = new HashMap<>();
        merged.put("provider", "deepseek");
        merged.put("apiKey", "test-key");
        merged.put("model", "fake-model");
        merged.put("thinking", "enabled");
        merged.putAll(LlmSettingsLoader.fromEnvironment(Map.of("WOLFMIND_LLM_THINKING", "disabled")));
        Properties properties = new Properties();
        properties.setProperty("wolfmind.llm.thinking", "speech-off");
        merged.putAll(LlmSettingsLoader.fromSystemProperties(properties));

        LlmSettings settings = LlmSettingsLoader.toSettings(merged);

        assertEquals(LlmSettings.ThinkingMode.SPEECH_OFF, settings.thinkingMode());
        assertTrue(settings.describeForLog().contains("thinking=speech-off"), settings.describeForLog());
        assertFalse(settings.describeForLog().contains("test-key"));
    }

    @Test
    @DisplayName("无法识别的取值按默认处理，并在启动摘要里标出原值")
    void unrecognizedModeFallsBackToDefault() {
        LlmSettings settings = new LlmSettings("deepseek", "", "test-key", 10_000L, 0, "fake-model",
                LlmSettings.thinkingExtra("speechoff"));

        assertEquals(LlmSettings.ThinkingMode.DEFAULT, settings.thinkingMode());
        assertTrue(settings.describeForLog().contains("thinking=default(unrecognized:speechoff)"),
                settings.describeForLog());
        assertEquals(LlmSettings.ThinkingMode.SPEECH_OFF, LlmSettings.ThinkingMode.parse(" Speech_Off "));
        assertEquals(LlmSettings.ThinkingMode.SPEECH_OFF,
                LlmSettings.fromEnvironment(Map.of("WOLFMIND_LLM_PROVIDER", "deepseek",
                        "WOLFMIND_LLM_THINKING", "speech-off")).thinkingMode());
    }
}
