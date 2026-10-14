package com.wolfmind.infrastructure.ai;

import com.wolfmind.application.agent.AgentRuntime;
import com.wolfmind.application.agent.fake.FakeAgentView;
import com.wolfmind.application.agent.fake.FakeStrategy;
import com.wolfmind.application.agent.fake.FakeToolGateway;
import com.wolfmind.application.agent.fake.FixedTime;
import com.wolfmind.application.agent.fake.InMemoryAgentTrace;
import com.wolfmind.application.agent.fake.InMemoryMemory;
import com.wolfmind.application.agent.llm.LlmClient;
import com.wolfmind.application.agent.llm.ScriptedLlmClient;
import com.wolfmind.application.agent.model.ActionProposal;
import com.wolfmind.application.agent.model.AgentConfig;
import com.wolfmind.application.agent.model.TurnBudget;
import com.wolfmind.application.agent.model.TurnRequest;
import com.wolfmind.application.agent.runtime.AgentWorkflow;
import com.wolfmind.application.agent.runtime.BudgetGuard;
import com.wolfmind.application.agent.runtime.LlmInvoker;
import com.wolfmind.application.agent.runtime.PromptBuilder;
import com.wolfmind.application.agent.runtime.SequentialWorkflowEngine;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 两条调度路径的一致性验证：**LangGraph4j 图引擎与顺序引擎必须行为相同**。
 *
 * <p>它们共用同一批节点处理器（{@link AgentWorkflow#nodeHandlers()}），差别只在调度。
 * 因此用同一份输入分别驱动两条路径，断言"提交结果、兜底标记、模型调用次数、工具调用次数"
 * 逐项相同——否则"换引擎会改变对局行为"，那对可复现性是致命的
 * （strategy-layer.md §11：同一输入必须产生同一结果）。
 *
 * <p>这条测试在实现期抓到过一个真实缺陷：图节点共享同一个处理器、靠"已访问节点"反推当前节点，
 * 导致多个节点执行同一个处理器。所以下面每个用例都同时跑两条路径并对比，而不是只断言图能跑通。
 */
class LangGraph4jWorkflowEngineTest {

    private static final long NOW = 1_730_000_000_000L;

    private static AgentConfig llmConfig() {
        return new AgentConfig(
                "graph-cfg", "图引擎测试", "fake-model", 0.6, 0.2,
                AgentConfig.StrategyProfile.BALANCED, "中性", AgentConfig.WorkflowMode.A,
                60, 0.5, 8, 3, "tpl", TurnBudget.DEFAULT);
    }

    private static TurnRequest request() {
        return new TurnRequest(
                "g_graph", "p_1", 1, "VILLAGER", "VILLAGE", "DAY_DISCUSS", 1, 0,
                10, NOW + 30_000L, FakeAgentView.of("VILLAGER"),
                List.of("SpeakTool"), TurnBudget.DEFAULT, llmConfig());
    }

    /** 用给定的引擎工厂跑一个回合，返回工作流状态。 */
    private static AgentWorkflow.FlowContext runWith(
            AgentWorkflow.WorkflowEngineFactory factory,
            LlmClient llmClient,
            FakeStrategy strategy,
            FakeToolGateway gateway) {

        FixedTime clock = new FixedTime(NOW);
        AgentWorkflow workflow = new AgentWorkflow(
                strategy, gateway, new InMemoryMemory(), new InMemoryAgentTrace(),
                new PromptBuilder(), new LlmInvoker(llmClient), new BudgetGuard(NOW), clock, factory);
        return workflow.run(
                request(),
                new AgentRuntime.AgentHandle("g_graph", "p_1", 1, "graph-cfg"));
    }

    private static FakeStrategy strategyWithCandidate() {
        return new FakeStrategy().withCandidates(FakeStrategy.candidate("SPEAK", 3, 0.9));
    }

    @Test
    @DisplayName("图引擎可构建：空节点表失败、真实节点表可跑完一个回合")
    void graphBuilds() {
        LangGraph4jWorkflowEngine probe = new LangGraph4jWorkflowEngine(Map.of());
        assertFalse(probe.graphBuilds(), "空节点表不应构建成功（条件边会指向不存在的节点）");
        assertNotNull(probe.graphBuildFailure(), "失败原因必须可诊断");

        ScriptedLlmClient client = new ScriptedLlmClient();
        client.enqueueText("ACTION:SPEAK:3");
        AgentWorkflow.FlowContext state = runWith(
                LangGraph4jWorkflowEngine::new, client, strategyWithCandidate(), new FakeToolGateway());
        assertNotNull(state);
        assertTrue(state.submitted() || state.fallbackUsed(), "图引擎必须完成一个回合");
    }

    @Test
    @DisplayName("模型不可用：两条路径都走兜底、都不调用模型")
    void bothEnginesFallBackIdentically() {
        FakeStrategy strategyA = strategyWithCandidate();
        FakeToolGateway gatewayA = new FakeToolGateway();
        AgentWorkflow.FlowContext sequential = runWith(
                SequentialWorkflowEngine::new, new ScriptedLlmClient().unavailable(), strategyA, gatewayA);

        FakeStrategy strategyB = strategyWithCandidate();
        FakeToolGateway gatewayB = new FakeToolGateway();
        AgentWorkflow.FlowContext graph = runWith(
                LangGraph4jWorkflowEngine::new, new ScriptedLlmClient().unavailable(), strategyB, gatewayB);

        assertEquals(sequential.submitted(), graph.submitted(), "提交结果必须一致");
        assertEquals(sequential.fallbackUsed(), graph.fallbackUsed(), "兜底标记必须一致");
        assertEquals(sequential.llmCalls(), graph.llmCalls(), "模型调用次数必须一致");
        assertEquals(0, graph.llmCalls(), "模型不可用时不得调用模型");
        assertEquals(gatewayA.submittedTools(), gatewayB.submittedTools(), "提交的工具必须一致");
    }

    @Test
    @DisplayName("模型返回合法动作：两条路径都提交且只调用一次模型")
    void bothEnginesSubmitIdenticallyOnValidOutput() {
        ScriptedLlmClient clientA = new ScriptedLlmClient();
        clientA.enqueueText("ACTION:SPEAK:3");
        FakeToolGateway gatewayA = new FakeToolGateway();
        AgentWorkflow.FlowContext sequential = runWith(
                SequentialWorkflowEngine::new, clientA, strategyWithCandidate(), gatewayA);

        ScriptedLlmClient clientB = new ScriptedLlmClient();
        clientB.enqueueText("ACTION:SPEAK:3");
        FakeToolGateway gatewayB = new FakeToolGateway();
        AgentWorkflow.FlowContext graph = runWith(
                LangGraph4jWorkflowEngine::new, clientB, strategyWithCandidate(), gatewayB);

        assertTrue(graph.submitted(), "图引擎必须完成提交，errorCode=" + graph.errorCode());
        assertEquals(sequential.submitted(), graph.submitted());
        assertEquals(sequential.fallbackUsed(), graph.fallbackUsed(), "两条路径的兜底标记必须一致");
        assertEquals(sequential.llmCalls(), graph.llmCalls(), "模型调用次数必须一致");
        assertEquals(1, clientA.callCount(), "顺序引擎应只调用一次模型");
        assertEquals(1, clientB.callCount(), "图引擎应只调用一次模型");
        assertEquals(gatewayA.submittedTools(), gatewayB.submittedTools(), "提交的工具必须一致");
        assertNotNull(graph.proposal());
    }

    @Test
    @DisplayName("网关拒绝：两条路径都继续走兜底而不是放弃")
    void bothEnginesFallBackAfterGatewayDenial() {
        ScriptedLlmClient clientA = new ScriptedLlmClient();
        clientA.enqueueText("ACTION:SPEAK:3");
        FakeToolGateway denyingA = new FakeToolGateway().denying("SpeakTool", "ACTION_NOT_ALLOWED");
        AgentWorkflow.FlowContext sequential = runWith(
                SequentialWorkflowEngine::new, clientA, strategyWithCandidate(), denyingA);

        ScriptedLlmClient clientB = new ScriptedLlmClient();
        clientB.enqueueText("ACTION:SPEAK:3");
        FakeToolGateway denyingB = new FakeToolGateway().denying("SpeakTool", "ACTION_NOT_ALLOWED");
        AgentWorkflow.FlowContext graph = runWith(
                LangGraph4jWorkflowEngine::new, clientB, strategyWithCandidate(), denyingB);

        // 这是一个曾经的真实缺陷：submit 失败后若直接结束，整局不会有任何动作被提交
        // （agent-runtime.md §10 要求"提交被拒 → 兜底"）。
        assertTrue(graph.fallbackUsed(),
                "网关拒绝后必须走兜底，而不是就此结束；errorCode=" + graph.errorCode());
        assertEquals(sequential.fallbackUsed(), graph.fallbackUsed());
        assertEquals(denyingA.eventCount(), denyingB.eventCount(), "两条路径的事件数必须一致");
        assertEquals(0, denyingB.eventCount(), "被拒绝的提交不得产生任何领域事件");
    }

    @Test
    @DisplayName("候选集为空：两条路径都产出并提交 SKIP")
    void emptyCandidatesStillSubmitSkip() {
        FakeStrategy strategyA = new FakeStrategy().withFallback(ActionProposal.skip("NO_CANDIDATE"));
        FakeToolGateway gatewayA = new FakeToolGateway();
        AgentWorkflow.FlowContext sequential = runWith(
                SequentialWorkflowEngine::new, new ScriptedLlmClient().unavailable(), strategyA, gatewayA);

        FakeStrategy strategyB = new FakeStrategy().withFallback(ActionProposal.skip("NO_CANDIDATE"));
        FakeToolGateway gatewayB = new FakeToolGateway();
        AgentWorkflow.FlowContext graph = runWith(
                LangGraph4jWorkflowEngine::new, new ScriptedLlmClient().unavailable(), strategyB, gatewayB);

        assertTrue(graph.submitted(),
                "候选集为空也必须产出并提交 SKIP（strategy-layer.md §9），errorCode=" + graph.errorCode());
        assertEquals(List.of("SpeakTool"), gatewayB.submittedTools(),
                "SKIP 经 SpeakTool 提交（tool-gateway.md §2 的行为映射）");
        assertEquals(gatewayA.submittedTools(), gatewayB.submittedTools());
        assertEquals(gatewayA.eventCount(), gatewayB.eventCount(), "两条路径的事件数必须一致");
    }

    @Test
    @DisplayName("校验失败：两条路径的模型调用次数一致（含修复轮）")
    void bothEnginesRepairTheSameWay() {
        ScriptedLlmClient clientA = new ScriptedLlmClient();
        clientA.enqueueText("坏输出");
        clientA.enqueueText("ACTION:SPEAK:3");
        FakeStrategy strategyA = strategyWithCandidate()
                .failingValidation("OUTPUT_UNPARSABLE", "不是 JSON");
        AgentWorkflow.FlowContext sequential = runWith(
                SequentialWorkflowEngine::new, clientA, strategyA, new FakeToolGateway());

        ScriptedLlmClient clientB = new ScriptedLlmClient();
        clientB.enqueueText("坏输出");
        clientB.enqueueText("ACTION:SPEAK:3");
        FakeStrategy strategyB = strategyWithCandidate()
                .failingValidation("OUTPUT_UNPARSABLE", "不是 JSON");
        AgentWorkflow.FlowContext graph = runWith(
                LangGraph4jWorkflowEngine::new, clientB, strategyB, new FakeToolGateway());

        assertEquals(clientA.callCount(), clientB.callCount(),
                "两条路径的模型调用次数必须一致（含修复轮）");
        assertEquals(sequential.submitted(), graph.submitted(), "提交结果必须一致");
        assertTrue(graph.submitted() || graph.fallbackUsed(), "修复或兜底之后必须有一个动作被提交");
    }

    @Test
    @DisplayName("防泄漏触发重写：两条路径行为一致")
    void bothEnginesRewriteOnLeak() {
        ScriptedLlmClient clientA = new ScriptedLlmClient();
        clientA.enqueueText("ACTION:SPEAK:3");
        clientA.enqueueText("ACTION:SPEAK:3");
        FakeStrategy strategyA = strategyWithCandidate().leaking();
        AgentWorkflow.FlowContext sequential = runWith(
                SequentialWorkflowEngine::new, clientA, strategyA, new FakeToolGateway());

        ScriptedLlmClient clientB = new ScriptedLlmClient();
        clientB.enqueueText("ACTION:SPEAK:3");
        clientB.enqueueText("ACTION:SPEAK:3");
        FakeStrategy strategyB = strategyWithCandidate().leaking();
        AgentWorkflow.FlowContext graph = runWith(
                LangGraph4jWorkflowEngine::new, clientB, strategyB, new FakeToolGateway());

        assertEquals(clientA.callCount(), clientB.callCount(), "重写轮的模型调用次数必须一致");
        assertEquals(sequential.submitted(), graph.submitted(), "提交结果必须一致");
        assertTrue(graph.submitted() || graph.fallbackUsed());
    }

    @Test
    @DisplayName("一个回合最多提交一次动作（两条路径都不重复提交）")
    void atMostOneSubmissionPerTurn() {
        ScriptedLlmClient client = new ScriptedLlmClient();
        client.enqueueText("ACTION:SPEAK:3");
        FakeToolGateway gateway = new FakeToolGateway();
        runWith(LangGraph4jWorkflowEngine::new, client, strategyWithCandidate(), gateway);

        assertTrue(gateway.submittedTools().size() <= 1,
                "一个回合最多提交一次动作，实际 " + gateway.submittedTools());
        assertEquals(1, gateway.eventCount(), "一次成功提交只应产生一个领域事件");
    }
}
