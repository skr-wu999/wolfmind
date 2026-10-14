package com.wolfmind.application.agent.llm;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/**
 * 脚本化的 LLM 客户端：按预置脚本返回固定输出，用于离线驱动整条 Agent 链路。
 *
 * <p>它是 strategy-layer.md §11 末句的实现：「LLM 通过接口注入，测试使用固定输出的实现，
 * 因此工作流可以完全离线验证」。用途有三：
 * <ol>
 *   <li>工作流单测：断言节点路径、修复轮数、失败降级；</li>
 *   <li>九人局端到端：让 9 个座位各自按脚本产出合法动作，无需任何网络与付费调用；</li>
 *   <li>对照实验：同一脚本跑两遍必须得到同一对局，用于验证可复现性。</li>
 * </ol>
 *
 * <p>线程安全：整局测试是单线程驱动的（同一 gameId 只有一个回合窗口），因此这里只做最小同步。
 * 每次 {@code chat} 消耗脚本中的一项；脚本耗尽后返回 {@code exhaustedResponse}。
 */
public final class ScriptedLlmClient implements LlmClient {

    private final String providerId;
    private final Deque<Object> script = new ArrayDeque<>();
    private final List<LlmRequest> received = new ArrayList<>();
    private boolean available = true;
    private InteractionMode mode = InteractionMode.JSON_PROPOSAL;

    public ScriptedLlmClient functionCalling() { mode = InteractionMode.FUNCTION_CALLING; return this; }
    @Override public InteractionMode interactionMode() { return mode; }
    public ScriptedLlmClient enqueueTools(ToolCall... calls) {
        script.addLast(new LlmResponse.Success("", "scripted-model", 100, 50, 5L, List.of(calls), null));
        return this;
    }
    private LlmResponse exhaustedResponse =
            new LlmResponse.Failure("UNAVAILABLE", "脚本已耗尽", false);

    public ScriptedLlmClient() {
        this("scripted");
    }

    public ScriptedLlmClient(String providerId) {
        this.providerId = providerId;
    }

    /** 排队一个成功响应。 */
    public ScriptedLlmClient enqueueText(String text) {
        script.addLast(new LlmResponse.Success(text, "scripted-model", 100, 50, 5L));
        return this;
    }

    /** 排队一个成功响应，显式给出 token 用量。 */
    public ScriptedLlmClient enqueueText(String text, int promptTokens, int completionTokens) {
        script.addLast(new LlmResponse.Success(text, "scripted-model", promptTokens, completionTokens, 5L));
        return this;
    }

    /** 排队一个失败响应。 */
    public ScriptedLlmClient enqueueFailure(String errorCode, String message, boolean retryable) {
        script.addLast(new LlmResponse.Failure(errorCode, message, retryable));
        return this;
    }

    /** 排队一个"抛出异常"的响应：验证 Runtime 不会因供应商异常而崩溃。 */
    public ScriptedLlmClient enqueueThrow(String errorCode, String message) {
        script.addLast(new RuntimeException(errorCode + ": " + message));
        return this;
    }

    /** 脚本耗尽时的响应。 */
    public ScriptedLlmClient exhaustedResponse(LlmResponse response) {
        this.exhaustedResponse = response;
        return this;
    }

    /** 模拟"模型不可用"：工作流会跳过 LLM 直接走兜底，且不消耗预算。 */
    public ScriptedLlmClient unavailable() {
        this.available = false;
        return this;
    }

    @Override
    public LlmResponse chat(LlmRequest request) {
        received.add(request);
        Object next = script.pollFirst();
        if (next == null) {
            return exhaustedResponse;
        }
        if (next instanceof RuntimeException exception) {
            throw exception;
        }
        return (LlmResponse) next;
    }

    @Override
    public boolean available() {
        return available;
    }

    @Override
    public String providerId() {
        return providerId;
    }

    /** 收到的全部请求，供测试断言"提示词分区内容"与"调用次数"。 */
    public List<LlmRequest> receivedRequests() {
        return List.copyOf(received);
    }

    public int callCount() {
        return received.size();
    }

    public int remainingScript() {
        return script.size();
    }

    /** 便于构造一个"总是输出同一段 JSON"的客户端，用于九人局驱动。 */
    public static ScriptedLlmClient alwaysText(String text, int times) {
        ScriptedLlmClient client = new ScriptedLlmClient();
        for (int index = 0; index < times; index++) {
            client.enqueueText(text);
        }
        return client;
    }
}
