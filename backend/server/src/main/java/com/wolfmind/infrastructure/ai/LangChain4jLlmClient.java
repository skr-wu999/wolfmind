package com.wolfmind.infrastructure.ai;

import com.wolfmind.application.agent.llm.LlmClient;
import com.wolfmind.application.agent.llm.LlmSettings;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.model.chat.response.PartialToolCall;
import dev.langchain4j.model.chat.response.CompleteToolCall;
import com.wolfmind.infrastructure.json.Json;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.chat.response.StreamingChatResponseHandler;
import dev.langchain4j.model.openai.OpenAiChatModel;
import dev.langchain4j.model.openai.OpenAiChatRequestParameters;
import dev.langchain4j.model.openai.OpenAiStreamingChatModel;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Consumer;

/**
 * LangChain4j 实现的模型调用（agent-runtime.md §8 末段、DESIGN.md §9）。
 *
 * <p>它只做一件事件：把 {@link LlmRequest} 变成 LangChain4j 的请求，把响应或异常归一为
 * {@link LlmResponse}。**不注册 LangChain4j 的工具执行函数**——按 tool-gateway.md §1，
 * 工具的真实执行只能发生在 {@code ToolGatewayPort}；若把工具交给框架执行，
 * 就绕过了权限校验流水线。原生工具调用只作为请求返回，由 Runtime 经网关执行。
 *
 * <p>供应商差异通过 {@code provider} + {@code baseUrl} 处理：{@code openai-compatible} 与
 * {@code deepseek} 等都走 OpenAI 兼容协议，只是基址不同；这避免了为每个供应商引一个 SDK。
 */
public final class LangChain4jLlmClient implements LlmClient {

    private static final ExecutorService BLOCKING_CALLS = Executors.newVirtualThreadPerTaskExecutor();

    private final LlmSettings settings;
    private final ChatModel chatModel;
    private final StreamingChatModel streamingModel;

    public LangChain4jLlmClient(LlmSettings settings) {
        this(settings,
                settings.configured() ? build(settings) : null,
                settings.configured() ? buildStreaming(settings) : null);
    }

    LangChain4jLlmClient(LlmSettings settings, ChatModel chatModel, StreamingChatModel streamingModel) {
        this.settings = settings;
        this.chatModel = chatModel;
        this.streamingModel = streamingModel;
    }

    /** 未配置时的构造：{@code available()} 返回 false，Runtime 全程走规则兜底。 */
    public static LangChain4jLlmClient disabled() {
        return new LangChain4jLlmClient(LlmSettings.DISABLED);
    }

    /** 流式模型不做 SDK 内重试：已推送的预览片段无法撤回，重试由 Runtime 的预算决定。 */
    private static StreamingChatModel buildStreaming(LlmSettings settings) {
        OpenAiStreamingChatModel.OpenAiStreamingChatModelBuilder builder = OpenAiStreamingChatModel.builder()
                .returnThinking(true)
                .modelName(settings.defaultModel())
                .apiKey(settings.apiKey())
                .timeout(Duration.ofMillis(settings.timeoutMs()));
        if (!settings.baseUrl().isBlank()) {
            builder.baseUrl(settings.baseUrl());
        }
        if (isDeepSeek(settings)) builder.sendThinking(true, "reasoning_content");
        return builder.build();
    }

    private static ChatModel build(LlmSettings settings) {
        OpenAiChatModel.OpenAiChatModelBuilder builder = OpenAiChatModel.builder()
                .returnThinking(true)
                .modelName(settings.defaultModel())
                .apiKey(settings.apiKey())
                .timeout(Duration.ofMillis(settings.timeoutMs()))
                .maxRetries(settings.maxRetries());
        if (!settings.baseUrl().isBlank()) {
            builder.baseUrl(settings.baseUrl());
        }
        if (isDeepSeek(settings)) builder.sendThinking(true, "reasoning_content");
        // maxTokens 与 temperature 在每次请求里覆盖，因此这里不设默认值，
        // 避免"配置一处、请求一处"两个真相。
        return builder.build();
    }

    /**
     * 只有关闭推理的请求才带 temperature 与 max_tokens：推理模式下 temperature 被供应商忽略，
     * 而 max_tokens 会把推理 token 一并计入，按发言长度设的上限可能截掉推理、留下空正文。
     */
    ChatRequest toChatRequest(LlmRequest request) {
        ChatRequest.Builder builder = ChatRequest.builder().messages(toLangChainMessages(request));
        LlmSettings.ThinkingMode mode = settings.thinkingMode();
        if (mode.disablesFor(request.speechTurn())) {
            builder.parameters(OpenAiChatRequestParameters.builder()
                    .temperature(request.temperature())
                    .maxOutputTokens(request.maxTokens())
                    .customParameters(Map.of("thinking", Map.of("type", "disabled")))
                    .build());
        } else if (mode.lowersFor(request.speechTurn())) {
            builder.parameters(OpenAiChatRequestParameters.builder()
                    .reasoningEffort("low")
                    .customParameters(Map.of("thinking", Map.of("type", "enabled")))
                    .build());
        } else if (mode == LlmSettings.ThinkingMode.ENABLED) {
            builder.parameters(OpenAiChatRequestParameters.builder()
                    .customParameters(Map.of("thinking", Map.of("type", "enabled")))
                    .build());
        }
        builder.modelName(request.model());
        if (request.interactionMode() == InteractionMode.FUNCTION_CALLING) {
            builder.toolSpecifications(request.tools().stream().map(spec -> ToolSpecification.fromJson(
                    Json.write(Map.of("name", spec.name(), "description", spec.description(), "parameters", spec.jsonSchema())))).toList());
        }
        return builder.build();
    }

    /**
     * 阻塞补全：调用线程最多等 {@code request.timeoutMs()}。
     *
     * <p>SDK 自身的超时按连接参数计、且含重试，可能远超回合剩余时间；驱动同步等待 Agent，
     * 不在这里截断就会拖住整局。超时后 SDK 调用仍在后台跑到它自己的超时为止，结果被丢弃。
     */
    @Override
    public LlmResponse chat(LlmRequest request) {
        if (chatModel == null) {
            return new LlmResponse.Failure("UNAVAILABLE", "LLM 未配置：请检查 provider/apiKey/model", false);
        }
        long startedAt = System.currentTimeMillis();
        CompletableFuture<ChatResponse> call = null;
        try {
            ChatRequest chatRequest = toChatRequest(request);
            call = CompletableFuture.supplyAsync(() -> chatModel.chat(chatRequest), BLOCKING_CALLS);
            return success(call.get(request.timeoutMs(), TimeUnit.MILLISECONDS), request, startedAt);
        } catch (TimeoutException timeout) {
            call.cancel(false);
            return new LlmResponse.Failure("TIMEOUT", "调用超过 " + request.timeoutMs() + "ms", true);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            call.cancel(false);
            return new LlmResponse.Failure("UNKNOWN", "调用被中断", false);
        } catch (ExecutionException failed) {
            return failure(failed);
        } catch (RuntimeException exception) {
            return new LlmResponse.Failure(
                    classify(exception), describe(exception), retryable(exception));
        }
    }

    private LlmResponse failure(ExecutionException failed) {
        RuntimeException cause = failed.getCause() instanceof RuntimeException runtime
                ? runtime
                : new RuntimeException(failed.getCause());
        return new LlmResponse.Failure(classify(cause), describe(cause), retryable(cause));
    }

    /**
     * 流式补全：片段按到达顺序回调，调用线程阻塞到完成、失败或超过 {@code request.timeoutMs()}。
     *
     * <p>没有流式模型时退回 {@link #chat}。超时后迟到的片段与结果被丢弃，不再回调。
     */
    @Override
    public LlmResponse chatStreaming(LlmRequest request, Consumer<String> onPartialText) {
        return chatStreamingEvents(request, event -> {
            if (event instanceof StreamEvent.TextDelta text) onPartialText.accept(text.text());
        });
    }

    @Override
    public LlmResponse chatStreamingEvents(LlmRequest request, Consumer<StreamEvent> consumer) {
        if (streamingModel == null) {
            return chat(request);
        }
        long startedAt = System.currentTimeMillis();
        CompletableFuture<ChatResponse> done = new CompletableFuture<>();
        try {
            streamingModel.chat(
                    toChatRequest(request),
                    new StreamingChatResponseHandler() {
                        @Override
                        public void onPartialResponse(String partial) {
                            if (!done.isDone() && partial != null && !partial.isEmpty()) {
                                consumer.accept(new StreamEvent.TextDelta(partial));
                            }
                        }

                        @Override
                        public void onPartialToolCall(PartialToolCall call) {
                            if (!done.isDone()) consumer.accept(new StreamEvent.ToolArguments(
                                    call.index(), call.id(), call.name(), call.partialArguments(), false));
                        }

                        @Override
                        public void onCompleteToolCall(CompleteToolCall call) {
                            if (!done.isDone()) {
                                var tool = call.toolExecutionRequest();
                                consumer.accept(new StreamEvent.ToolArguments(call.index(), tool.id(), tool.name(), tool.arguments(), true));
                            }
                        }

                        @Override
                        public void onCompleteResponse(ChatResponse response) {
                            done.complete(response);
                        }

                        @Override
                        public void onError(Throwable error) {
                            done.completeExceptionally(error);
                        }
                    });
            return success(done.get(request.timeoutMs(), TimeUnit.MILLISECONDS), request, startedAt);
        } catch (TimeoutException timeout) {
            done.cancel(false);
            return new LlmResponse.Failure("TIMEOUT", "流式调用超过 " + request.timeoutMs() + "ms", true);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            done.cancel(false);
            return new LlmResponse.Failure("UNKNOWN", "流式调用被中断", false);
        } catch (ExecutionException failed) {
            return failure(failed);
        } catch (RuntimeException exception) {
            return new LlmResponse.Failure(
                    classify(exception), describe(exception), retryable(exception));
        }
    }

    private static LlmResponse success(ChatResponse response, LlmRequest request, long startedAt) {
        long latency = System.currentTimeMillis() - startedAt;
        String text = response.aiMessage() == null ? "" : response.aiMessage().text();
        int promptTokens = 0;
        int completionTokens = 0;
        if (response.tokenUsage() != null) {
            promptTokens = response.tokenUsage().inputTokenCount() == null
                    ? 0 : response.tokenUsage().inputTokenCount();
            completionTokens = response.tokenUsage().outputTokenCount() == null
                    ? 0 : response.tokenUsage().outputTokenCount();
        }
        return new LlmResponse.Success(
                text == null ? "" : text,
                response.modelName() == null ? request.model() : response.modelName(),
                promptTokens,
                completionTokens,
                latency,
                response.aiMessage() == null ? List.of() : response.aiMessage().toolExecutionRequests().stream()
                        .map(tool -> new ToolCall(tool.id(), tool.name(), tool.arguments())).toList(),
                response.aiMessage() == null || response.aiMessage().thinking() == null
                        ? null : new Continuation(response.aiMessage().thinking()));
    }

    @Override
    public boolean available() {
        return chatModel != null;
    }

    @Override
    public String providerId() {
        return "langchain4j-" + settings.provider();
    }

    @Override
    public InteractionMode interactionMode() { return settings.interactionMode(); }

    private static boolean isDeepSeek(LlmSettings settings) {
        return settings.provider().equals("deepseek") || settings.baseUrl().contains("api.deepseek.com");
    }

    private List<ChatMessage> toLangChainMessages(LlmRequest request) {
        List<ChatMessage> messages = new ArrayList<>();
        for (LlmRequest.Message message : request.messages()) {
            messages.add(switch (message.role()) {
                case "system" -> SystemMessage.from(message.content());
                case "user" -> UserMessage.from(message.content());
                case "assistant" -> AiMessage.builder()
                        .text(message.content().isEmpty() ? null : message.content())
                        .thinking(message.continuation() == null ? null : message.continuation().thinking())
                        .toolExecutionRequests(message.toolCalls().stream().map(call -> ToolExecutionRequest.builder()
                                .id(call.callId()).name(call.name()).arguments(call.argumentsJson()).build()).toList()).build();
                case "tool" -> ToolExecutionResultMessage.from(message.callId(), message.toolName(), message.content());
                default -> throw new IllegalArgumentException("未知消息类型");
            });
        }
        return messages;
    }

    /**
     * 把供应商异常归类为结构化错误码（agent-runtime.md §10 的降级判定需要它）。
     *
     * <p>按类名归类而不是按具体异常类型：不同供应商的 SDK 异常类型不同，
     * 按类名匹配可以让本适配器在换供应商时不用改。包内可见，便于单测直接覆盖分类表。
     */
    static String classify(RuntimeException exception) {
        String name = exception.getClass().getSimpleName().toLowerCase(java.util.Locale.ROOT);
        String message = exception.getMessage() == null
                ? "" : exception.getMessage().toLowerCase(java.util.Locale.ROOT);
        if (name.contains("timeout") || message.contains("timeout")) {
            return "TIMEOUT";
        }
        if (name.contains("ratelimit") || message.contains("429")) {
            return "RATE_LIMITED";
        }
        if (name.contains("authentication") || name.contains("authorization")
                || message.contains("401") || message.contains("403")) {
            return "AUTH";
        }
        if (name.contains("badrequest") || message.contains("400") || message.contains("invalid")) {
            return "BAD_REQUEST";
        }
        if (name.contains("connect") || name.contains("unavailable") || message.contains("connection")) {
            return "UNAVAILABLE";
        }
        return "UNKNOWN";
    }

    private boolean retryable(RuntimeException exception) {
        String code = classify(exception);
        return "TIMEOUT".equals(code) || "RATE_LIMITED".equals(code) || "UNAVAILABLE".equals(code);
    }

    private String describe(RuntimeException exception) {
        String message = exception.getMessage();
        if (message == null) {
            return exception.getClass().getSimpleName();
        }
        // 不把可能含密钥的完整请求 URL 记进错误信息
        return exception.getClass().getSimpleName() + ": "
                + message.substring(0, Math.min(200, message.length()));
    }
}
