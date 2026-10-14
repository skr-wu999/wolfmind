package com.wolfmind.infrastructure.ai;

import com.wolfmind.application.agent.llm.LlmSettings;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Properties;

/**
 * LLM 参数载入：把"在 IDE 里配好参数"这件事变成一个明确的注入点。
 *
 * <p>解析顺序（先命中者生效），这样同一份代码在本地、CI、容器里都能用：
 * <ol>
 *   <li>JVM 系统属性（IDEA 运行配置的 VM options：{@code -Dwolfmind.llm.apiKey=...}）；</li>
 *   <li>环境变量（{@code WOLFMIND_LLM_*}，容器与 CI 用）；</li>
 *   <li>配置文件 {@code wolfmind-llm.properties}（工作目录或 classpath）。</li>
 * </ol>
 *
 * <p>保留 Properties 入口供 IDE 和独立测试使用；Spring 配置在装配时调用本加载器，
 * 应用层只接收框架无关的 {@link LlmSettings}，不负责读取文件或环境变量。
 *
 * <p>密钥纪律：本类不打印任何密钥；{@link LlmSettings#describeForLog()} 只报告"{@code (set)}"。
 * 请把 {@code wolfmind-llm.properties} 加入 {@code .gitignore}，用 IDEA 运行配置或环境变量注入。
 */
public final class LlmSettingsLoader {

    /** 配置文件默认名。 */
    public static final String CONFIG_FILE = "wolfmind-llm.properties";

    private LlmSettingsLoader() {}

    /** 按优先级载入；全都没有时返回 {@link LlmSettings#DISABLED}（Runtime 全程走规则兜底）。 */
    public static LlmSettings load() {
        return load(Path.of("").toAbsolutePath());
    }

    /** 便于测试注入工作目录。 */
    public static LlmSettings load(Path workingDirectory) {
        Map<String, String> merged = new LinkedHashMap<>();
        // 优先级从低到高覆盖：文件 → 环境变量 → 系统属性
        merged.putAll(fromFile(workingDirectory));
        merged.putAll(fromEnvironment(System.getenv()));
        merged.putAll(fromSystemProperties(System.getProperties()));
        return toSettings(merged);
    }

    /** 环境变量形式：{@code WOLFMIND_LLM_PROVIDER} / {@code _BASE_URL} / {@code _API_KEY} / {@code _MODEL}。 */
    public static Map<String, String> fromEnvironment(Map<String, String> env) {
        Map<String, String> values = new LinkedHashMap<>();
        putIfPresent(values, "provider", env.get("WOLFMIND_LLM_PROVIDER"));
        putIfPresent(values, "baseUrl", env.get("WOLFMIND_LLM_BASE_URL"));
        putIfPresent(values, "apiKey", env.get("WOLFMIND_LLM_API_KEY"));
        putIfPresent(values, "model", env.get("WOLFMIND_LLM_MODEL"));
        putIfPresent(values, "timeoutMs", env.get("WOLFMIND_LLM_TIMEOUT_MS"));
        putIfPresent(values, "maxRetries", env.get("WOLFMIND_LLM_MAX_RETRIES"));
        putIfPresent(values, LlmSettings.THINKING_KEY, env.get("WOLFMIND_LLM_THINKING"));
        putIfPresent(values, LlmSettings.INTERACTION_KEY, env.get("WOLFMIND_LLM_INTERACTION_MODE"));
        return values;
    }

    /** 系统属性形式：{@code -Dwolfmind.llm.provider=...}。 */
    public static Map<String, String> fromSystemProperties(Properties properties) {
        Map<String, String> values = new LinkedHashMap<>();
        putIfPresent(values, "provider", properties.getProperty("wolfmind.llm.provider"));
        putIfPresent(values, "baseUrl", properties.getProperty("wolfmind.llm.baseUrl"));
        putIfPresent(values, "apiKey", properties.getProperty("wolfmind.llm.apiKey"));
        putIfPresent(values, "model", properties.getProperty("wolfmind.llm.model"));
        putIfPresent(values, "timeoutMs", properties.getProperty("wolfmind.llm.timeoutMs"));
        putIfPresent(values, "maxRetries", properties.getProperty("wolfmind.llm.maxRetries"));
        putIfPresent(values, LlmSettings.THINKING_KEY, properties.getProperty("wolfmind.llm.thinking"));
        putIfPresent(values, LlmSettings.INTERACTION_KEY, properties.getProperty("wolfmind.llm.interactionMode"));
        return values;
    }

    /** 配置文件形式：工作目录优先，其次 classpath。 */
    public static Map<String, String> fromFile(Path workingDirectory) {
        Properties properties = new Properties();
        Path candidate = workingDirectory.resolve(CONFIG_FILE);
        if (Files.isReadable(candidate)) {
            try (InputStream stream = Files.newInputStream(candidate)) {
                properties.load(stream);
            } catch (IOException exception) {
                // 配置读不出来不是崩溃理由：退回"未配置"，Runtime 走规则兜底并留下可诊断信息
                return Map.of();
            }
        } else {
            try (InputStream stream = LlmSettingsLoader.class.getClassLoader()
                    .getResourceAsStream(CONFIG_FILE)) {
                if (stream == null) {
                    return Map.of();
                }
                properties.load(stream);
            } catch (IOException exception) {
                return Map.of();
            }
        }
        Map<String, String> values = new LinkedHashMap<>();
        for (String key : properties.stringPropertyNames()) {
            values.put(key, properties.getProperty(key));
        }
        return values;
    }

    /** 把扁平键值转成 {@link LlmSettings}；缺 provider 视为未配置。 */
    public static LlmSettings toSettings(Map<String, String> values) {
        String provider = trim(values.get("provider"));
        if (provider.isEmpty()) {
            return LlmSettings.DISABLED;
        }
        return new LlmSettings(
                provider,
                trim(values.get("baseUrl")),
                trim(values.get("apiKey")),
                parseLong(values.get("timeoutMs"), 30_000L),
                (int) parseLong(values.get("maxRetries"), 1L),
                trim(values.get("model")),
                extras(values));
    }

    private static Map<String, Object> extras(Map<String, String> values) {
        Map<String, Object> extra = new LinkedHashMap<>(LlmSettings.thinkingExtra(values.get(LlmSettings.THINKING_KEY)));
        String mode = values.get(LlmSettings.INTERACTION_KEY);
        if (mode != null && !mode.isBlank()) {
            extra.put(LlmSettings.INTERACTION_KEY, com.wolfmind.application.agent.llm.LlmClient.InteractionMode
                    .valueOf(mode.trim().toUpperCase(java.util.Locale.ROOT)).name());
        }
        return Map.copyOf(extra);
    }

    /** 生成一份带注释的模板，便于用户直接粘贴到 IDEA 运行配置或用环境变量。 */
    public static String template() {
        return """
                # WolfMind LLM 参数模板。使用方式（三选一，优先级 系统属性 > 环境变量 > 本文件）：
                #   1) IDEA 运行配置的 VM options:  -Dwolfmind.llm.provider=openai -Dwolfmind.llm.apiKey=sk-xxx -Dwolfmind.llm.model=gpt-4o-mini
                #   2) 环境变量:                    WOLFMIND_LLM_PROVIDER / WOLFMIND_LLM_API_KEY / WOLFMIND_LLM_MODEL
                #   3) 本文件（工作目录下）:        复制为 wolfmind-llm.properties 并填入，务必不要提交到仓库
                #
                # 不要把真实密钥写进仓库；本文件只是模板，加载器不会读取 .template 后缀。

                # 供应商：openai / openai-compatible / deepseek / ollama / none
                provider=none
                # 原生工具调用为默认；回退可设 JSON_PROPOSAL
                interactionMode=FUNCTION_CALLING
                # 兼容协议的基址；留空则用供应商默认值
                baseUrl=
                # 密钥（ollama 可留空）
                apiKey=
                # 默认模型名；座位未指定模型时用它
                model=
                # 单次调用超时（毫秒）
                timeoutMs=30000
                # 网络层重试次数（不含"输出修复"轮）
                maxRetries=1
                # 推理模式（DeepSeek 等支持 thinking 字段的供应商）：留空=供应商默认 / enabled / disabled /
                # speech-off（白天发言与遗言关闭推理，夜间行动、投票、开枪保留）/
                # speech-low（白天发言与遗言低强度推理）
                thinking=
                """;
    }

    private static void putIfPresent(Map<String, String> target, String key, String value) {
        if (value != null && !value.isBlank()) {
            target.put(key, value.trim());
        }
    }

    private static String trim(String value) {
        return value == null ? "" : value.trim();
    }

    private static long parseLong(String value, long fallback) {
        if (value == null || value.isBlank()) {
            return fallback;
        }
        try {
            return Long.parseLong(value.trim());
        } catch (NumberFormatException exception) {
            return fallback;
        }
    }
}
