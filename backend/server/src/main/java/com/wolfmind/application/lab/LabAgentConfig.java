package com.wolfmind.application.lab;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 实验室里保存的一份 Agent 配置（api-spec.md §8 的 {@code /lab/agent-configs}）。
 *
 * <p><b>只放运行时真的会消费的字段。</b>实验室的价值是"改了参数之后胜率变没变"，
 * 因此每一个可编辑项都必须能追到一处消费点，否则它会变成一个看起来能调、实际无效果的旋钮——
 * 而那比没有这个旋钮更糟，因为它会让人得出错误结论：
 *
 * <table>
 *   <caption>字段 → 消费点</caption>
 *   <tr><th>字段</th><th>消费点</th></tr>
 *   <tr><td>{@code model} / {@code temperature}</td>
 *       <td>{@code LlmInvoker} 发出的请求（{@code LlmRequest}）</td></tr>
 *   <tr><td>{@code strategyProfile}</td><td>{@code DecisionPolicy.weightsFor} 的打分权重</td></tr>
 *   <tr><td>{@code persona}</td><td>{@code ContextAssembler} 的【配置】区（影响表达，不变约束）</td></tr>
 *   <tr><td>{@code memoryMode}</td><td>{@code AgentWorkflow.recall} 的召回条数上限</td></tr>
 *   <tr><td>{@code promptTemplateId}</td><td><b>只作为比对维度</b>：它被记进轨迹与统计，
 *       但本阶段不改变提示词装配。提示词模板的可插拔属于后续工作，
 *       在 {@code PromptBuilder} 支持模板选择之前，把它说成"切换提示词"是不实的。</td></tr>
 * </table>
 *
 * <p><b>{@code role} 是"这份配置打算扮演谁"，不是"它一定会扮演谁"。</b>
 * 角色由 {@code Game.start} 在开局时分配；批量对局按实际分配结果把配置匹配到座位，
 * 匹配不到的角色由规则基线补齐。
 *
 * @param ownerId 创建者账号；配置是账号私有的实验室资产
 */
public record LabAgentConfig(
        String agentConfigId,
        String ownerId,
        String name,
        String role,
        String model,
        double temperature,
        String strategyProfile,
        String persona,
        String memoryMode,
        String promptTemplateId,
        long createdAt,
        long updatedAt) {

    /** 支持的策略档位；与 {@code AgentConfig.StrategyProfile} 同名，避免两套词汇。 */
    public static final List<String> STRATEGY_PROFILES = List.of("AGGRESSIVE", "BALANCED", "CONSERVATIVE");

    /** 记忆模式 → 长期记忆召回条数（agent-runtime.md §7 的 {@code memoryRecallLimit}）。 */
    public static final List<String> MEMORY_MODES = List.of("off", "short", "long");

    /** 可配置角色的白名单；经典九人局的五个角色（守卫不在本阶段规则集内）。 */
    public static final List<String> ROLES = List.of("WEREWOLF", "VILLAGER", "SEER", "WITCH", "HUNTER");

    public LabAgentConfig {
        if (agentConfigId == null || agentConfigId.isBlank()) {
            throw new IllegalArgumentException("agentConfigId 不能为空");
        }
    }

    /** 召回条数：{@code off} 为 0（不召回），其余按模式给固定档位。 */
    public int memoryRecallLimit() {
        return switch (memoryMode == null ? "short" : memoryMode) {
            case "off" -> 0;
            case "long" -> 8;
            default -> 4;
        };
    }

    /**
     * 校验一份草稿并归一化。
     *
     * <p>返回错误映射而不是抛异常：接口层要把它们原样放进 {@code details[]}，
     * 让客户端能一次看到所有字段的问题，而不是改一个报一个。
     */
    public static Map<String, String> validate(Map<String, Object> body) {
        Map<String, String> errors = new LinkedHashMap<>();
        String name = text(body, "name");
        if (name == null || name.isBlank() || name.length() > 24) {
            errors.put("name", "配置名称需为 1–24 个字符。");
        }
        String role = text(body, "role");
        if (role == null || !ROLES.contains(role)) {
            errors.put("role", "角色必须是经典九人局之一：" + String.join(" / ", ROLES));
        }
        String model = text(body, "model");
        if (model == null || model.isBlank() || model.length() > 120) {
            errors.put("model", "模型标识需为 1–120 个字符。");
        }
        Double temperature = number(body, "temperature");
        if (temperature == null || temperature < 0 || temperature > 2) {
            errors.put("temperature", "温度需在 0–2 之间。");
        }
        String profile = text(body, "strategyProfile");
        if (profile == null || !STRATEGY_PROFILES.contains(profile)) {
            errors.put("strategyProfile", "策略档位必须是 " + String.join(" / ", STRATEGY_PROFILES));
        }
        String persona = text(body, "persona");
        if (persona != null && persona.length() > 120) {
            errors.put("persona", "人格描述不能超过 120 个字符。");
        }
        String memory = text(body, "memoryMode");
        if (memory != null && !MEMORY_MODES.contains(memory)) {
            errors.put("memoryMode", "记忆模式必须是 " + String.join(" / ", MEMORY_MODES));
        }
        String template = text(body, "promptTemplateId");
        if (template != null && template.length() > 64) {
            errors.put("promptTemplateId", "提示词模板标识不能超过 64 个字符。");
        }
        return errors;
    }

    /**
     * 角色 → 该配置在批量对局里的匹配键。
     *
     * <p>同一角色有多份配置时按顺序轮转，因此"同一角色放三份配置"就是一场 A/B/C 对比。
     */
    public boolean playsRole(String seatRole) {
        return role != null && role.equals(seatRole);
    }

    private static String text(Map<String, Object> body, String field) {
        Object value = body == null ? null : body.get(field);
        return value instanceof String string ? string.trim() : null;
    }

    private static Double number(Map<String, Object> body, String field) {
        Object value = body == null ? null : body.get(field);
        return value instanceof Number number ? number.doubleValue() : null;
    }
}
