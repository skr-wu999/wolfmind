package com.wolfmind.infrastructure.ai;

import com.wolfmind.application.agent.runtime.AgentWorkflow;
import com.wolfmind.application.agent.runtime.SequentialWorkflowEngine;
import com.wolfmind.application.agent.runtime.WorkflowEngine;
import java.util.Locale;
import java.util.Map;

/** Immutable process configuration; selecting a fallback requires restarting the service. */
public final class WorkflowEngineSelection implements AgentWorkflow.WorkflowEngineFactory {
    public enum Mode { LANGGRAPH4J, SEQUENTIAL }
    private final Mode mode;

    public WorkflowEngineSelection(String configured) {
        String value = configured == null ? "LANGGRAPH4J" : configured.trim().toUpperCase(Locale.ROOT);
        try { mode = Mode.valueOf(value); }
        catch (IllegalArgumentException invalid) {
            throw new IllegalArgumentException("wolfmind.agent.workflow-engine must be LANGGRAPH4J or SEQUENTIAL", invalid);
        }
    }

    @Override public WorkflowEngine create(Map<WorkflowEngine.WorkflowNode, WorkflowEngine.NodeHandler> handlers) {
        return switch (mode) {
            case LANGGRAPH4J -> new LangGraph4jWorkflowEngine(handlers);
            case SEQUENTIAL -> new SequentialWorkflowEngine(handlers);
        };
    }
    @Override public String engineName() { return mode.name(); }
    public Mode mode() { return mode; }
}
