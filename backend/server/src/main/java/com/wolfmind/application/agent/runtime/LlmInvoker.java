package com.wolfmind.application.agent.runtime;

import com.wolfmind.application.agent.llm.LlmClient;

/**
 * LLM 调用包装：把 {@link LlmClient} 的失败形态归一为工作流能处理的结果。
 *
 * <p>它存在的理由是把"调用模型"这件事与"处理失败"分开：
 * <ul>
 *   <li>工作流只关心"是否拿到文本 + token 用量 + 失败码"；</li>
 *   <li>超时、供应商异常、未配置模型都在这里收敛成 {@code ok() == false}，
 *       由工作流转入 {@code repair} 或兜底（agent-runtime.md §10）；</li>
 *   <li>{@code available()} 为 false 时工作流**不消耗预算**，直接走兜底——
 *       这正是"纯规则基线"与"CI 无密钥"两种情形的路径。</li>
 * </ul>
 */
public final class LlmInvoker {

    private final LlmClient client;
    private final String settingsDescription;
    private final com.wolfmind.application.port.TimePort clock;
    private com.wolfmind.application.scheduling.TurnTaskStorePort taskStore;

    public LlmInvoker bindTaskStore(com.wolfmind.application.scheduling.TurnTaskStorePort store) {
        this.taskStore = store; return this;
    }

    public LlmInvoker(LlmClient client) {
        this(client, "unset");
    }

    public LlmInvoker(LlmClient client, String settingsDescription) {
        this(client, settingsDescription, System::currentTimeMillis);
    }

    public LlmInvoker(LlmClient client, String settingsDescription, com.wolfmind.application.port.TimePort clock) {
        this.client = client;
        this.settingsDescription = settingsDescription == null ? "unset" : settingsDescription;
        this.clock = clock;
    }

    public boolean available() {
        return client != null && client.available();
    }

    public String providerId() {
        return client == null ? "none" : client.providerId();
    }

    public LlmClient.InteractionMode interactionMode() {
        return client == null ? LlmClient.InteractionMode.JSON_PROPOSAL : client.interactionMode();
    }

    /** 便于启动/测试断言：不含密钥。 */
    public String settingsDescription() {
        return settingsDescription;
    }

    /**
     * 发起一次调用。
     *
     * @param request     提示词请求
     * @param temperature 覆盖请求里的温度（decide 用主温度，repair/rewrite 用修复温度）
     * @param turn        回合请求，仅用于解析超时上限
     */
    public Outcome invoke(
            LlmClient.LlmRequest request,
            double temperature,
            com.wolfmind.application.agent.model.TurnRequest turn) {
        return invoke(request, temperature, turn, null);
    }

    /**
     * 发起一次调用；{@code onPartialText} 非空时走流式，片段只用于展示预览。
     *
     * <p>片段回调抛出的异常不影响本次调用的结果：预览坏了不能让 Agent 丢掉这一回合。
     */
    public Outcome invoke(
            LlmClient.LlmRequest request,
            double temperature,
            com.wolfmind.application.agent.model.TurnRequest turn,
            java.util.function.Consumer<String> onPartialText) {
        if (!available()) {
            return Outcome.failure("UNAVAILABLE", "LLM 未配置或不可用", false);
        }
        if (turn.fence() != null && (taskStore == null || !taskStore.reserve(turn.fence(), "MODEL",
                turn.budget().maxLlmCalls(), clock.nowMillis()))) {
            return Outcome.failure("TASK_BUDGET_OR_EXPIRED", "回合任务已失效或预算耗尽", false);
        }
        long timeoutMs = Math.min(request.timeoutMs(),
                Math.max(1L, turn.deadline() - clock.nowMillis() - turn.budget().submitReserveMs()));
        LlmClient.LlmRequest effective = new LlmClient.LlmRequest(
                request.model(),
                temperature,
                request.messages(),
                request.maxTokens(),
                request.tools(),
                timeoutMs,
                request.speechTurn(), request.interactionMode());
        long startedAt = System.currentTimeMillis();
        try {
            LlmClient.LlmResponse response = onPartialText == null
                    ? client.chat(effective)
                    : client.chatStreaming(effective, isolated(onPartialText));
            long latency = System.currentTimeMillis() - startedAt;
            if (response instanceof LlmClient.LlmResponse.Success success) {
                return new Outcome(
                        true, success.text(), success.promptTokens(), success.completionTokens(),
                        latency, null, null, success.toolCalls(), success.continuation());
            }
            LlmClient.LlmResponse.Failure failure = (LlmClient.LlmResponse.Failure) response;
            return new Outcome(
                    false, null, 0, 0, latency, failure.errorCode(), failure.message());
        } catch (LlmClient.LlmException exception) {
            return new Outcome(
                    false, null, 0, 0, System.currentTimeMillis() - startedAt,
                    exception.errorCode(), exception.getMessage());
        } catch (RuntimeException exception) {
            // 供应商 SDK 抛出的任何非受检异常都归为 UNKNOWN：Runtime 不允许因模型异常而崩溃，
            // 否则一局对局会因为一次网络抖动而中断（agent-runtime.md §10）。
            return new Outcome(
                    false, null, 0, 0, System.currentTimeMillis() - startedAt,
                    "UNKNOWN", exception.getClass().getSimpleName() + ": " + exception.getMessage());
        }
    }

    private static java.util.function.Consumer<String> isolated(java.util.function.Consumer<String> onPartialText) {
        java.util.concurrent.atomic.AtomicBoolean broken = new java.util.concurrent.atomic.AtomicBoolean();
        return text -> {
            if (broken.get()) {
                return;
            }
            try {
                onPartialText.accept(text);
            } catch (RuntimeException error) {
                broken.set(true);
            }
        };
    }

    /** 回合上下文的最小投影，避免本类依赖整个 TurnRequest。 */
    public record TurnContext(long deadline, long reserveMs) {

        public static TurnContext of(com.wolfmind.application.agent.model.TurnRequest request) {
            return new TurnContext(request.deadline(), request.budget().submitReserveMs());
        }
    }

    /** 调用结果。 */    public record Outcome(
            boolean ok,
            String text,
            int promptTokens,
            int completionTokens,
            long latencyMs,
            String errorCode,
            String errorMessage,
            java.util.List<LlmClient.ToolCall> toolCalls,
            LlmClient.Continuation continuation) {

        public Outcome(boolean ok, String text, int promptTokens, int completionTokens,
                       long latencyMs, String errorCode, String errorMessage) {
            this(ok, text, promptTokens, completionTokens, latencyMs, errorCode, errorMessage, java.util.List.of(), null);
        }

        public static Outcome failure(String code, String message, boolean retryable) {
            return new Outcome(false, null, 0, 0, 0L, code, message);
        }
    }
}
