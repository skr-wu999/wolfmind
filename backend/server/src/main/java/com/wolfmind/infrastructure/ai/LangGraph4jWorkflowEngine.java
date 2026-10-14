package com.wolfmind.infrastructure.ai;

import com.wolfmind.application.agent.runtime.AgentWorkflow;
import com.wolfmind.application.agent.runtime.WorkflowEngine;
import org.bsc.langgraph4j.CompiledGraph;
import org.bsc.langgraph4j.GraphStateException;
import org.bsc.langgraph4j.StateGraph;
import org.bsc.langgraph4j.action.AsyncEdgeAction;
import org.bsc.langgraph4j.state.AgentState;
import org.bsc.langgraph4j.state.Channels;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;

import static org.bsc.langgraph4j.StateGraph.END;
import static org.bsc.langgraph4j.StateGraph.START;

/**
 * LangGraph4j 驱动的工作流引擎（agent-runtime.md §8、DESIGN.md §9、AGENTS.md §3 基线）。
 *
 * <p>它与 {@link com.wolfmind.application.agent.runtime.SequentialWorkflowEngine} **共用同一批节点
 * 处理器**（{@link AgentWorkflow#nodeHandlers()}），区别只在"谁负责调度"：这里把
 * strategy-layer.md §6 的图真正建成 {@link StateGraph}，节点路径与条件边是图的结构。
 * 两条路径的行为必须一致，因此 {@code LangGraph4jWorkflowEngineTest} 用同一份输入分别驱动两者，
 * 断言提交结果、兜底标记与模型调用次数逐项相同。
 *
 * <p>图与文档的对应：
 * <pre>
 *   START → perceive → recall → summarize → analyze → strategize → decide → [B: express] → validate
 *         → (有提案) leakCheck → (无违规) submit → fallback(幂等) → finalize → END
 *                            ↘ (违规) rewrite ↗
 *         → (无提案) fallback ↗
 * </pre>
 *
 * <p>两个实现要点（都是踩过的坑，写在这里避免后来者重犯）：
 * <ol>
 *   <li><b>每个图节点必须绑定自己的处理器</b>。所有节点共享一个处理器、再靠"已访问节点"反推
 *       当前节点是错的：LangGraph4j 会把所有节点路由到同一个 lambda，反推必然错位
 *       （例如 {@code leakCheck} 与 {@code submit} 都跑到 {@code leakCheck}）。
 *       这里为每个节点建立独立的 {@link NodeAction}，节点身份在构造期固定。</li>
 *   <li><b>图状态里不能放 {@link AgentWorkflow.FlowContext}</b>。LangGraph4j 在节点之间序列化状态，
 *       而 FlowContext 持有策略层/网关/LLM 端口，必然抛 {@code NotSerializableException}。
 *       因此状态里只放可序列化的 {@code runId} 字符串与节点名列表，真正的上下文放在进程内引用表里。</li>
 * </ol>
 */
public final class LangGraph4jWorkflowEngine implements WorkflowEngine {

    /**
     * 运行期上下文引用表：{@code runId → FlowContext}。
     *
     * <p>它不是缓存而是"图的运行时环境"：一次 run 内所有节点都从这里取同一个可变上下文。
     * 容量上限只是异常路径（图抛错未走到 finally）的兜底，正常路径在 {@code finally} 中移除。
     */
    private static final int REFERENCE_LIMIT = 64;
    private static final Map<String, AgentWorkflow.FlowContext> REFERENCES =
            Collections.synchronizedMap(new LinkedHashMap<>() {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, AgentWorkflow.FlowContext> eldest) {
                    return size() > REFERENCE_LIMIT;
                }
            });
    private static final AtomicLong RUN_IDS = new AtomicLong();

    /** 图状态：只含可序列化值（runId 字符串 + 已走过的节点名列表）。 */
    public static final class TurnState extends AgentState {

        /** 本回合在 {@link #REFERENCES} 中的键。 */
        public static final String RUN_ID = "runId";
        /** 已走过的节点名，进入顺序追加。 */
        public static final String VISITED = "visited";

        public TurnState(Map<String, Object> initData) {
            super(initData);
        }

        public String runId() {
            return this.<String>value(RUN_ID)
                    .orElseThrow(() -> new IllegalStateException("图状态缺少 runId"));
        }

        /** 已走过的节点名；供 Trace 与断言使用。 */
        @SuppressWarnings("unchecked")
        public List<String> visited() {
            Object raw = data().get(VISITED);
            return raw instanceof List<?> list ? (List<String>) list : List.of();
        }
    }

    private final Map<WorkflowNode, NodeHandler> handlers;
    private final List<WorkflowNode> visited = new ArrayList<>();
    private CompiledGraph<TurnState> compiled;

    public LangGraph4jWorkflowEngine(Map<WorkflowNode, NodeHandler> handlers) {
        this.handlers = Map.copyOf(handlers);
    }

    @Override
    public AgentWorkflow.FlowContext run(AgentWorkflow.FlowContext state) {
        visited.clear();
        String runId = "agent-run-" + RUN_IDS.incrementAndGet();
        REFERENCES.put(runId, state);
        try {
            TurnState result = graph()
                    .invoke(Map.of(TurnState.RUN_ID, runId))
                    .orElseThrow(() -> new IllegalStateException("图执行未返回状态"));
            visited.addAll(result.visited().stream()
                    .map(LangGraph4jWorkflowEngine::toNode)
                    .filter(Objects::nonNull)
                    .toList());
            // 各节点就地修改了同一个 FlowContext，因此返回原引用即可
            return state;
        } catch (GraphStateException exception) {
            throw new IllegalStateException(
                    "LangGraph4j 工作流构建失败：" + exception.getMessage(), exception);
        } finally {
            REFERENCES.remove(runId);
        }
    }

    @Override
    public List<WorkflowNode> visitedNodes() {
        return List.copyOf(visited);
    }

    /** Validate the graph and required handlers without executing a turn. */
    @Override
    public void validate() {
        for (WorkflowNode node : WorkflowNode.values()) {
            if (!handlers.containsKey(node)) throw new IllegalStateException("Missing workflow handler: " + node.nodeName());
        }
        try {
            graph();
        } catch (GraphStateException failure) {
            throw new IllegalStateException("LangGraph4j graph validation failed: " + failure.getMessage(), failure);
        }
    }

    /** 图是否可构建；供装配层启动自检与测试断言。 */
    public boolean graphBuilds() {
        try {
            return graph() != null;
        } catch (GraphStateException exception) {
            return false;
        }
    }

    /** 图构建失败的原始原因；{@link #graphBuilds()} 只返回布尔值，排查时需要这个。 */
    public String graphBuildFailure() {
        try {
            graph();
            return null;
        } catch (GraphStateException exception) {
            return exception.getClass().getSimpleName() + ": " + exception.getMessage();
        }
    }

    /** 已注册的图节点数；供装配自检（应等于节点处理器表的规模）。 */
    public int registeredNodeCount() {
        int count = 0;
        for (WorkflowNode node : WorkflowNode.values()) {
            if (handlers.containsKey(node)) {
                count++;
            }
        }
        return count;
    }

    // ------------------------------------------------------------------
    // 建图
    // ------------------------------------------------------------------

    private CompiledGraph<TurnState> graph() throws GraphStateException {
        if (compiled != null) {
            return compiled;
        }
        Map<String, org.bsc.langgraph4j.state.Channel<?>> channels = Map.of(
                // 两个 channel 的默认值都必须非 null：LangGraph4j 用 Collectors.toMap 构造初始状态，
                // 而 toMap 拒绝 null 值——它会在 invoke 时报 NPE（不是建图期），极难定位。
                TurnState.RUN_ID, Channels.base(() -> ""),
                TurnState.VISITED, Channels.appender(ArrayList::new));

        StateGraph<TurnState> graph = new StateGraph<>(channels, TurnState::new);
        for (WorkflowNode node : WorkflowNode.values()) {
            NodeHandler handler = handlers.get(node);
            if (handler != null) {
                graph.addNode(node.nodeName(), new NodeAction(node, handler));
            }
        }

        String perceive = name(WorkflowNode.PERCEIVE);
        graph.addEdge(START, perceive);
        graph.addEdge(perceive, name(WorkflowNode.RECALL));
        graph.addEdge(name(WorkflowNode.RECALL), name(WorkflowNode.SUMMARIZE));
        graph.addEdge(name(WorkflowNode.SUMMARIZE), name(WorkflowNode.ANALYZE));
        graph.addEdge(name(WorkflowNode.ANALYZE), name(WorkflowNode.STRATEGIZE));
        graph.addEdge(name(WorkflowNode.STRATEGIZE), name(WorkflowNode.DECIDE));
        graph.addConditionalEdges(name(WorkflowNode.DECIDE), edge(state -> {
            var flow = flow(state);
            return flow.twoStageSpeech() && flow.speechPlan() != null
                    ? name(WorkflowNode.EXPRESS) : name(WorkflowNode.VALIDATE);
        }), Map.of(name(WorkflowNode.EXPRESS), name(WorkflowNode.EXPRESS),
                name(WorkflowNode.VALIDATE), name(WorkflowNode.VALIDATE)));
        graph.addEdge(name(WorkflowNode.EXPRESS), name(WorkflowNode.VALIDATE));

        // validate → 有提案则查泄漏；校验失败且尚未修复过则修复；两者都不是则兜底。
        // 必须先判"是否已修复过"再回流 REPAIR，否则校验持续失败时会形成 REPAIR ⇄ VALIDATE 死循环
        // （LangGraph4j 的迭代上限会直接抛异常，而不是优雅降级）。
        graph.addConditionalEdges(name(WorkflowNode.VALIDATE),
                edge(state -> {
                    AgentWorkflow.FlowContext flow = flow(state);
                    if (flow.proposal() != null) {
                        return name(WorkflowNode.LEAK_CHECK);
                    }
                    boolean repaired = state.visited().contains(name(WorkflowNode.REPAIR));
                    if (!repaired && flow.rawLlmOutput() != null
                            && flow.llmCalls() < flow.request().budget().maxLlmCalls()) {
                        return name(WorkflowNode.REPAIR);
                    }
                    return name(WorkflowNode.FALLBACK);
                }),
                Map.of(name(WorkflowNode.LEAK_CHECK), name(WorkflowNode.LEAK_CHECK),
                        name(WorkflowNode.REPAIR), name(WorkflowNode.REPAIR),
                        name(WorkflowNode.FALLBACK), name(WorkflowNode.FALLBACK)));

        // repair 之后重新校验：重新校验成功会走 leakCheck，失败则因为"已修复过"而落到兜底
        graph.addEdge(name(WorkflowNode.REPAIR), name(WorkflowNode.VALIDATE));

        // leakCheck → 违规则重写，否则提交
        graph.addConditionalEdges(name(WorkflowNode.LEAK_CHECK),
                edge(state -> {
                    AgentWorkflow.FlowContext flow = flow(state);
                    return flow.proposal() != null && flow.rejectionReason() != null
                            ? name(WorkflowNode.REWRITE)
                            : name(WorkflowNode.SUBMIT);
                }),
                Map.of(name(WorkflowNode.REWRITE), name(WorkflowNode.REWRITE),
                        name(WorkflowNode.SUBMIT), name(WorkflowNode.SUBMIT)));

        graph.addEdge(name(WorkflowNode.REWRITE), name(WorkflowNode.SUBMIT));
        // SUBMIT 无条件进入 FALLBACK：不论提交成功还是被网关拒绝都要经过兜底节点，
        // 由该节点自行判断"已提交则原样返回"（幂等）。若在这里按结果分流，被拒绝时会绕过兜底，
        // 导致整局没有任何动作被提交——这正是顺序引擎早期版本的缺陷，两条路径必须一致。
        graph.addEdge(name(WorkflowNode.SUBMIT), name(WorkflowNode.FALLBACK));
        graph.addEdge(name(WorkflowNode.FALLBACK), name(WorkflowNode.FINALIZE));
        graph.addEdge(name(WorkflowNode.FINALIZE), END);

        compiled = graph.compile();
        return compiled;
    }

    /**
     * 单节点动作：**在构造期绑定**自己的节点与处理器。
     *
     * <p>这是本类的关键设计。早期版本让所有节点共享一个动作、再靠"已访问节点集合"反推当前节点，
     * 结果多个节点解析到同一个处理器，图的行为与顺序引擎不一致。把节点身份固定在实例上之后，
     * 每个图节点只可能执行自己的处理器。
     */
    private final class NodeAction implements org.bsc.langgraph4j.action.AsyncNodeAction<TurnState> {

        private final WorkflowNode node;
        private final NodeHandler handler;

        NodeAction(WorkflowNode node, NodeHandler handler) {
            this.node = node;
            this.handler = handler;
        }

        @Override
        public CompletableFuture<Map<String, Object>> apply(TurnState state) {
            // 处理器就地修改同一个 FlowContext；这里只把"走过的节点"回报给图。
            handler.apply(flow(state));
            return CompletableFuture.completedFuture(
                    Map.of(TurnState.VISITED, List.of(node.nodeName())));
        }
    }

    private static String name(WorkflowNode node) {
        return node.nodeName();
    }

    /** 取回当前回合的上下文；引用缺失说明生命周期被破坏，必须显式失败而不是静默继续。 */
    private static AgentWorkflow.FlowContext flow(TurnState state) {
        AgentWorkflow.FlowContext flow = REFERENCES.get(state.runId());
        if (flow == null) {
            throw new IllegalStateException("图状态引用了已释放的运行上下文：" + state.runId());
        }
        return flow;
    }

    private static AsyncEdgeAction<TurnState> edge(Function<TurnState, String> route) {
        return AsyncEdgeAction.edge_async(route::apply);
    }

    static WorkflowNode toNode(String nodeName) {
        for (WorkflowNode node : WorkflowNode.values()) {
            if (node.nodeName().equals(nodeName)) {
                return node;
            }
        }
        return null;
    }
}
