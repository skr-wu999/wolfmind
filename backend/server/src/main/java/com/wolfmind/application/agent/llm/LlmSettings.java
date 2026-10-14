package com.wolfmind.application.agent.llm;

import java.util.Map;

/**
 * LLM 连接参数：<b>接入真实模型时唯一需要填的东西</b>。
 *
 * <p>它是配置注入点，不是业务对象：模型名仍来自 {@code AgentConfig}（实验室维度），
 * 而"用哪个供应商、哪个端点、哪个密钥"由本记录承载。因此：
 * <ul>
 *   <li>代码里没有任何硬编码的 model / key / baseUrl；</li>
 *   <li>测试与纯规则基线不构造它，{@code LlmClient.available()} 返回 false，全程走兜底；</li>
 *   <li>载入方式留给接口层（properties / Spring 绑定 / IDEA 运行配置的环境变量），
 *       本记录只解决"参数长什么样"。</li>
 * </ul>
 *
 * <p><b>安全：不要把 {@code apiKey} 写进仓库。</b>建议从环境变量或 IDE 运行配置注入
 * （见 {@code AgentLlmSettings#fromEnvironment()}）。
 *
 * @param provider  供应商标识：{@code openai} / {@code openai-compatible} / {@code deepseek} /
 *                  {@code ollama} / {@code mock}
 * @param baseUrl   接口基址；为空时由实现按 provider 取默认值
 * @param apiKey    密钥；{@code ollama} 与 {@code mock} 可为空
 * @param timeoutMs 单次调用超时
 * @param maxRetries 网络层重试次数（不含"输出修复"轮，后者由预算控制）
 * @param defaultModel 当 {@code AgentConfig.model} 为空时的兜底模型名；两者都空则视为未配置
 * @param extra     供应商专属参数（例如 {@code topP}、{@code seed}），透传不做解释
 */
public record LlmSettings(
        String provider,
        String baseUrl,
        String apiKey,
        long timeoutMs,
        int maxRetries,
        String defaultModel,
        Map<String, Object> extra) {

    /** 未配置：{@code available()} 为 false，Runtime 全程走规则兜底。 */
    public static final LlmSettings DISABLED =
            new LlmSettings("none", "", "", 30_000L, 0, "", Map.of());

    public LlmSettings {
        if (provider == null || provider.isBlank()) {
            provider = "none";
        }
        if (baseUrl == null) {
            baseUrl = "";
        }
        if (apiKey == null) {
            apiKey = "";
        }
        if (defaultModel == null) {
            defaultModel = "";
        }
        extra = Map.copyOf(extra == null ? Map.of() : extra);
        if (timeoutMs <= 0) {
            throw new IllegalArgumentException("timeoutMs 必须为正数");
        }
        if (maxRetries < 0) {
            throw new IllegalArgumentException("maxRetries 不能为负");
        }
    }

    /**
     * 从环境变量读取，供"在 IDEA 里配好参数再验证 9 个 AI"的路径使用。
     *
     * <p>变量名：{@code WOLFMIND_LLM_PROVIDER} / {@code WOLFMIND_LLM_BASE_URL} /
     * {@code WOLFMIND_LLM_API_KEY} / {@code WOLFMIND_LLM_MODEL}。
     */
    public static LlmSettings fromEnvironment() {
        return fromEnvironment(System.getenv());
    }

    /** 便于测试注入：不直接读 {@code System.getenv()}。 */
    public static LlmSettings fromEnvironment(Map<String, String> env) {
        String provider = env.getOrDefault("WOLFMIND_LLM_PROVIDER", "").trim();
        if (provider.isEmpty()) {
            return DISABLED;
        }
        return new LlmSettings(
                provider,
                env.getOrDefault("WOLFMIND_LLM_BASE_URL", "").trim(),
                env.getOrDefault("WOLFMIND_LLM_API_KEY", "").trim(),
                30_000L,
                1,
                env.getOrDefault("WOLFMIND_LLM_MODEL", "").trim(),
                environmentExtra(env));
    }

    private static Map<String, Object> environmentExtra(Map<String, String> env) {
        Map<String, Object> values = new java.util.LinkedHashMap<>(thinkingExtra(env.get("WOLFMIND_LLM_THINKING")));
        String mode = env.get("WOLFMIND_LLM_INTERACTION_MODE");
        if (mode != null && !mode.isBlank()) values.put(INTERACTION_KEY,
                LlmClient.InteractionMode.valueOf(mode.trim().toUpperCase(java.util.Locale.ROOT)).name());
        return Map.copyOf(values);
    }

    /** 把思考模式原值放进 {@link #extra}；空值不放，表示沿用供应商默认。 */
    public static Map<String, Object> thinkingExtra(String raw) {
        return raw == null || raw.isBlank() ? Map.of() : Map.of(THINKING_KEY, raw.trim());
    }

    /** 是否具备调用条件。{@code none} 与 {@code mock} 之外的 provider 需要模型名。 */
    public boolean configured() {
        if ("none".equals(provider)) {
            return false;
        }
        if ("mock".equals(provider) || "ollama".equals(provider)) {
            return true;
        }
        return !apiKey.isBlank() && !defaultModel.isBlank();
    }

    /** {@link #extra} 中思考模式的键，取值见 {@link ThinkingMode}。 */
    public static final String THINKING_KEY = "thinking";
    public static final String INTERACTION_KEY = "interactionMode";

    public LlmClient.InteractionMode interactionMode() {
        Object value = extra.get(INTERACTION_KEY);
        return value == null ? LlmClient.InteractionMode.FUNCTION_CALLING
                : LlmClient.InteractionMode.valueOf(value.toString().trim().toUpperCase(java.util.Locale.ROOT));
    }

    /**
     * 模型推理（thinking）模式。只对支持 {@code thinking} 请求字段的供应商（如 DeepSeek）有效。
     * 推理会显著拉长首字延迟：发言回合在 30 秒时限内超时的主因就是它。
     */
    public enum ThinkingMode {
        /** 不发送该字段，沿用供应商默认。 */
        DEFAULT,
        ENABLED,
        DISABLED,
        /** 发言回合（白天发言、遗言）关闭推理，夜间行动、投票、开枪保留。 */
        SPEECH_OFF,
        /** 发言回合以低强度推理，其余回合沿用供应商默认。 */
        SPEECH_LOW;

        /** 空值为 {@link #DEFAULT}；无法识别返回 null，由调用方决定如何提示。 */
        public static ThinkingMode parse(String raw) {
            if (raw == null || raw.isBlank()) {
                return DEFAULT;
            }
            String normalized = raw.trim().toUpperCase(java.util.Locale.ROOT).replace('-', '_');
            for (ThinkingMode mode : values()) {
                if (mode.name().equals(normalized)) {
                    return mode;
                }
            }
            return null;
        }

        public boolean disablesFor(boolean speechTurn) {
            return this == DISABLED || (this == SPEECH_OFF && speechTurn);
        }

        public boolean lowersFor(boolean speechTurn) {
            return this == SPEECH_LOW && speechTurn;
        }
    }

    /** 无法识别的取值按 {@link ThinkingMode#DEFAULT} 处理；{@link #describeForLog()} 会把原值标出来。 */
    public ThinkingMode thinkingMode() {
        Object raw = extra.get(THINKING_KEY);
        ThinkingMode mode = ThinkingMode.parse(raw == null ? null : raw.toString());
        return mode == null ? ThinkingMode.DEFAULT : mode;
    }

    /** 便于启动日志打印：绝不输出密钥内容。 */
    public String describeForLog() {
        Object rawThinking = extra.get(THINKING_KEY);
        String thinking = rawThinking != null && ThinkingMode.parse(rawThinking.toString()) == null
                ? "default(unrecognized:" + rawThinking + ")"
                : thinkingMode().name().toLowerCase(java.util.Locale.ROOT).replace('_', '-');
        return "provider=" + provider
                + " baseUrl=" + (baseUrl.isBlank() ? "(default)" : baseUrl)
                + " model=" + (defaultModel.isBlank() ? "(unset)" : defaultModel)
                + " apiKey=" + (apiKey.isBlank() ? "(unset)" : "(set)")
                + " thinking=" + thinking + " interactionMode=" + interactionMode();
    }

    public LlmSettings withDefaultModel(String model) {
        return new LlmSettings(provider, baseUrl, apiKey, timeoutMs, maxRetries, model, extra);
    }
}
