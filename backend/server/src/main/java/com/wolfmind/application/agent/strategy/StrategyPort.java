package com.wolfmind.application.agent.strategy;

import com.wolfmind.application.agent.model.ActionProposal;
import com.wolfmind.application.agent.model.AgentConfig;
import com.wolfmind.application.agent.model.AgentContext;
import com.wolfmind.application.agent.model.AgentView;
import com.wolfmind.application.agent.model.Candidate;

import java.util.List;

/**
 * 策略层入口：把角色可见视图折叠成"事实 → 打分 → 候选集"，并提供 LLM 输出的校验与防泄漏。
 *
 * <p>它是纯函数式的只读层（strategy-layer.md §2 末句：「除 LLM 调用以外的全部组件都是纯函数或
 * 只读查询，可以直接单测」）。因此：
 * <ul>
 *   <li>实现**不得**访问仓储、事件流、工具网关或任何写路径；</li>
 *   <li>实现**不得**使用随机数：打分平局按 {@link TieBreak} 的稳定哈希取首个（strategy-layer.md §13 末条）；</li>
 *   <li>同一 {@code AgentView} + 同一 {@code AgentConfig} 必须产生同一结果，
 *       这是实验室对比结论可信的前提（§11）。</li>
 * </ul>
 *
 * <p>分工边界（strategy-layer.md §1）：本层决定"能不能做、有哪些合法选择"，
 * LLM 只在候选集里取舍并决定"怎么表达"。
 */
public interface StrategyPort {

    /**
     * 生成候选动作集（{@code CandidateGenerator} + {@code DecisionPolicy}）。
     *
     * @param view   角色可见视图（已脱敏；{@code JUDGE} 事实与他人私有事实不在其中）
     * @param role   本座位角色
     * @param phase  当前阶段
     * @param config 本座位配置（档位决定打分权重）
     * @return 按分数降序的候选集；平局按 {@link TieBreak}。可能为空（无合法动作）
     */
    List<Candidate> candidates(AgentView view, String role, String phase, AgentConfig config);

    /**
     * 该角色该阶段允许提交的动作类型。
     *
     * <p>它必须与工具网关的权限矩阵**同源**（strategy-layer.md §7 约束 1）：
     * 由 {@code ToolGatewayPort.allowedActionTypes(role, phase)} 提供，
     * 本层不得维护第二份手写矩阵。
     */
    List<String> allowedActions(String role, String phase);

    /**
     * 校验 LLM 输出（{@code OutputValidator}，strategy-layer.md §8.1）。
     *
     * @param raw        模型原始输出文本（期望是 JSON）
     * @param candidates 本回合候选集
     * @param view       角色可见视图（用于阶段/角色/长度校验）
     * @param config     本座位配置
     */
    ValidationResult validate(String raw, List<Candidate> candidates, AgentView view, AgentConfig config);

    /**
     * 信息边界校验（{@code LeakGuard}，strategy-layer.md §8.2）。
     *
     * <p>拦截的是"这个 Agent 说出了它自己都不可能知道的事实"：文本中出现视图之外的
     * 座位私有事实或 {@code JUDGE} 层事实即违规。它是**保守的**：只匹配结构化事实指纹，
     * 不做语义审查，宁可漏判也不误杀。
     *
     * @param text 待检查文本（通常是发言内容）
     * @param view 角色可见视图（可见指纹集合的来源）
     */
    LeakCheckResult leakCheck(String text, AgentView view);

    /**
     * 复读检查（strategy-layer.md §8.3）：发言与视图内已有发言高度相似时命中，交给重写。
     *
     * <p>默认不检查，测试替身等不关心表达质量的实现可沿用。
     */
    default EchoCheckResult echoCheck(String text, AgentView view) {
        return EchoCheckResult.clean();
    }

    /**
     * 规则兜底（{@code FallbackPlanner}，strategy-layer.md §9）。
     *
     * <p>不依赖 LLM：取候选集中打分最高者；候选集为空时产出 {@code SKIP}。
     * 它是"9 个 AI 在 LLM 完全不可用时也能跑完一局"的实现（agent-runtime.md §10）。
     */
    ActionProposal fallback(List<Candidate> candidates, AgentView view, String role, String phase);

    /**
     * 装配上下文（{@code ContextAssembler} 的策略部分，agent-runtime.md §6）。
     *
     * @param view       角色可见视图
     * @param candidates 候选集
     * @param config     本座位配置
     * @param memory     已召回的长期记忆文本；无记忆时为空串
     */
    AgentContext assembleContext(
            AgentView view, List<Candidate> candidates, AgentConfig config, String memory);

    /** 校验结果。 */
    record ValidationResult(boolean valid, ActionProposal proposal, String failureCode, String failureReason) {

        public static ValidationResult ok(ActionProposal proposal) {
            return new ValidationResult(true, proposal, null, null);
        }

        public static ValidationResult invalid(String code, String reason) {
            return new ValidationResult(false, null, code, reason);
        }
    }

    /**
     * 防泄漏结果。
     *
     * @param leaked         是否命中不可见事实
     * @param violationKind  违规类别：{@code PRIVATE_FACT} / {@code JUDGE_FACT}
     * @param detail         命中的指纹描述，进 repair prompt 与 Trace
     */
    record LeakCheckResult(boolean leaked, String violationKind, String detail) {

        public static LeakCheckResult clean() {
            return new LeakCheckResult(false, null, null);
        }

        public static LeakCheckResult violation(String kind, String detail) {
            return new LeakCheckResult(true, kind, detail);
        }
    }

    /**
     * 复读检查结果。
     *
     * @param echo       是否命中
     * @param seatNo     最相似发言的座位
     * @param round      最相似发言的轮次
     * @param similarity 相似度 0..1
     * @param self       最相似的是否为本人此前的发言
     */
    record EchoCheckResult(boolean echo, int seatNo, int round, double similarity, boolean self) {

        public static EchoCheckResult clean() {
            return new EchoCheckResult(false, 0, 0, 0.0, false);
        }

        public static EchoCheckResult echo(int seatNo, int round, double similarity, boolean self) {
            return new EchoCheckResult(true, seatNo, round, similarity, self);
        }

        /** 进重写提示词与 Trace；只含公开发言的座位与轮次。 */
        public String detail() {
            return (self ? "你自己" : seatNo + " 号") + "第 " + round + " 轮的发言"
                    + String.format(java.util.Locale.ROOT, "（相似度 %.2f）", similarity);
        }
    }
}
