package com.wolfmind.infrastructure.agent;

import com.wolfmind.application.agent.model.ActionProposal;
import com.wolfmind.application.game.ActionCodec;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 工具目录（tool-gateway.md §2）与参数 Schema（§4 G2）的唯一来源。
 *
 * <p>三件事只能在这里定义：
 * <ol>
 *   <li>工具名：{@code QueryGameStateTool} / {@code QueryHistoryTool} / {@code SpeakTool} /
 *       {@code SubmitVoteTool} / {@code KillPlayerTool} / {@code UseSkillTool}；</li>
 *   <li>每个工具的参数 Schema（字段名、类型、枚举、整数范围、长度上限、必填与互斥），
 *       它同时是 LangChain4j 侧工具定义的来源与 G2 的判据；</li>
 *   <li>文档两套词汇之间的桥接：§2 的<b>工具参数</b>（{@code targetId} / {@code skip} /
 *       {@code giveUp} / {@code skill}）与 api-spec §5.3 的<b>领域命令参数</b>
 *       （{@code targetSeatNo} / {@code abstain} / {@code empty} / {@code content}）。</li>
 * </ol>
 *
 * <p>为什么需要第 3 条：模型按 §2 说话（{@code KillPlayerTool{targetId:"p_5}"}），而
 * {@link ActionCodec} 按 §5.3 收参数（{@code targetSeatNo:5}）。若让模型直接产出后者，
 * 等于把领域命令形状暴露给模型；若让应用层适配前者，又会把 Agent 词汇带进 application.game。
 * 桥接放在网关（防腐层）是唯一不破坏 DEP-3 的位置。
 *
 * <p>本类无状态、不可变，可并发调用。
 */
public final class ToolCatalog {

    public static final String QUERY_GAME_STATE = "QueryGameStateTool";
    public static final String QUERY_HISTORY = "QueryHistoryTool";
    public static final String SPEAK = "SpeakTool";
    public static final String VOTE = "SubmitVoteTool";
    public static final String KILL = "KillPlayerTool";
    public static final String SKILL = "UseSkillTool";

    /** 兜底动作 {@code SKIP} 的载体通配符：它可由任意已注册工具承载（agent-runtime.md §10）。 */
    public static final String SKIP_CARRIER = "*";

    /** §2 的六个工具，顺序与文档表格一致。 */
    public static final List<String> TOOLS =
            List.of(QUERY_GAME_STATE, QUERY_HISTORY, SPEAK, VOTE, KILL, SKILL);

    /** 只读工具。 */
    public static final List<String> READ_TOOLS = List.of(QUERY_GAME_STATE, QUERY_HISTORY);

    /** §2.1 的三个 scope；**不存在 GOD**（§8 观测作弊、§13 禁止事项）。 */
    public static final List<String> SCOPES = List.of("SELF", "PUBLIC", "MY_CAMP");

    /** §2.2 的七个技能名。 */
    public static final List<String> SKILLS = List.of(
            "SEER_INSPECT", "SEER_SKIP", "WITCH_SAVE", "WITCH_POISON", "WITCH_PASS",
            "HUNTER_SHOOT", "HUNTER_GIVE_UP");

    /** 目标策略：由动作类型决定参数里"必须有目标 / 必须没有目标 / 可选"。 */
    public enum TargetPolicy {
        REQUIRED,
        FORBIDDEN,
        OPTIONAL
    }

    /** 参数字段类型（G2 的类型校验）。 */
    public enum Kind {
        STRING,
        INTEGER,
        NUMBER,
        BOOLEAN,
        ENUM
    }

    /**
     * 一个参数字段。
     *
     * @param maxLength 字符串上限；{@code 0} 表示本 Schema 不设限，{@code -1} 表示取本局
     *                  {@code rules.speechMaxLength}（发言是唯一受对局规则约束的字段）
     */
    public record Field(String name, Kind kind, Set<String> enums, Integer min, Integer max, int maxLength) {

        public Field {
            enums = enums == null ? Set.of() : Set.copyOf(enums);
        }

        public static Field string(String name, int maxLength) {
            return new Field(name, Kind.STRING, Set.of(), null, null, maxLength);
        }

        public static Field integer(String name, int min, int max) {
            return new Field(name, Kind.INTEGER, Set.of(), min, max, 0);
        }

        public static Field flag(String name) {
            return new Field(name, Kind.BOOLEAN, Set.of(), null, null, 0);
        }

        public static Field enumeration(String name, Set<String> values) {
            return new Field(name, Kind.ENUM, values, null, null, 0);
        }
    }

    /**
     * 一个工具的参数 Schema。
     *
     * @param anyOfGroups     每组至少要出现一个字段（"要么给目标、要么给 abstain"）
     * @param exclusiveGroups 每组最多出现一个字段；<b>组与组之间也互斥</b>——
     *                        例如投票的 {@code {targetId,targetSeatNo}} 与 {@code {abstain}}
     *                        只能有一组出现。targetId 与 targetSeatNo 是同一个目标的两种写法，
     *                        把它们当成互斥字段会把正常提案判成参数注入。
     */
    public record ArgSchema(
            String tool,
            List<Field> fields,
            List<Set<String>> anyOfGroups,
            List<Set<String>> exclusiveGroups) {

        public ArgSchema {
            fields = List.copyOf(fields);
            anyOfGroups = List.copyOf(anyOfGroups);
            exclusiveGroups = List.copyOf(exclusiveGroups);
        }

        public Set<String> fieldNames() {
            return fields.stream().map(Field::name).collect(java.util.stream.Collectors.toCollection(
                    java.util.LinkedHashSet::new));
        }

        public Field field(String name) {
            return fields.stream().filter(field -> field.name().equals(name)).findFirst().orElse(null);
        }
    }

    /** 声明动作类型与 `actionType` 字段的合法取值（含 §2.2 技能名别名）。 */
    private static final Set<String> DECLARED_ACTION_TYPES = Set.of(
            "SPEAK", "VOTE", "WOLF_KILL", "SEER_INSPECT", "WITCH_SAVE", "WITCH_POISON", "WITCH_PASS",
            "HUNTER_SHOOT", "SKIP", "SEER_SKIP", "HUNTER_SKIP", "HUNTER_GIVE_UP");

    private static final List<Field> WRITE_COMMON_FIELDS = List.of(
            Field.enumeration("actionType", DECLARED_ACTION_TYPES),
            Field.string("targetId", 0),
            Field.integer("targetSeatNo", 1, 9),
            new Field("confidence", Kind.NUMBER, Set.of(), 0, 1, 0),
            Field.string("reasonCode", 100),
            Field.string("notes", 1000));

    private static final Map<String, ArgSchema> SCHEMAS = buildSchemas();

    private ToolCatalog() {}

    private static Map<String, ArgSchema> buildSchemas() {
        Map<String, ArgSchema> schemas = new LinkedHashMap<>();

        schemas.put(QUERY_GAME_STATE, new ArgSchema(
                QUERY_GAME_STATE,
                List.of(Field.enumeration("scope", Set.copyOf(SCOPES))),
                List.of(Set.of("scope")),
                List.of()));

        schemas.put(QUERY_HISTORY, new ArgSchema(
                QUERY_HISTORY,
                List.of(
                        Field.integer("fromVersion", 1, 0),
                        Field.integer("toVersion", 1, 0),
                        Field.integer("limit", 1, 0)),
                List.of(),
                List.of()));

        schemas.put(SPEAK, new ArgSchema(
                SPEAK,
                concat(WRITE_COMMON_FIELDS, List.of(
                        Field.string("content", -1),
                        Field.flag("skip"),
                        Field.enumeration("reason", Set.of("VOLUNTARY", "TIMEOUT")))),
                List.of(Set.of("content", "skip")),
                List.of(Set.of("content"), Set.of("skip"))));

        schemas.put(VOTE, new ArgSchema(
                VOTE,
                concat(WRITE_COMMON_FIELDS, List.of(Field.flag("abstain"))),
                List.of(Set.of("targetId", "targetSeatNo", "abstain")),
                List.of(Set.of("targetId", "targetSeatNo"), Set.of("abstain"))));

        schemas.put(KILL, new ArgSchema(
                KILL,
                concat(WRITE_COMMON_FIELDS, List.of(Field.flag("empty"))),
                List.of(Set.of("targetId", "targetSeatNo", "empty")),
                List.of(Set.of("targetId", "targetSeatNo"), Set.of("empty"))));

        schemas.put(SKILL, new ArgSchema(
                SKILL,
                concat(WRITE_COMMON_FIELDS, List.of(
                        Field.enumeration("skill", Set.copyOf(SKILLS)),
                        Field.flag("giveUp"))),
                List.of(Set.of("skill")),
                List.of()));

        return Map.copyOf(schemas);
    }

    private static List<Field> concat(List<Field> head, List<Field> tail) {
        List<Field> all = new java.util.ArrayList<>(head);
        all.addAll(tail);
        return List.copyOf(all);
    }

    /** G1：工具名是否在注册表白名单内。 */
    public static boolean isRegistered(String tool) {
        return tool != null && SCHEMAS.containsKey(tool);
    }

    public static ArgSchema schemaOf(String tool) {
        ArgSchema schema = tool == null ? null : SCHEMAS.get(tool);
        if (schema == null) {
            throw new IllegalArgumentException("未注册的工具没有参数 Schema：" + tool);
        }
        return schema;
    }

    /** 声明的动作类型是否是合法取值（G2 的枚举校验）。 */
    public static boolean isDeclaredActionType(String actionType) {
        return actionType != null && DECLARED_ACTION_TYPES.contains(actionType);
    }

    public static Set<String> declaredActionTypes() {
        return DECLARED_ACTION_TYPES;
    }

    /**
     * 别名 → 主名：§2.2 的<b>技能名</b>归一化为 api-spec §5.3 的<b>动作类型</b>。
     *
     * <p>两个词汇表的边界就在这里：{@code SEER_SKIP} / {@code HUNTER_GIVE_UP} 是模型与工具参数
     * 里的技能名，而 {@code ActionProposal.actionType}（application.agent 的动作词汇表）只有主名。
     * 入站的别名仍被接受（{@link #effectiveActionType} 会桥接），但<b>对外只公布主名</b>：
     * 策略层的候选生成读 {@code allowedActionTypes}，如果那里出现别名，候选集与
     * {@code ActionProposal} 的契约就会同时存在两套写法。
     */
    public static String canonicalActionType(String actionType) {
        if (actionType == null) {
            return null;
        }
        return switch (actionType) {
            case "SEER_SKIP" -> "SKIP";
            case "HUNTER_GIVE_UP", "HUNTER_SKIP" -> "HUNTER_SHOOT";
            default -> actionType;
        };
    }

    /**
     * 别名桥接：把模型/策略层声明的动作类型归一化为领域动作类型。
     *
     * <ul>
     *   <li>{@code SPEAK} 但没有发言 → {@code SKIP}（§2：{@code SpeakTool{skip:true}} 映射
     *       {@code SkipAction}，事件是 {@code SPEECH_SKIPPED}）；</li>
     *   <li>{@code SEER_SKIP} → {@code SKIP}（领域用 {@code SkipAction} 表达，事件 {@code SEER_SKIPPED}）；</li>
     *   <li>{@code HUNTER_GIVE_UP} / {@code HUNTER_SKIP} → {@code HUNTER_SHOOT}（放弃开枪由
     *       {@code HunterShotAction.giveUp} 表达，事件 {@code HUNTER_SKIPPED}）。</li>
     * </ul>
     *
     * <p>只此一份：策略层声明的动作、网关 G2/G5/G7 的判定、提交给应用层的动作类型都走它，
     * 否则"网关认 SEER_SKIP、应用层只认 SKIP"就会变成一个只在夜里出现的缺陷。
     *
     * @param hasSpeech 是否真的带发言文本（决定 {@code SPEAK} 是发言还是跳过）
     */
    public static String effectiveActionType(String declaredActionType, boolean hasSpeech) {
        if (declaredActionType == null) {
            return null;
        }
        if ("SPEAK".equals(declaredActionType)) {
            return hasSpeech ? "SPEAK" : "SKIP";
        }
        return canonicalActionType(declaredActionType);
    }

    /** 目标策略：null 表示该工具/动作没有目标概念（只读工具）。 */
    public static TargetPolicy targetPolicy(String tool, String effectiveActionType) {
        if (!isRegistered(tool) || READ_TOOLS.contains(tool)) {
            return null;
        }
        return switch (effectiveActionType == null ? "" : effectiveActionType) {
            case "SPEAK", "SKIP", "WITCH_PASS" -> TargetPolicy.FORBIDDEN;
            case "VOTE", "WOLF_KILL", "HUNTER_SHOOT" -> TargetPolicy.OPTIONAL;
            case "SEER_INSPECT", "WITCH_SAVE", "WITCH_POISON" -> TargetPolicy.REQUIRED;
            default -> TargetPolicy.FORBIDDEN;
        };
    }

    /**
     * 构造模型可见参数（G2 的校验对象）。
     *
     * <p>字段名与 §2 的工具参数表一致：发言是 {@code content} 或 {@code skip+reason}，
     * 投票是 {@code targetId/targetSeatNo} 或 {@code abstain}，狼刀是 {@code targetId/targetSeatNo}
     * 或 {@code empty}，技能是 {@code skill}（加可选目标）。**必须按工具生成**：把
     * {@code {actionType, targetSeatNo}} 原样交给 {@code SubmitVoteTool} 的 Schema，
     * 会把一次合法弃权判成"参数缺失"。
     *
     * <p>不做任何白名单过滤——未知字段必须能被 G2 看见，否则"未知字段直接拒绝"这条永远不会触发。
     */
    public static Map<String, Object> modelArgs(String tool, ActionProposal proposal) {
        Map<String, Object> args = new LinkedHashMap<>();
        if (proposal == null) {
            return Map.of();
        }
        String declared = proposal.actionType();
        args.put("actionType", declared);
        if (proposal.notes() != null && !proposal.notes().isBlank()) {
            args.put("notes", proposal.notes());
        }
        switch (tool == null ? "" : tool) {
            case SPEAK -> {
                if (proposal.hasSpeech()) {
                    args.put("content", proposal.speech());
                } else {
                    // §2：SpeakTool{skip:true, reason} 映射 SkipAction（事件 SPEECH_SKIPPED）
                    args.put("skip", true);
                    args.put("reason", "VOLUNTARY");
                }
            }
            case VOTE -> {
                if (proposal.targetSeatNo() != null || proposal.targetId() != null) {
                    putTarget(args, proposal);
                } else {
                    args.put("abstain", true);
                }
            }
            case KILL -> {
                if (proposal.targetSeatNo() != null || proposal.targetId() != null) {
                    putTarget(args, proposal);
                } else {
                    args.put("empty", true);
                }
            }
            case SKILL -> {
                String skill = skillNameOf(declared);
                if (skill != null) {
                    args.put("skill", skill);
                }
                if (proposal.targetSeatNo() != null || proposal.targetId() != null) {
                    putTarget(args, proposal);
                }
            }
            default -> {
                // 只读工具不走写路径；这里保持空实现，避免把目标字段带进不该有的 Schema
            }
        }
        return Map.copyOf(args);
    }

    private static void putTarget(Map<String, Object> args, ActionProposal proposal) {
        if (proposal.targetId() != null) {
            args.put("targetId", proposal.targetId());
        }
        if (proposal.targetSeatNo() != null) {
            args.put("targetSeatNo", proposal.targetSeatNo());
        }
    }

    /** §2.2 的技能名：动作类型里属于技能的那些与技能名同名，别名在 {@link #effectiveActionType} 里归一化。 */
    private static String skillNameOf(String declaredActionType) {
        if (declaredActionType == null) {
            return null;
        }
        return SKILLS.contains(declaredActionType) ? declaredActionType : null;
    }

    /**
     * 构造领域命令参数（api-spec.md §5.3 的 {@code params}）。
     *
     * <p>座位号只用 G9 解析出来的 {@code resolvedTargetSeat}，不用模型的原始字段：
     * 这样"targetId 与 targetSeatNo 各自指向不同座位"的注入没有任何生效机会。
     */
    public static Map<String, Object> domainParams(
            String effectiveActionType, ActionProposal proposal, Integer resolvedTargetSeat) {
        Map<String, Object> params = new LinkedHashMap<>();
        switch (effectiveActionType == null ? "" : effectiveActionType) {
            case "SPEAK" -> params.put(ActionCodec.PARAM_CONTENT, proposal.speech());
            case "VOTE" -> {
                if (resolvedTargetSeat == null) {
                    params.put(ActionCodec.PARAM_ABSTAIN, true);
                } else {
                    params.put(ActionCodec.PARAM_TARGET_SEAT_NO, resolvedTargetSeat);
                }
            }
            case "WOLF_KILL" -> {
                if (resolvedTargetSeat == null) {
                    params.put(ActionCodec.PARAM_EMPTY, true);
                } else {
                    params.put(ActionCodec.PARAM_TARGET_SEAT_NO, resolvedTargetSeat);
                }
            }
            case "SEER_INSPECT", "WITCH_SAVE", "WITCH_POISON" ->
                    params.put(ActionCodec.PARAM_TARGET_SEAT_NO, resolvedTargetSeat);
            case "HUNTER_SHOOT" -> {
                if (resolvedTargetSeat == null) {
                    params.put(ActionCodec.PARAM_GIVE_UP, true);
                } else {
                    params.put(ActionCodec.PARAM_TARGET_SEAT_NO, resolvedTargetSeat);
                }
            }
            case "WITCH_PASS" -> {
                // 无需药：ActionCodec 明确拒绝携带目标，因此这里必须是空参数
            }
            case "SKIP" -> params.put(ActionCodec.PARAM_REASON, "VOLUNTARY");
            default -> {
                // 未登记的领域动作不会被提交：G7 已经用同一份矩阵拒绝过它
            }
        }
        return Map.copyOf(params);
    }

    /** 工具名 ↔ 动作类型的默认映射（tool-gateway.md §2 的"映射的领域 Action"列）。 */
    public static String defaultActionTypeOf(String tool) {
        return switch (tool == null ? "" : tool) {
            case SPEAK -> "SPEAK";
            case VOTE -> "VOTE";
            case KILL -> "WOLF_KILL";
            default -> null;
        };
    }
}
