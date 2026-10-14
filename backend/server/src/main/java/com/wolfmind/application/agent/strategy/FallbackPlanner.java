package com.wolfmind.application.agent.strategy;

import com.wolfmind.application.agent.model.ActionProposal;
import com.wolfmind.application.agent.model.AgentView;
import com.wolfmind.application.agent.model.Candidate;

import java.util.List;

/**
 * 规则兜底（strategy-layer.md §9）：不依赖 LLM 的规则决策器。
 *
 * <pre>
 * 1. 取候选集中 DecisionPolicy 打分最高者
 * 2. 若候选集为空（无合法动作）→ SKIP
 * 3. 发言使用结构化模板
 * 4. 标记 TurnResult.status = FALLBACK、fallbackUsed = true（由 Runtime 完成）
 * </pre>
 *
 * <p>存在的意义是让"9 个 AI 自动完成一局"在 LLM 完全不可用时也能达成
 * （agent-runtime.md §10 最后一行），同时给实验室一个"纯规则基线"对照组。
 * 因此这里**不允许**出现任何需要模型参与的东西：模板、打分、排序全是确定性的。
 */
public final class FallbackPlanner {

    /** 只表达立场，不把内部阶段名当作公开发言。 */
    public static String templateSpeech(int round, String phase, Integer topSuspectSeat) {
        boolean lastWords = StrategyCodes.LAST_WORDS.equals(StrategyCodes.normalize(phase));
        if (topSuspectSeat == null) {
            return lastWords ? "我的发言就到这里，希望大家结合公开信息继续判断。"
                    : "我先听大家的判断，再给出我的结论。";
        }
        return lastWords ? "最后提醒大家关注 " + topSuspectSeat + " 号，结合公开发言和投票再判断。"
                : "我的判断是先关注 " + topSuspectSeat + " 号，想听听他的理由。";
    }

    /**
     * 安全模板发言：重写一次仍违规时的最后手段（§8.2 末段）。
     *
     * <p>只包含阶段相关的通用表态，结构上不可能命中任何私有事实指纹——
     * 这是"对局不因表达问题卡住"的保证，而不是另一种表达风格。
     */
    public String safeSpeech(AgentView view) {
        String phase = view == null ? "" : view.phase();
        return templateSpeech(view == null ? 1 : view.round(), phase, null);
    }

    /**
     * 产出兜底提案。
     *
     * @param candidates      已打分的候选集；为空或 {@code null} 时产出 {@code SKIP}
     * @param topSuspectSeat  当前怀疑度最高的存活座位，用于发言模板；可为 {@code null}
     */
    public ActionProposal plan(
            List<Candidate> candidates, AgentView view, String role, String phase, Integer topSuspectSeat) {
        if (candidates == null || candidates.isEmpty()) {
            return ActionProposal.skip(StrategyCodes.REASON_FALLBACK_EMPTY_CANDIDATES);
        }
        Candidate top = candidates.stream().min(DecisionPolicy.order(view)).orElse(null);
        if (top == null) {
            return ActionProposal.skip(StrategyCodes.REASON_FALLBACK_EMPTY_CANDIDATES);
        }
        String normalizedPhase = StrategyCodes.normalize(phase);
        String speech = null;
        if (StrategyCodes.SPEECH_PHASES.contains(normalizedPhase)) {
            Integer focus = topSuspectSeat != null ? topSuspectSeat : top.targetSeatNo();
            speech = templateSpeech(view == null ? 1 : view.round(), phase == null ? "" : phase, focus);
        }
        return new ActionProposal(
                top.actionType(),
                top.targetId(),
                top.targetSeatNo(),
                speech,
                clamp01(top.score()),
                StrategyCodes.REASON_FALLBACK_TOP1,
                "规则兜底：候选集最高分（score="
                        + String.format(java.util.Locale.ROOT, "%.2f", top.score()) + "）");
    }

    private static double clamp01(double value) {
        if (Double.isNaN(value)) {
            return 0.0;
        }
        return Math.max(0.0, Math.min(1.0, value));
    }
}
