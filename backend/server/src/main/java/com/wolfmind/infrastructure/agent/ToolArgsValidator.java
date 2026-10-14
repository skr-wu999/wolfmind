package com.wolfmind.infrastructure.agent;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * G2 参数 Schema 校验（tool-gateway.md §4 G2、§8"参数注入"）。
 *
 * <p>白名单式校验：字段名、类型、枚举、整数范围、字符串长度、必填组、互斥组，六项都来自
 * {@link ToolCatalog} 的 Schema。校验的是**模型可见参数**，不是领域命令参数——
 * 后者由网关按已解析的目标座位重建，模型无法直接构造。
 *
 * <p>身份字段（{@code playerId} / {@code gameId} / {@code role} / {@code seatNo} 等）刻意不算
 * "未知字段"：它们是**已知但禁止**的字段，必须由 G3 用 {@code TOOL_IDENTITY_MISMATCH} 表达，
 * 而不是被 G2 当成拼写错误（两者的修复方式对模型完全不同：一个是"删掉这行"，
 * 一个是"改字段名"）。
 */
public final class ToolArgsValidator {

    /** 参数里出现即判身份注入的字段名（tool-gateway.md §8"身份伪造"、§13）。 */
    public static final Set<String> IDENTITY_KEYS = Set.of(
            "playerId", "playerIds", "gameId", "role", "seatNo", "seat", "actorId", "viewerUserId");

    /** 校验上下文：两个上限来自配置与本局规则参数。 */
    public record Limits(int speechMaxLength, int historyMaxLimit, boolean denyUnknownArgs) {

        public Limits {
            if (speechMaxLength < 1) {
                speechMaxLength = ToolGatewayConfig.DEFAULT_SPEECH_MAX_LENGTH;
            }
            if (historyMaxLimit < 1) {
                historyMaxLimit = ToolGatewayConfig.DEFAULT.queryHistoryMaxLimit();
            }
        }

        public static Limits of(int speechMaxLength, ToolGatewayConfig config) {
            return new Limits(speechMaxLength, config.queryHistoryMaxLimit(), config.denyUnknownArgs());
        }
    }

    /** 校验结论。 */
    public enum Finding {
        OK,
        /** 参数形态非法 → {@code TOOL_ARGS_INVALID}。 */
        ARGS_INVALID,
        /** 参数里夹带身份字段 → {@code TOOL_IDENTITY_MISMATCH}。 */
        IDENTITY_INJECTION
    }

    public record Outcome(Finding finding, String code, String message, List<String> injectedKeys) {

        public Outcome {
            injectedKeys = List.copyOf(injectedKeys);
        }

        public boolean ok() {
            return finding == Finding.OK;
        }

        /** 通过：无拒绝码、无注入。工厂方法名与 {@link #ok()} 区分，避免同名重载歧义。 */
        static Outcome allowed() {
            return new Outcome(Finding.OK, null, null, List.of());
        }

        static Outcome invalid(String message) {
            return new Outcome(Finding.ARGS_INVALID, DenialCodes.TOOL_ARGS_INVALID, message, List.of());
        }

        static Outcome injection(List<String> keys) {
            return new Outcome(
                    Finding.IDENTITY_INJECTION,
                    DenialCodes.TOOL_IDENTITY_MISMATCH,
                    "工具参数不得携带身份字段（" + String.join(", ", keys)
                            + "）：身份只由 Runtime 注入的工具会话决定（tool-gateway.md §8）",
                    keys);
        }
    }

    private ToolArgsValidator() {}

    /**
     * 校验。
     *
     * @param tool                已注册的工具名（G1 已保证）
     * @param args                模型可见参数；显式 {@code null} 值按"未提供"处理
     * @param effectiveActionType 归一化后的领域动作类型，用于目标策略；只读工具传 {@code null}
     */
    public static Outcome validate(String tool, Map<String, Object> args, String effectiveActionType, Limits limits) {
        Map<String, Object> present = new java.util.LinkedHashMap<>();
        if (args != null) {
            args.forEach((key, value) -> {
                if (key != null && value != null) {
                    present.put(key, value);
                }
            });
        }

        List<String> injected = new ArrayList<>();
        for (String key : present.keySet()) {
            if (IDENTITY_KEYS.contains(key)) {
                injected.add(key);
            }
        }
        if (!injected.isEmpty()) {
            return Outcome.injection(injected);
        }

        ToolCatalog.ArgSchema schema = ToolCatalog.schemaOf(tool);
        Set<String> allowed = schema.fieldNames();

        for (Map.Entry<String, Object> entry : present.entrySet()) {
            String name = entry.getKey();
            if (!allowed.contains(name)) {
                if (limits.denyUnknownArgs()) {
                    return Outcome.invalid("工具 " + tool + " 不接受参数 " + name
                            + "，允许的字段：" + String.join(", ", allowed));
                }
                // denyUnknownArgs=false：忽略了未知字段，而且它不会被带进命令参数（参数由网关重建）
                continue;
            }
            ToolCatalog.Field field = schema.field(name);
            String problem = checkField(field, entry.getValue(), limits);
            if (problem != null) {
                return Outcome.invalid("参数 " + name + " 非法：" + problem);
            }
        }

        for (Set<String> group : schema.anyOfGroups()) {
            if (group.stream().noneMatch(present::containsKey)) {
                return Outcome.invalid("必须提供 " + String.join(" 或 ", group) + " 之一");
            }
        }
        String groupProblem = checkGroupExclusivity(schema, present);
        if (groupProblem != null) {
            return Outcome.invalid(groupProblem);
        }

        return checkTargetPolicy(tool, effectiveActionType, present);
    }

    /**
     * 组间互斥：{@code {targetId,targetSeatNo}} 与 {@code {abstain}} 只能出现一组。
     *
     * <p>组内字段不互斥：{@code targetId} 与 {@code targetSeatNo} 是同一个目标的两种写法，
     * 同时给出时由 G9 交叉校验是否指向同一座位。
     */
    private static String checkGroupExclusivity(
            ToolCatalog.ArgSchema schema, Map<String, Object> present) {
        List<String> presentGroups = new java.util.ArrayList<>();
        for (Set<String> group : schema.exclusiveGroups()) {
            for (String name : group) {
                if (present.containsKey(name)) {
                    presentGroups.add(String.join("/", group));
                    break;
                }
            }
        }
        return presentGroups.size() > 1
                ? "参数互斥，不能同时提供 " + String.join(" 与 ", presentGroups)
                : null;
    }

    /** 目标策略：必需 / 禁止 / 可选（tool-gateway.md §2 的参数表）。 */
    private static Outcome checkTargetPolicy(String tool, String effectiveActionType, Map<String, Object> args) {
        ToolCatalog.TargetPolicy policy = ToolCatalog.targetPolicy(tool, effectiveActionType);
        if (policy == null) {
            return Outcome.allowed();
        }
        boolean hasTarget = args.containsKey("targetId") || args.containsKey("targetSeatNo");
        return switch (policy) {
            case REQUIRED -> hasTarget
                    ? Outcome.allowed()
                    : Outcome.invalid(effectiveActionType + " 必须给出 targetId 或 targetSeatNo");
            case FORBIDDEN -> hasTarget
                    ? Outcome.invalid(effectiveActionType + " 不接受目标")
                    : Outcome.allowed();
            case OPTIONAL -> Outcome.allowed();
        };
    }

    private static String checkField(ToolCatalog.Field field, Object value, Limits limits) {
        return switch (field.kind()) {
            case BOOLEAN -> value instanceof Boolean ? null : "必须是布尔值，实际为 " + value;
            case INTEGER -> checkInteger(field, value, limits);
            case NUMBER -> value instanceof Number number && Double.isFinite(number.doubleValue())
                    && number.doubleValue() >= field.min() && number.doubleValue() <= field.max()
                    ? null : "数值必须在 " + field.min() + ".." + field.max() + " 之间";
            case ENUM -> {
                if (!(value instanceof String text)) {
                    yield "必须是字符串枚举，实际为 " + value;
                }
                String normalized = text.strip();
                yield field.enums().contains(normalized)
                        ? null
                        : "未知取值 " + text + "，允许值：" + String.join(" / ", field.enums());
            }
            case STRING -> checkString(field, value, limits);
        };
    }

    private static String checkInteger(ToolCatalog.Field field, Object value, Limits limits) {
        if (!(value instanceof Number number)) {
            return "必须是整数，实际为 " + value;
        }
        double asDouble = number.doubleValue();
        if (asDouble != Math.rint(asDouble)) {
            return "必须是整数，实际为 " + value;
        }
        long asLong = number.longValue();
        Integer min = field.min();
        if (min != null && asLong < min) {
            return "必须 ≥ " + min + "，实际为 " + asLong;
        }
        int max = field.max() == null ? 0 : field.max();
        // limit 的上限来自 gateway.queryHistory.maxLimit（§11）：它同时是文档 §2.1 的 100。
        if ("limit".equals(field.name())) {
            max = limits.historyMaxLimit();
        }
        if (max > 0 && asLong > max) {
            return "必须 ≤ " + max + "，实际为 " + asLong;
        }
        return null;
    }

    private static String checkString(ToolCatalog.Field field, Object value, Limits limits) {
        if (!(value instanceof String text)) {
            return "必须是字符串，实际为 " + value;
        }
        if (text.isBlank()) {
            return "不能为空";
        }
        int maxLength = switch (field.maxLength()) {
            case 0 -> 0;
            case -1 -> limits.speechMaxLength();
            default -> field.maxLength();
        };
        if (maxLength > 0 && text.length() > maxLength) {
            return "长度 " + text.length() + " 超过上限 " + maxLength;
        }
        return null;
    }
}
