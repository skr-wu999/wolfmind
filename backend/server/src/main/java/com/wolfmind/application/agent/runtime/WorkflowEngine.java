package com.wolfmind.application.agent.runtime;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * 工作流引擎：按 strategy-layer.md §6 的节点序列驱动一个回合。
 *
 * <p>它是接口而不是直接调用 LangGraph4j，原因有两条：
 * <ol>
 *   <li>测试必须离线：{@link SequentialWorkflowEngine} 让整条链路在无图引擎、无网络时也能跑通，
 *       因此"9 个 AI 完成一局"可以被自动化验证（agent-runtime.md §14 的"兜底"一行）；</li>
 *   <li>LangGraph4j 只承担"图执行"，节点语义属于策略层；换图引擎不应改节点实现。</li>
 * </ol>
 *
 * <p>Runtime 的职责只有四项（agent-runtime.md §8）：初始化状态、驱动执行、中断与超时、收敛结果。
 */
public interface WorkflowEngine {

    /**
     * 执行一个回合。
     *
     * @param state 初始状态；节点就地更新它
     * @return 执行后的状态
     */
    AgentWorkflow.FlowContext run(AgentWorkflow.FlowContext state);

    /** 本次执行实际走过的节点，按顺序；供 Trace 与测试断言节点路径与重试次数。 */
    List<WorkflowNode> visitedNodes();

    /** Validate structure without executing nodes or invoking external services. */
    default void validate() { }

    /**
     * 工作流节点（strategy-layer.md §6 的图）。
     *
     * <p>{@code usesLlm} 标注 {@code decide} / {@code express} / {@code repair} / {@code rewrite} 会调用模型，
     * 且总调用次数受 {@code agent.maxLlmCallsPerTurn} 约束。
     */
    enum WorkflowNode {
        PERCEIVE("perceive", false),
        RECALL("recall", false),
        SUMMARIZE("summarize", false),
        ANALYZE("analyze", false),
        STRATEGIZE("strategize", false),
        DECIDE("decide", true),
        EXPRESS("express", true),
        VALIDATE("validate", false),
        REPAIR("repair", true),
        LEAK_CHECK("leakCheck", false),
        REWRITE("rewrite", true),
        SUBMIT("submit", false),
        FALLBACK("fallback", false),
        FINALIZE("finalize", false);

        private final String nodeName;
        private final boolean usesLlm;

        WorkflowNode(String nodeName, boolean usesLlm) {
            this.nodeName = nodeName;
            this.usesLlm = usesLlm;
        }

        public String nodeName() {
            return nodeName;
        }

        public boolean usesLlm() {
            return usesLlm;
        }
    }

    /** 节点处理函数。返回 {@code false} 表示"本回合到此结束"（不再走后续节点）。 */
    @FunctionalInterface
    interface NodeHandler {
        boolean apply(AgentWorkflow.FlowContext state);
    }

    /** 按节点名索引的处理函数表。 */
    static Map<WorkflowNode, NodeHandler> handlers(
            NodeHandler perceive,
            NodeHandler recall,
            NodeHandler summarize,
            NodeHandler analyze,
            NodeHandler strategize,
            NodeHandler decide,
            NodeHandler validate,
            NodeHandler repair,
            NodeHandler leakCheck,
            NodeHandler rewrite,
            NodeHandler submit,
            NodeHandler fallback,
            NodeHandler finalize) {
        Map<WorkflowNode, NodeHandler> map = new EnumMap<>(WorkflowNode.class);
        map.put(WorkflowNode.PERCEIVE, perceive);
        map.put(WorkflowNode.RECALL, recall);
        map.put(WorkflowNode.SUMMARIZE, summarize);
        map.put(WorkflowNode.ANALYZE, analyze);
        map.put(WorkflowNode.STRATEGIZE, strategize);
        map.put(WorkflowNode.DECIDE, decide);
        map.put(WorkflowNode.EXPRESS, state -> true);
        map.put(WorkflowNode.VALIDATE, validate);
        map.put(WorkflowNode.REPAIR, repair);
        map.put(WorkflowNode.LEAK_CHECK, leakCheck);
        map.put(WorkflowNode.REWRITE, rewrite);
        map.put(WorkflowNode.SUBMIT, submit);
        map.put(WorkflowNode.FALLBACK, fallback);
        map.put(WorkflowNode.FINALIZE, finalize);
        return map;
    }
}
