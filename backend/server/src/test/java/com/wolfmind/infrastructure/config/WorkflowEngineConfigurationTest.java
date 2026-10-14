package com.wolfmind.infrastructure.config;

import com.wolfmind.application.agent.runtime.AgentRuntimeImpl;
import com.wolfmind.application.agent.speech.SpeechPreviewPort;
import com.wolfmind.application.agent.trace.AgentTracePort;
import com.wolfmind.application.agent.view.AgentViewQueryPort;
import com.wolfmind.application.port.MetricsPort;
import com.wolfmind.application.scheduling.TurnTaskStorePort;
import com.wolfmind.infrastructure.agent.ToolGatewayAdapter;
import com.wolfmind.infrastructure.ai.LangGraph4jWorkflowEngine;
import com.wolfmind.infrastructure.ai.WorkflowEngineSelection;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.env.SystemEnvironmentPropertySource;
import org.springframework.mock.env.MockEnvironment;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class WorkflowEngineConfigurationTest {
    @Test void productionTraceRetentionHonorsConfiguredLimits() {
        var now = new java.util.concurrent.atomic.AtomicLong(1_000);
        var trace = new MemoryStorageConfiguration().agentTracePort(new MockEnvironment()
                .withProperty("wolfmind.agent.trace.finished-max-games", "2")
                .withProperty("wolfmind.agent.trace.finished-retention-ms", "100"), now::get, MetricsPort.NOOP);
        for (int i = 0; i < 3; i++) {
            trace.record(new AgentTracePort.AgentTraceEvent(AgentTracePort.TraceEventType.AGENT_TURN_FINISHED,
                    "game-" + i, "p_1", 10, "DAY_VOTE", 1, now.get(), Map.of()));
            trace.finishGame("game-" + i);
        }
        assertTrue(trace.traces("game-0", "p_1").isEmpty());
        assertEquals(1, trace.traces("game-2", "p_1").size());
        now.addAndGet(100);
        trace.maintain();
        assertTrue(trace.traces("game-2", "p_1").isEmpty());
    }
    @Test void speechModeEnvironmentOverridesNewAgentConfigAndInvalidModeFails() throws Exception {
        var environment = new StandardEnvironment();
        environment.getPropertySources().addFirst(new SystemEnvironmentPropertySource("test-speech-env",
                Map.of("WOLFMIND_AGENT_SPEECH_MODE", "B")));
        var runtime = new AgentRuntimeImpl(null, null, null, null, null, null, () -> 1000);
        new AgentRuntimeConfiguration().agentSpeechMode(runtime, environment).afterPropertiesSet();
        runtime.createAgent(new com.wolfmind.application.agent.AgentRuntime.CreateAgentCommand("g", "p", 1,
                com.wolfmind.application.agent.model.AgentConfig.ruleBaseline("test")));
        assertEquals(com.wolfmind.application.agent.model.AgentConfig.WorkflowMode.B, runtime.configOf("g", "p").orElseThrow().mode());
        environment.getPropertySources().addFirst(new SystemEnvironmentPropertySource("invalid-speech-env",
                Map.of("WOLFMIND_AGENT_SPEECH_MODE", "invalid")));
        assertThrows(IllegalArgumentException.class, () -> new AgentRuntimeConfiguration().agentSpeechMode(runtime, environment));
    }
    @Test void productionDefaultsToLangGraphAndAllowsExplicitSequential() {
        var config = new AgentRuntimeConfiguration();
        assertEquals(WorkflowEngineSelection.Mode.LANGGRAPH4J, config.workflowEngineSelection(new MockEnvironment()).mode());
        assertEquals(WorkflowEngineSelection.Mode.SEQUENTIAL, config.workflowEngineSelection(
                new MockEnvironment().withProperty("wolfmind.agent.workflow-engine", "sequential")).mode());
        assertThrows(IllegalArgumentException.class, () -> config.workflowEngineSelection(
                new MockEnvironment().withProperty("wolfmind.agent.workflow-engine", "unknown")));
    }
    @Test void deploymentEnvironmentVariableSelectsSequential() {
        var environment = new StandardEnvironment();
        environment.getPropertySources().addFirst(new SystemEnvironmentPropertySource("test-env",
                Map.of("WOLFMIND_AGENT_WORKFLOW_ENGINE", "SEQUENTIAL")));
        assertEquals(WorkflowEngineSelection.Mode.SEQUENTIAL, new AgentRuntimeConfiguration().workflowEngineSelection(environment).mode());
    }
    @Test void productionBeanValidatesRealNodeHandlersWithoutInvokingPorts() {
        var views=mock(AgentViewQueryPort.class); var gateway=mock(ToolGatewayAdapter.class);
        var trace=mock(AgentTracePort.class); var tasks=mock(TurnTaskStorePort.class);
        @SuppressWarnings("unchecked") ObjectProvider<SpeechPreviewPort> preview=mock(ObjectProvider.class);
        var runtime=new AgentRuntimeConfiguration().agentRuntime(views,gateway,trace,()->1000,
                MetricsPort.NOOP,null,tasks,new WorkflowEngineSelection(null),preview);
        assertInstanceOf(AgentRuntimeImpl.class,runtime);
        verifyNoInteractions(views,gateway,trace,tasks);
    }
    @Test void invalidProductionGraphFailsStartupInsteadOfSelectingAnotherEngine() {
        var selection=mock(WorkflowEngineSelection.class);
        when(selection.create(anyMap())).thenReturn(new LangGraph4jWorkflowEngine(Map.of()));
        @SuppressWarnings("unchecked") ObjectProvider<SpeechPreviewPort> preview=mock(ObjectProvider.class);
        assertThrows(IllegalStateException.class, () -> new AgentRuntimeConfiguration().agentRuntime(
                mock(AgentViewQueryPort.class),mock(ToolGatewayAdapter.class),mock(AgentTracePort.class),()->1000,
                MetricsPort.NOOP,null,mock(TurnTaskStorePort.class),selection,preview));
        verify(selection,times(1)).create(anyMap());
    }
}
