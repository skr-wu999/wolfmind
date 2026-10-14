package com.wolfmind.application.agent.strategy;

import com.wolfmind.application.agent.model.ActionProposal;
import com.wolfmind.application.agent.model.AgentConfig;
import com.wolfmind.application.agent.model.AgentView;
import com.wolfmind.application.agent.model.Candidate;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 输出校验（strategy-layer.md §8.1）。
 *
 * <p>校验的五类失败一律转成 {@code repair}，只有 {@code confidence} 与 {@code reasonCode}
 * 非法时用默认值而不修复——理由码是统计维度而不是约束，为它浪费一次 LLM 调用不划算。
 *
 * <table>
 *   <caption>校验与失败处理</caption>
 *   <tr><th>校验</th><th>失败处理</th></tr>
 *   <tr><td>JSON 可解析</td><td>{@code BAD_JSON}</td></tr>
 *   <tr><td>actionType 在允许集合内</td><td>{@code ACTION_NOT_ALLOWED}</td></tr>
 *   <tr><td>动作属于本角色本阶段的候选集</td><td>{@code PHASE_ROLE_MISMATCH}</td></tr>
 *   <tr><td>targetId 在候选集内</td><td>{@code TARGET_NOT_IN_CANDIDATES}</td></tr>
 *   <tr><td>targetId 不是自己、不是死者、视图里存在</td><td>{@code TARGET_INVALID}</td></tr>
 *   <tr><td>speech 长度与空值</td><td>{@code SPEECH_LENGTH}</td></tr>
 *   <tr><td>confidence 与 reasonCode 合法</td><td>使用默认值，不修复</td></tr>
 * </table>
 *
 * <p>目标身份以**候选集**为准：校验通过后写进提案的 {@code targetId} / {@code targetSeatNo}
 * 来自命中的那条候选，而不是模型给的字符串。模型可以写错 id 的形态，但改不了"选的是谁"。
 */
public final class OutputValidator {

    /** 从模型输出里解析座位号的兜底方式：允许 {@code "5"}、{@code "5号"}、{@code "#5"} 这类写法。 */
    private static final Pattern SEAT_IN_TEXT = Pattern.compile("(\\d{1,2})");

    /** 表示"没有目标"的常见写法，按缺省处理而不是判非法。 */
    private static final Set<String> ABSENT_TOKENS = Set.of("null", "none", "n/a", "na", "-", "");

    /** notes 只进 Trace，截断避免模型用它塞进整篇小作文。 */
    private static final int NOTES_MAX_LENGTH = 500;

    /**
     * 校验模型输出。
     *
     * <p>{@code config} 目前不参与判定，保留它是因为 {@code StrategyPort.validate} 的签名里
     * 有这一项（候选上限、温度等配置属于调用方；校验若哪天需要按配置放宽，入口已经在这里）。
     * 显式说明这一点，避免读者以为存在"按配置改变校验强度"的隐藏分支。
     */
    public StrategyPort.ValidationResult validate(
            String raw,
            List<Candidate> candidates,
            AgentView view,
            AgentConfig config,
            List<String> allowedActions) {
        List<Candidate> safeCandidates = candidates == null ? List.of() : candidates;
        if (view == null || view.me() == null) {
            return StrategyPort.ValidationResult.invalid(
                    StrategyCodes.FAIL_ACTION_NOT_ALLOWED, "缺少本人视角的视图，无法提交任何动作");
        }
        Map<String, Object> object = JsonLite.tryReadObject(raw);
        if (object == null) {
            return StrategyPort.ValidationResult.invalid(
                    StrategyCodes.FAIL_BAD_JSON, "输出不是可解析的 JSON 对象");
        }
        String actionType = StrategyCodes.normalize(JsonLite.string(object, "actionType"));
        if (actionType.isEmpty()) {
            return StrategyPort.ValidationResult.invalid(
                    StrategyCodes.FAIL_ACTION_NOT_ALLOWED, "缺少 actionType 字段");
        }
        Set<String> allowed = new LinkedHashSet<>();
        if (allowedActions != null) {
            for (String action : allowedActions) {
                allowed.add(StrategyCodes.normalize(action));
            }
        }
        if (!allowed.contains(actionType)) {
            return StrategyPort.ValidationResult.invalid(
                    StrategyCodes.FAIL_ACTION_NOT_ALLOWED,
                    "actionType " + actionType + " 不在本角色本阶段的允许集合 " + allowed + " 内");
        }
        List<Candidate> sameType = new ArrayList<>();
        for (Candidate candidate : safeCandidates) {
            if (actionType.equals(StrategyCodes.normalize(candidate.actionType()))) {
                sameType.add(candidate);
            }
        }
        if (sameType.isEmpty()) {
            return StrategyPort.ValidationResult.invalid(
                    StrategyCodes.FAIL_PHASE_ROLE_MISMATCH,
                    "动作 " + actionType + " 不属于本角色本阶段的候选集（阶段 " + view.phase() + "）");
        }

        String rawTarget = firstNonBlank(
                JsonLite.string(object, "targetId"), JsonLite.string(object, "targetSeatNo"));
        Candidate matched;
        List<Candidate> targeted = sameType.stream().filter(Candidate::hasTarget).toList();
        List<Candidate> untargeted = sameType.stream().filter(candidate -> !candidate.hasTarget()).toList();
        Integer seatNo = resolveSeat(view, rawTarget);
        if (seatNo == null) {
            // 无目标形态（弃权 / 空刀 / 不用药 / 放弃开枪）：候选集里必须存在对应的无目标候选。
            // 同时容忍 api-spec.md §5.3 的标记写法（abstain / empty / giveUp），
            // 因为它表达的是同一件事，为它触发一次 repair 是纯浪费。
            boolean inactionFlag = booleanFlag(object, "abstain")
                    || booleanFlag(object, "empty")
                    || booleanFlag(object, "giveUp");
            if ((isAbsent(rawTarget) || inactionFlag) && !untargeted.isEmpty()) {
                matched = untargeted.get(0);
            } else if (isAbsent(rawTarget)) {
                return StrategyPort.ValidationResult.invalid(
                        StrategyCodes.FAIL_TARGET_NOT_IN_CANDIDATES,
                        "动作 " + actionType + " 需要 targetId，但输出没有给出");
            } else {
                return StrategyPort.ValidationResult.invalid(
                        StrategyCodes.FAIL_TARGET_NOT_IN_CANDIDATES,
                        "无法把 targetId 解析为座位号：" + rawTarget);
            }
        } else {
            if (seatNo == view.me().seatNo()) {
                return StrategyPort.ValidationResult.invalid(
                        StrategyCodes.FAIL_TARGET_INVALID, "目标不能是自己");
            }
            AgentView.SeatView seat = view.seat(seatNo).orElse(null);
            if (seat == null) {
                return StrategyPort.ValidationResult.invalid(
                        StrategyCodes.FAIL_TARGET_INVALID, "座位 " + seatNo + " 不在视图里");
            }
            if (!seat.alive()) {
                return StrategyPort.ValidationResult.invalid(
                        StrategyCodes.FAIL_TARGET_INVALID, "座位 " + seatNo + " 已死亡（INV-9）");
            }
            matched = targeted.stream().filter(candidate -> candidate.targets(seatNo)).findFirst().orElse(null);
            if (matched == null) {
                return StrategyPort.ValidationResult.invalid(
                        StrategyCodes.FAIL_TARGET_NOT_IN_CANDIDATES,
                        "座位 " + seatNo + " 不在本回合候选集内；候选为 "
                                + targeted.stream().map(Candidate::targetSeatNo).toList());
            }
        }

        String speechError = speechError(object, actionType, view);
        if (speechError != null) {
            return StrategyPort.ValidationResult.invalid(StrategyCodes.FAIL_SPEECH_LENGTH, speechError);
        }
        String speech = normalizeSpeech(object);

        // confidence 与 reasonCode 非法只回落默认值：它们是统计维度，不决定动作是否合法
        Double confidence = JsonLite.number(object, "confidence");
        double safeConfidence = confidence == null || confidence < 0 || confidence > 1 ? 0.5 : confidence;
        String reasonCode = StrategyCodes.normalizeReasonCode(JsonLite.string(object, "reasonCode"));
        String notes = normalizeNotes(JsonLite.string(object, "notes"));

        return StrategyPort.ValidationResult.ok(new ActionProposal(
                actionType,
                matched.targetId(),
                matched.targetSeatNo(),
                speech,
                safeConfidence,
                reasonCode,
                notes));
    }

    /** 发言长度与空值校验；返回 {@code null} 表示通过。 */
    private String speechError(Map<String, Object> object, String actionType, AgentView view) {
        String speech = normalizeSpeech(object);
        int maxLength = view.ruleInt("speechMaxLength", 500);
        String rawSpeech = JsonLite.string(object, "speech");
        if (rawSpeech != null && rawSpeech.strip().length() > maxLength) {
            return "发言长度超过上限 " + maxLength;
        }
        boolean requiresSpeech = StrategyCodes.SPEAK.equals(actionType)
                || StrategyCodes.SPEECH_PHASES.contains(StrategyCodes.normalize(view.phase()));
        if (requiresSpeech && (speech == null || speech.isEmpty())) {
            return "阶段 " + view.phase() + " 需要非空发言（1.." + maxLength + " 字符）";
        }
        if (speech != null && speech.length() > maxLength) {
            return "发言长度 " + speech.length() + " 超过上限 " + maxLength;
        }
        return null;
    }

    private String normalizeSpeech(Map<String, Object> object) {
        String speech = JsonLite.string(object, "speech");
        if (speech == null) {
            return null;
        }
        String trimmed = com.wolfmind.application.agent.speech.PublicSpeechText.clean(speech);
        return trimmed.isEmpty() ? null : trimmed;
    }

    private String normalizeNotes(String notes) {
        if (notes == null) {
            return null;
        }
        String trimmed = notes.strip();
        if (trimmed.isEmpty()) {
            return null;
        }
        return trimmed.length() <= NOTES_MAX_LENGTH ? trimmed : trimmed.substring(0, NOTES_MAX_LENGTH);
    }

    /**
     * 解析目标：先按玩家 id 精确匹配（视图里的 id 是不透明字符串），再退化为数字座位号。
     *
     * <p>宽容这一层是因为模型的写法会漂移（{@code "p_5"}、{@code "5"}、{@code "5号"}），
     * 而"选的是谁"由候选集最终确定——宽容解析不会让越界目标变成合法目标。
     */
    private Integer resolveSeat(AgentView view, String raw) {
        if (isAbsent(raw)) {
            return null;
        }
        String text = raw.strip();
        if (view.me() != null && text.equals(view.me().playerId())) {
            return view.me().seatNo();
        }
        for (AgentView.SeatView seat : view.other()) {
            if (text.equals(seat.playerId())) {
                return seat.seatNo();
            }
        }
        Matcher matcher = SEAT_IN_TEXT.matcher(text);
        if (matcher.find()) {
            try {
                return Integer.parseInt(matcher.group(1));
            } catch (NumberFormatException exception) {
                return null;
            }
        }
        return null;
    }

    private boolean isAbsent(String raw) {
        return raw == null || ABSENT_TOKENS.contains(raw.strip().toLowerCase(Locale.ROOT));
    }

    private boolean booleanFlag(Map<String, Object> object, String key) {
        Object value = object.get(key);
        return value instanceof Boolean flag && flag;
    }

    private String firstNonBlank(String first, String second) {
        if (first != null && !first.isBlank()) {
            return first;
        }
        return second;
    }
}
