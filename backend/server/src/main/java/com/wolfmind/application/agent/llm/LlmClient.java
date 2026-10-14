package com.wolfmind.application.agent.llm;

import java.util.List;
import java.util.Map;

/**
 * LLM 调用抽象（agent-runtime.md §2 的 {@code LlmClient}）。
 *
 * <p>它是 Agent 子系统与模型供应商之间的唯一边界：
 * <ul>
 *   <li>策略层与工作流只依赖本接口，因此测试可以用固定输出的实现完全离线跑通
 *       （strategy-layer.md §11）；</li>
 *   <li>真实实现放在 {@code infrastructure.ai}，用 LangChain4j 封装；
 *       模型名、API key、baseUrl 全部来自 {@link LlmSettings}，代码里不硬编码；</li>
 *   <li>调用超时、重试与降级由 Runtime 的 {@code BudgetGuard} 与 {@code FallbackPlanner} 负责，
 *       本接口只报告结构化失败。</li>
 * </ul>
 *
 * <p>禁止：本接口不暴露任何工具执行能力。模型只能"选择工具与参数"，真实执行在
 * {@code ToolGatewayPort}（tool-gateway.md §1）。
 */
public interface LlmClient {

    /**
     * 发起一次对话补全。
     *
     * @param request 请求；含分区后的消息序列、模型参数与工具声明
     * @return 补全结果或结构化失败
     * @throws LlmException 仅在实现无法把失败表达为 {@link LlmResponse.Failure} 时抛出
     */
    LlmResponse chat(LlmRequest request);

    /**
     * 流式补全：返回值与 {@link #chat} 相同，生成过程中把原始文本片段按到达顺序交给 {@code onPartialText}。
     *
     * <p>片段只用于展示预览，结果仍以返回的完整文本为准。不支持流式的实现沿用默认行为：
     * 不回调片段，直接返回 {@link #chat} 的结果。
     */
    default LlmResponse chatStreaming(LlmRequest request, java.util.function.Consumer<String> onPartialText) {
        return chat(request);
    }

    sealed interface StreamEvent {
        record TextDelta(String text) implements StreamEvent {}
        record ToolArguments(int index, String callId, String name, String arguments, boolean complete) implements StreamEvent {}
    }

    default LlmResponse chatStreamingEvents(LlmRequest request, java.util.function.Consumer<StreamEvent> consumer) {
        return chatStreaming(request, text -> consumer.accept(new StreamEvent.TextDelta(text)));
    }

    /** 该实现当前是否可用（无密钥、无网络时返回 false，Runtime 据此直接走兜底）。 */
    boolean available();

    /** 实现的稳定标识，用于 Trace（例如 {@code "langchain4j-openai"}）。 */
    String providerId();

    enum InteractionMode { JSON_PROPOSAL, FUNCTION_CALLING }

    default InteractionMode interactionMode() { return InteractionMode.JSON_PROPOSAL; }

    record ToolCall(String callId, String name, String argumentsJson) {}

    /** 供应商续接信息仅回传模型，不写日志或决策分析。 */
    final class Continuation {
        private final String thinking;
        public Continuation(String thinking) { this.thinking = thinking; }
        public String thinking() { return thinking; }
        @Override public String toString() { return "Continuation[redacted]"; }
    }

    /**
     * 一次补全请求。
     *
     * @param model       模型标识，来自 {@code AgentConfig.model}
     * @param temperature 采样温度
     * @param messages    消息序列，按分区顺序；{@code role} 取 {@code system} / {@code user} / {@code assistant}
     * @param maxTokens   生成长度上限
     * @param tools       模型可见的工具声明（只含名称与参数 schema，不含执行逻辑）
     * @param timeoutMs   本次调用的超时上限
     * @param speechTurn  是否属于发言回合（白天发言、遗言）；实现可据此选择更快的推理模式
     */
    record LlmRequest(
            String model,
            double temperature,
            List<Message> messages,
            int maxTokens,
            List<ToolSpec> tools,
            long timeoutMs,
            boolean speechTurn,
            InteractionMode interactionMode) {

        public LlmRequest {
            messages = List.copyOf(messages);
            tools = List.copyOf(tools);
            if (model == null || model.isBlank()) {
                throw new IllegalArgumentException("LlmRequest.model 不能为空：请检查 AgentConfig 的模型配置");
            }
        }

        public LlmRequest(String model, double temperature, List<Message> messages, int maxTokens,
                          List<ToolSpec> tools, long timeoutMs) {
            this(model, temperature, messages, maxTokens, tools, timeoutMs, false);
        }

        public LlmRequest(String model, double temperature, List<Message> messages, int maxTokens,
                          List<ToolSpec> tools, long timeoutMs, boolean speechTurn) {
            this(model, temperature, messages, maxTokens, tools, timeoutMs, speechTurn, InteractionMode.JSON_PROPOSAL);
        }

        public LlmRequest withMessages(List<Message> value) {
            return new LlmRequest(model, temperature, value, maxTokens, tools, timeoutMs, speechTurn, interactionMode);
        }

        /** 一条消息。 */
        public record Message(String role, String content, List<ToolCall> toolCalls,
                              String callId, String toolName, Continuation continuation) {

            public Message {
                if (role == null || content == null) {
                    throw new IllegalArgumentException("消息的 role 与 content 不能为空");
                }
                toolCalls = List.copyOf(toolCalls);
            }

            public Message(String role, String content) { this(role, content, List.of(), null, null, null); }

            public static Message assistant(String text, List<ToolCall> calls, Continuation continuation) {
                return new Message("assistant", text == null ? "" : text, calls, null, null, continuation);
            }

            public static Message tool(ToolCall call, String result) {
                return new Message("tool", result, List.of(), call.callId(), call.name(), null);
            }

            public static Message system(String content) {
                return new Message("system", content);
            }

            public static Message user(String content) {
                return new Message("user", content);
            }
        }

        /**
         * 模型可见的工具声明。
         *
         * <p>{@code name} 与 {@code jsonSchema} 来自工具网关的白名单定义，
         * 保证"prompt 里说可以"与"网关校验通过"同源（strategy-layer.md §7 约束 1）。
         */
        public record ToolSpec(String name, String description, Map<String, Object> jsonSchema) {

            public ToolSpec {
                jsonSchema = Map.copyOf(jsonSchema);
            }
        }
    }

    /** 一次补全结果。 */
    sealed interface LlmResponse permits LlmResponse.Success, LlmResponse.Failure {

        /** 调用成功。 */
        record Success(
                String text,
                String model,
                int promptTokens,
                int completionTokens,
                long latencyMs,
                List<ToolCall> toolCalls,
                Continuation continuation) implements LlmResponse {
            public Success { toolCalls = List.copyOf(toolCalls); }
            public Success(String text, String model, int promptTokens, int completionTokens, long latencyMs) {
                this(text, model, promptTokens, completionTokens, latencyMs, List.of(), null);
            }
        }

        /**
         * 调用失败。
         *
         * @param errorCode 结构化原因：{@code TIMEOUT} / {@code RATE_LIMITED} / {@code AUTH} /
         *                  {@code BAD_REQUEST} / {@code UNAVAILABLE} / {@code UNKNOWN}
         * @param retryable 是否可原样重试
         */
        record Failure(String errorCode, String message, boolean retryable) implements LlmResponse {}

        default boolean ok() {
            return this instanceof Success;
        }
    }

    /** 实现无法归类时的包装异常；调用方应优先处理 {@link LlmResponse.Failure}。 */
    class LlmException extends RuntimeException {

        private static final long serialVersionUID = 1L;

        private final String errorCode;

        public LlmException(String errorCode, String message, Throwable cause) {
            super(message, cause);
            this.errorCode = errorCode;
        }

        public String errorCode() {
            return errorCode;
        }
    }
}
