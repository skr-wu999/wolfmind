package com.wolfmind.infrastructure.ai;

import com.wolfmind.application.agent.llm.LlmClient;
import com.wolfmind.application.agent.llm.LlmSettings;

import java.util.List;
import java.util.Map;

/**
 * LLM 连通性自检：在跑整局之前，先用一次最小调用确认"参数填对了"。
 *
 * <p>它的存在是为了把"配置错误"和"策略问题"分开：用户配好参数后先跑这个，
 * 拿到 {@code OK} 再跑九人局；否则一个 401 会以"AI 不动"的形式出现在对局里，
 * 排查成本高得多。
 *
 * <p>可以直接在 IDEA 里以 main 类运行：
 * <pre>
 *   java -cp ... com.wolfmind.infrastructure.ai.LlmConnectivityCheck
 * </pre>
 * 参数来自 {@link LlmSettingsLoader}（系统属性 / 环境变量 / {@code wolfmind-llm.properties}）。
 *
 * <p>退出码：{@code 0} 成功或"未配置"；{@code 1} 配置了但调用失败——便于 CI 断言。
 */
public final class LlmConnectivityCheck {

    private LlmConnectivityCheck() {}

    /** 自检结果。 */
    public record Report(boolean configured, boolean reachable, String provider, String model,
                         String errorCode, String message, int promptTokens, int completionTokens) {

        public String describe() {
            if (!configured) {
                return "LLM 未配置：将使用纯规则兜底（9 个 AI 仍可完成对局）。"
                        + "要接入真实模型，请设置 WOLFMIND_LLM_PROVIDER / _API_KEY / _MODEL，"
                        + "或写 wolfmind-llm.properties。";
            }
            if (reachable) {
                return "LLM 可达：provider=" + provider + " model=" + model
                        + " tokens=" + promptTokens + "/" + completionTokens;
            }
            return "LLM 不可达：provider=" + provider + " code=" + errorCode + " message=" + message;
        }
    }

    /** 执行一次最小调用。 */
    public static Report check(LlmSettings settings) {
        if (!settings.configured()) {
            return new Report(false, false, settings.provider(), settings.defaultModel(),
                    null, null, 0, 0);
        }
        LangChain4jLlmClient client = new LangChain4jLlmClient(settings);
        // 按发言回合发送：thinking=speech-off/disabled 时供应商若不认该字段，自检就会报出来
        LlmClient.LlmRequest request = new LlmClient.LlmRequest(
                settings.defaultModel(),
                0.0,
                List.of(LlmClient.LlmRequest.Message.user(settings.interactionMode() == LlmClient.InteractionMode.FUNCTION_CALLING
                        ? "调用 ConnectivityProbe 工具，value 填可达。" : "只回复两个字：可达")),
                16,
                settings.interactionMode() == LlmClient.InteractionMode.FUNCTION_CALLING
                        ? List.of(new LlmClient.LlmRequest.ToolSpec("ConnectivityProbe", "验证原生工具调用，服务端不执行副作用",
                        Map.of("type", "object", "properties", Map.of("value", Map.of("type", "string", "enum", List.of("可达"))),
                                "required", List.of("value"), "additionalProperties", false))) : List.of(),
                settings.timeoutMs(),
                true, settings.interactionMode());
        try {
            LlmClient.LlmResponse response = client.chat(request);
            if (response instanceof LlmClient.LlmResponse.Success success) {
                if (settings.interactionMode() == LlmClient.InteractionMode.FUNCTION_CALLING
                        && (success.toolCalls().size() != 1 || !success.toolCalls().get(0).name().equals("ConnectivityProbe")
                        || !Map.of("value", "可达").equals(com.wolfmind.infrastructure.json.Json.readObject(
                                success.toolCalls().get(0).argumentsJson())))) {
                    return new Report(true, false, settings.provider(), success.model(), "BAD_REQUEST",
                            "模型没有返回原生工具调用", success.promptTokens(), success.completionTokens());
                }
                return new Report(true, true, settings.provider(), success.model(),
                        null, null, success.promptTokens(), success.completionTokens());
            }
            LlmClient.LlmResponse.Failure failure = (LlmClient.LlmResponse.Failure) response;
            return new Report(true, false, settings.provider(), settings.defaultModel(),
                    failure.errorCode(), failure.message(), 0, 0);
        } catch (RuntimeException exception) {
            return new Report(true, false, settings.provider(), settings.defaultModel(),
                    "UNKNOWN", exception.getMessage(), 0, 0);
        }
    }

    /** 命令行入口：打印参数摘要（不含密钥）与自检结果。 */
    public static void main(String[] args) {
        LlmSettings settings = LlmSettingsLoader.load();
        System.out.println("WolfMind LLM 自检");
        System.out.println("  参数来源：系统属性 > 环境变量 > " + LlmSettingsLoader.CONFIG_FILE);
        System.out.println("  参数摘要：" + settings.describeForLog());
        Report report = check(settings);
        System.out.println("  结果：" + report.describe());
        if (!report.configured()) {
            System.out.println();
            System.out.println("模板（复制到 wolfmind-llm.properties 或 IDEA 运行配置）：");
            System.out.println(LlmSettingsLoader.template());
        }
        System.exit(!report.configured() || report.reachable() ? 0 : 1);
    }
}
