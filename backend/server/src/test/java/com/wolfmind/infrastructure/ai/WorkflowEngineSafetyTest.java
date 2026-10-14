package com.wolfmind.infrastructure.ai;

import com.wolfmind.application.agent.AgentRuntime;
import com.wolfmind.application.agent.fake.*;
import com.wolfmind.application.agent.llm.ScriptedLlmClient;
import com.wolfmind.application.agent.model.*;
import com.wolfmind.application.agent.runtime.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class WorkflowEngineSafetyTest {
    static final long NOW=1_730_000_000_000L;
    static Map<WorkflowEngine.WorkflowNode,WorkflowEngine.NodeHandler> handlers() {
        Map<WorkflowEngine.WorkflowNode,WorkflowEngine.NodeHandler> nodes=new EnumMap<>(WorkflowEngine.WorkflowNode.class);
        for (var node:WorkflowEngine.WorkflowNode.values()) nodes.put(node,state -> { throw new AssertionError("Startup validation executed a node"); });
        return nodes;
    }
    @Test void defaultGraphValidationDoesNotExecuteAnyNodes() {
        var selected=new WorkflowEngineSelection(null);
        var graph=selected.create(handlers());
        assertInstanceOf(LangGraph4jWorkflowEngine.class,graph);
        assertDoesNotThrow(graph::validate);
        assertEquals(List.of(),graph.visitedNodes());
    }
    @ParameterizedTest @EnumSource(WorkflowEngine.WorkflowNode.class)
    void missingHandlersFailGraphValidation(WorkflowEngine.WorkflowNode missing) {
        var nodes=handlers(); nodes.remove(missing);
        assertThrows(IllegalStateException.class,new WorkflowEngineSelection(null).create(nodes)::validate);
    }
    @Test void selectingSequentialDoesNotBuildLangGraph() {
        var sequential=new WorkflowEngineSelection("SEQUENTIAL").create(Map.of());
        assertInstanceOf(SequentialWorkflowEngine.class,sequential);
        assertDoesNotThrow(sequential::validate);
    }
    private static TurnRequest request() {
        var config=new AgentConfig("cfg","test","fake",.6,.2,AgentConfig.StrategyProfile.BALANCED,"中性",
                AgentConfig.WorkflowMode.A,60,.5,8,3,"default",TurnBudget.DEFAULT);
        return new TurnRequest("g_safety","p_1",1,"VILLAGER","VILLAGE","DAY_DISCUSS",1,0,10,NOW+30_000,
                FakeAgentView.of("VILLAGER"),List.of("SpeakTool"),TurnBudget.DEFAULT,config);
    }
    private static AgentWorkflow workflow(ScriptedLlmClient model,FakeToolGateway gateway,
                                          AgentWorkflow.WorkflowEngineFactory factory) {
        var strategy=new FakeStrategy().withCandidates(FakeStrategy.candidate("SPEAK",3,.9));
        var time=new FixedTime(NOW);
        return new AgentWorkflow(strategy,gateway,new InMemoryMemory(),new InMemoryAgentTrace(),new PromptBuilder(),
                new LlmInvoker(model),new BudgetGuard(time),time,factory);
    }
    private static AgentWorkflow.WorkflowEngineFactory failingFactory(WorkflowEngineSelection.Mode mode,
            WorkflowEngine.WorkflowNode failing,AtomicInteger creations) {
        return nodes -> {
            creations.incrementAndGet();
            Map<WorkflowEngine.WorkflowNode,WorkflowEngine.NodeHandler> wrapped=new EnumMap<>(nodes);
            wrapped.put(failing,state -> {
                if (failing==WorkflowEngine.WorkflowNode.DECIDE) nodes.get(failing).apply(state);
                throw new IllegalStateException("Injected execution failure");
            });
            return new WorkflowEngineSelection(mode.name()).create(wrapped);
        };
    }
    @ParameterizedTest @EnumSource(WorkflowEngineSelection.Mode.class)
    void failureAfterModelCallDoesNotRestartWorkflow(WorkflowEngineSelection.Mode mode) {
        var model=new ScriptedLlmClient(); model.enqueueText("ACTION:SPEAK:3");
        var gateway=new FakeToolGateway(); var creations=new AtomicInteger();
        var workflow=workflow(model,gateway,failingFactory(mode,WorkflowEngine.WorkflowNode.DECIDE,creations));
        assertThrows(RuntimeException.class,() -> workflow.run(request(),new AgentRuntime.AgentHandle("g_safety","p_1",1,"cfg")));
        assertEquals(1,model.callCount()); assertEquals(1,creations.get());
        assertTrue(gateway.submittedTools().isEmpty());
    }
    @ParameterizedTest @EnumSource(WorkflowEngineSelection.Mode.class)
    void failureAfterAcceptedActionPreservesSuccessWithoutResubmitting(WorkflowEngineSelection.Mode mode) {
        var model=new ScriptedLlmClient(); model.enqueueText("ACTION:SPEAK:3");
        var gateway=new FakeToolGateway(); var creations=new AtomicInteger();
        var workflow=workflow(model,gateway,failingFactory(mode,WorkflowEngine.WorkflowNode.FINALIZE,creations));
        var state=workflow.run(request(),new AgentRuntime.AgentHandle("g_safety","p_1",1,"cfg"));
        assertTrue(state.submitted()); assertEquals(1,model.callCount()); assertEquals(1,creations.get());
        assertEquals(1,gateway.submittedTools().size());
    }
}
