package com.wolfmind.application.agent.model;

import java.util.List;
import java.util.Map;

/**
 * 策略层的最终提案（strategy-layer.md §10）。
 *
 * <p>它是"意图"，不是"状态变更指令"：提案可能越权，此时工具网关会给出结构化拒绝，
 * 对局状态不受影响（C-5）。字段与 strategy-layer.md §7.1 的输出契约一一对应。
 *
 * @param actionType   动作类型，必须在 {@code availableTools} 对应的动作集合内
 * @param targetId     目标玩家 id；不需要目标或选择放弃时为 {@code null}
 * @param targetSeatNo 目标座位号，便于 Trace 与调试
 * @param speech       发言文本；不需要发言的阶段可为空
 * @param confidence   置信度 0..1
 * @param reasonCode   预定义理由码，用于复盘与统计
 * @param notes        模型给出的补充说明；只进 Trace，不进对局事件
 */
public record ActionProposal(
        String actionType,
        String targetId,
        Integer targetSeatNo,
        String speech,
        double confidence,
        String reasonCode,
        String notes) {

    public ActionProposal {
        if (actionType == null || actionType.isBlank()) {
            throw new IllegalArgumentException("actionType 不能为空");
        }
        if (confidence < 0 || confidence > 1) {
            throw new IllegalArgumentException("confidence 必须在 0..1 之间，实际为 " + confidence);
        }
    }

    public static ActionProposal of(String actionType, String targetId, Integer targetSeatNo) {
        return new ActionProposal(actionType, targetId, targetSeatNo, null, 0.5, "UNSPECIFIED", null);
    }

    /** 跳过：兜底链条的最后一步（agent-runtime.md §10）。 */
    public static ActionProposal skip(String reasonCode) {
        return new ActionProposal("SKIP", null, null, null, 0.0, reasonCode, null);
    }

    public boolean hasSpeech() {
        return speech != null && !speech.isBlank();
    }

    /** 把提案映射成工具参数（tool-gateway.md §2 的工具参数表）。 */
    public Map<String, Object> toToolArguments() {
        Map<String, Object> args = new java.util.LinkedHashMap<>();
        switch (actionType) {
            case "SPEAK" -> {
                if (hasSpeech()) {
                    args.put("content", speech);
                } else {
                    args.put("skip", true);
                    args.put("reason", "VOLUNTARY");
                }
            }
            case "VOTE" -> {
                if (targetSeatNo == null) {
                    args.put("abstain", true);
                } else {
                    args.put("targetId", targetId);
                }
            }
            case "WOLF_KILL" -> {
                if (targetSeatNo == null) {
                    args.put("empty", true);
                } else {
                    args.put("targetId", targetId);
                }
            }
            case "SEER_INSPECT" -> args.put("targetId", targetId);
            case "WITCH_SAVE", "WITCH_POISON" -> args.put("targetId", targetId);
            case "HUNTER_SHOOT" -> {
                if (targetSeatNo == null) {
                    args.put("giveUp", true);
                } else {
                    args.put("targetId", targetId);
                }
            }
            case "SKIP" -> args.put("reason", "VOLUNTARY");
            default -> {
                // 未知动作类型由工具网关的 G1/G2 拒绝，这里不伪造参数
            }
        }
        return Map.copyOf(args);
    }

    /** 便于 Trace 与日志：只记结构，不记发言全文（tool-gateway.md §7）。 */
    public List<String> describeDigest() {
        return List.of(
                "action=" + actionType,
                "target=" + (targetSeatNo == null ? "-" : targetSeatNo),
                "speechLen=" + (speech == null ? 0 : speech.length()),
                "confidence=" + confidence,
                "reason=" + reasonCode);
    }
}
