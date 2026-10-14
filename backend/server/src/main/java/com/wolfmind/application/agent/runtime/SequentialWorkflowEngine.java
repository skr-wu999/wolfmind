package com.wolfmind.application.agent.runtime;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 顺序工作流引擎：按 {@link WorkflowEngine.WorkflowNode} 的声明顺序执行节点。
 *
 * <p>它是显式回退与离线测试实现，生产默认使用 LangGraph4j。
 * 与 LangGraph4j 版本的区别只在"谁负责调度"，节点语义完全一致。
 *
 * <p><b>两条调度规则（本类最容易被写错的地方）</b>：
 * <ol>
 *   <li>节点函数返回的布尔值表示"本次执行是否产出了它该产出的东西"
 *       （例如 {@code submit} 返回"提交是否成功"），**不决定是否继续往下走**。
 *       因此 {@code submit} 失败后仍会执行 {@code fallback}——否则整局不会有任何动作被提交。</li>
 *   <li>条件节点由状态门控，而不是由前一个节点的返回值门控：
 *       {@code repair} 只在"校验失败且预算允许且尚未修复过"时执行一次；
 *       {@code rewrite} 只在"已给出提案但防泄漏命中且尚未重写过"时执行一次；
 *       {@code fallback} 在已提交成功后跳过。</li>
 * </ol>
 */
public final class SequentialWorkflowEngine implements WorkflowEngine {

    private static final List<WorkflowNode> ORDER = List.of(
            WorkflowNode.PERCEIVE,
            WorkflowNode.RECALL,
            WorkflowNode.SUMMARIZE,
            WorkflowNode.ANALYZE,
            WorkflowNode.STRATEGIZE,
            WorkflowNode.DECIDE,
            WorkflowNode.EXPRESS,
            WorkflowNode.VALIDATE,
            WorkflowNode.REPAIR,
            WorkflowNode.LEAK_CHECK,
            WorkflowNode.REWRITE,
            WorkflowNode.SUBMIT,
            WorkflowNode.FALLBACK,
            WorkflowNode.FINALIZE);

    private final Map<WorkflowNode, NodeHandler> handlers;
    private final List<WorkflowNode> visited = new ArrayList<>();

    public SequentialWorkflowEngine(Map<WorkflowNode, NodeHandler> handlers) {
        this.handlers = Map.copyOf(handlers);
    }

    @Override
    public AgentWorkflow.FlowContext run(AgentWorkflow.FlowContext state) {
        visited.clear();
        Set<WorkflowNode> done = EnumSet.noneOf(WorkflowNode.class);
        for (WorkflowNode node : ORDER) {
            if (!shouldRun(node, state, done)) {
                continue;
            }
            NodeHandler handler = handlers.get(node);
            if (handler == null) {
                continue;
            }
            visited.add(node);
            done.add(node);
            handler.apply(state);
        }
        return state;
    }

    /**
     * 该节点本轮是否应该执行。
     *
     * <p>{@code done} 让"最多执行一次"的节点（repair / rewrite）天然幂等：即使状态在之后
     * 又变得"看起来需要修复"，也不会重复调用模型，从而守住
     * {@code agent.maxLlmCallsPerTurn} 与 {@code agent.maxRepairRounds} 两条预算约束。
     */
    private boolean shouldRun(WorkflowNode node, AgentWorkflow.FlowContext state, Set<WorkflowNode> done) {
        return switch (node) {
            case EXPRESS -> state.twoStageSpeech() && state.speechPlan() != null;
            case REPAIR -> !done.contains(WorkflowNode.REPAIR)
                    && state.rawLlmOutput() != null
                    && state.proposal() == null
                    && state.llmCalls() < state.request().budget().maxLlmCalls();
            case REWRITE -> !done.contains(WorkflowNode.REWRITE)
                    && state.proposal() != null
                    && state.rejectionReason() != null;
            case FALLBACK -> !state.submitted();
            case LEAK_CHECK -> state.proposal() != null;
            case SUBMIT -> state.proposal() != null && !state.submitted();
            default -> true;
        };
    }

    @Override
    public List<WorkflowNode> visitedNodes() {
        return List.copyOf(visited);
    }
}
