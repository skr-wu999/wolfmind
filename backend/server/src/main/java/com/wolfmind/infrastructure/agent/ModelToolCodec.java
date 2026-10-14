package com.wolfmind.infrastructure.agent;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wolfmind.application.agent.llm.LlmClient;
import com.wolfmind.application.agent.model.ActionProposal;
import com.wolfmind.application.agent.model.AgentView;
import com.wolfmind.application.agent.tool.ToolGatewayPort;
import com.wolfmind.infrastructure.json.Json;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** 模型参数保留到 schema 校验完成，之后才映射为领域无关的行动提案。 */
final class ModelToolCodec {
    private static final ObjectMapper JSON = new ObjectMapper()
            .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    private static final int MAX_ARGUMENT_CHARS = 32768;

    private ModelToolCodec() {}

    @SuppressWarnings("unchecked")
    static Map<String, Object> arguments(LlmClient.ToolCall call) {
        if (call.argumentsJson() == null || call.argumentsJson().length() > MAX_ARGUMENT_CHARS) {
            throw new IllegalArgumentException("工具参数为空或超长");
        }
        try {
            return JSON.readValue(call.argumentsJson(), Map.class);
        } catch (Exception badJson) {
            throw new IllegalArgumentException("工具参数必须是完整 JSON 对象，不得有重复字段");
        }
    }

    static ToolGatewayPort.PreparedCall prepare(LlmClient.ToolCall call, AgentView view, ToolGatewayConfig config) {
        if (!ToolCatalog.isRegistered(call.name())) return denied(call, "TOOL_UNKNOWN", "工具未注册");
        try {
            Map<String, Object> args = arguments(call);
            if (args == null) return denied(call, "TOOL_ARGS_INVALID", "工具参数必须是对象");
            for (String key : args.keySet()) {
                if (ToolArgsValidator.IDENTITY_KEYS.contains(key)) {
                    return denied(call, "TOOL_IDENTITY_MISMATCH", "身份字段只能由服务器绑定");
                }
                if (!ToolCatalog.schemaOf(call.name()).fieldNames().contains(key)) {
                    return denied(call, "TOOL_ARGS_INVALID", "工具不接受该参数字段");
                }
            }
            String action = action(call.name(), args);
            for (String flag : List.of("skip", "abstain", "empty", "giveUp")) {
                if (args.get(flag) != null && !Boolean.TRUE.equals(args.get(flag))) {
                    return denied(call, "TOOL_ARGS_INVALID", "放弃标记只能为 true 或省略");
                }
            }
            if (args.get("giveUp") != null && (!"HUNTER_SHOOT".equals(action)
                    || args.get("targetId") != null || args.get("targetSeatNo") != null)) {
                return denied(call, "TOOL_ARGS_INVALID", "giveUp 仅用于猎人无目标放弃开枪");
            }
            if (args.get("actionType") != null && !args.get("actionType").equals(action)) {
                return denied(call, "TOOL_ARGS_INVALID", "actionType 与工具或技能不一致");
            }
            var checked = ToolArgsValidator.validate(call.name(), args,
                    action == null ? null : ToolCatalog.canonicalActionType(action),
                    new ToolArgsValidator.Limits(view.ruleInt("speechMaxLength", 500),
                            config.queryHistoryMaxLimit(), true));
            if (!checked.ok()) return denied(call, checked.code(), checked.message());
            if (action == null) return new ToolGatewayPort.PreparedCall(null, null, null);
            String canonical = ToolCatalog.canonicalActionType(action);
            Integer seat = args.get("targetSeatNo") instanceof Number number ? number.intValue() : null;
            String target = string(args, "targetId");
            if (target == null && seat != null) {
                target = view.me().seatNo() == seat ? view.me().playerId()
                        : view.seat(seat).map(AgentView.SeatView::playerId).orElse(null);
            }
            if (seat == null && target != null) {
                String targetId = target;
                seat = view.me().playerId().equals(targetId) ? Integer.valueOf(view.me().seatNo())
                        : view.other().stream().filter(s -> s.playerId().equals(targetId))
                        .map(AgentView.SeatView::seatNo).findFirst().orElse(null);
            }
            if (args.get("targetSeatNo") != null && target != null) {
                String supplied = target;
                Integer actual = view.me().playerId().equals(supplied) ? Integer.valueOf(view.me().seatNo())
                        : view.other().stream().filter(s -> s.playerId().equals(supplied))
                        .map(AgentView.SeatView::seatNo).findFirst().orElse(null);
                if (!java.util.Objects.equals(actual, seat)) return denied(call, "TOOL_ARGS_INVALID", "目标字段不一致");
            }
            if (target != null && seat == null) return denied(call, "ACTION_INVALID_TARGET", "目标不存在");
            ActionProposal proposal = new ActionProposal(canonical, target, seat,
                    string(args, "content"), args.get("confidence") instanceof Number n ? n.doubleValue() : 0.5,
                    args.get("reasonCode") instanceof String code ? code : "UNSPECIFIED", string(args, "notes"));
            Map<String, Object> normalized = new LinkedHashMap<>();
            normalized.put("actionType", canonical);
            normalized.put("targetId", target);
            normalized.put("speech", proposal.speech());
            normalized.put("confidence", proposal.confidence());
            normalized.put("reasonCode", proposal.reasonCode());
            normalized.put("notes", proposal.notes());
            return new ToolGatewayPort.PreparedCall(proposal, Json.write(normalized), null);
        } catch (IllegalArgumentException invalid) {
            return denied(call, "TOOL_ARGS_INVALID", "工具参数格式或类型不合法");
        }
    }

    private static String action(String tool, Map<String, Object> args) {
        return switch (tool) {
            case ToolCatalog.QUERY_GAME_STATE, ToolCatalog.QUERY_HISTORY -> null;
            case ToolCatalog.SPEAK -> Boolean.TRUE.equals(args.get("skip")) ? "SKIP" : "SPEAK";
            case ToolCatalog.VOTE -> "VOTE";
            case ToolCatalog.KILL -> "WOLF_KILL";
            case ToolCatalog.SKILL -> {
                String skill = string(args, "skill");
                if (skill == null || !ToolCatalog.SKILLS.contains(skill)) throw new IllegalArgumentException("技能非法");
                yield skill;
            }
            default -> throw new IllegalArgumentException("工具未知");
        };
    }

    private static String string(Map<String, Object> args, String name) {
        return args.get(name) instanceof String value ? value : null;
    }

    private static ToolGatewayPort.PreparedCall denied(LlmClient.ToolCall call, String code, String message) {
        return new ToolGatewayPort.PreparedCall(null, null, ToolGatewayPort.ToolResult.Denied.of(call.name(), code, message));
    }

    static List<LlmClient.LlmRequest.ToolSpec> specifications(String role, String phase, ToolGatewayConfig config) {
        List<LlmClient.LlmRequest.ToolSpec> tools = new ArrayList<>();
        for (String tool : RolePhaseMatrix.toolsFor(role, phase)) {
            Map<String, Object> properties = new LinkedHashMap<>();
            for (ToolCatalog.Field field : ToolCatalog.schemaOf(tool).fields()) {
                if (Set.of("actionType", "targetSeatNo").contains(field.name())) continue;
                Map<String, Object> property = new LinkedHashMap<>();
                property.put("type", switch (field.kind()) {
                    case STRING, ENUM -> "string";
                    case INTEGER -> "integer";
                    case NUMBER -> "number";
                    case BOOLEAN -> "boolean";
                });
                property.put("description", fieldDescription(field.name()));
                if (field.kind() == ToolCatalog.Kind.ENUM) {
                    List<String> values = field.enums().stream().sorted().toList();
                    if (field.name().equals("skill")) values = RolePhaseMatrix.grants().stream()
                            .filter(g -> g.tool().equals(tool) && g.roles().contains(role) && g.phases().contains(phase))
                            .map(RolePhaseMatrix.Grant::skill).filter(java.util.Objects::nonNull).distinct().toList();
                    property.put("enum", values);
                }
                if (field.min() != null) property.put("minimum", field.min());
                if (field.max() != null && field.max() > 0) property.put("maximum", field.max());
                if (field.name().equals("limit")) property.put("maximum", config.queryHistoryMaxLimit());
                if (field.maxLength() > 0) property.put("maxLength", field.maxLength());
                properties.put(field.name(), property);
            }
            List<String> required = ToolCatalog.schemaOf(tool).anyOfGroups().stream()
                    .filter(group -> group.size() == 1).flatMap(Set::stream).toList();
            tools.add(new LlmClient.LlmRequest.ToolSpec(tool, description(tool),
                    Map.of("type", "object", "properties", properties, "required", required, "additionalProperties", false)));
        }
        return List.copyOf(tools);
    }

    private static String description(String tool) {
        return switch (tool) {
            case ToolCatalog.QUERY_GAME_STATE -> "查询本座位授权状态。PUBLIC 公开，MY_CAMP 加阵营信息，SELF 加本人私有信息。";
            case ToolCatalog.QUERY_HISTORY -> "按版本区间查询本座位有权看到的历史，结果有上限。";
            case ToolCatalog.SPEAK -> "发言或跳过。content 与 skip=true 互斥且必须选择一个。";
            case ToolCatalog.VOTE -> "投票或弃权。targetId 与 abstain=true 互斥且必须选择一个。";
            case ToolCatalog.KILL -> "狼人提名或空刀。targetId 与 empty=true 互斥且必须选择一个。";
            case ToolCatalog.SKILL -> "使用本角色技能；查验、救人、毒人、开枪需要合法目标，放弃动作不得带目标。";
            default -> throw new IllegalArgumentException("工具未注册");
        };
    }

    private static String fieldDescription(String name) {
        return switch (name) {
            case "targetId" -> "候选集中的目标玩家 ID；不是行动者身份";
            case "content" -> "公开发言，长度遵循本局规则";
            case "skill" -> "本角色本阶段允许的技能";
            case "scope" -> "所需的授权信息范围";
            case "limit" -> "最多返回的事件数量";
            case "fromVersion" -> "起始事件版本（包含），省略从 1 开始";
            case "toVersion" -> "结束事件版本（包含），省略到当前版本";
            case "confidence" -> "可选置信度，0..1";
            case "reasonCode" -> "可选简短决策理由码";
            case "notes" -> "可选私人备注，不公开";
            default -> "仅在选择放弃、弃权或空刀时提供；标记值必须为 true";
        };
    }
}
