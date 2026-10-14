package com.wolfmind.application.agent.fake;

import com.wolfmind.application.agent.trace.AgentTracePort;

import java.util.ArrayList;
import java.util.List;

/** 内存轨迹实现：断言"记录了哪些决策事件"以及"它们不进入对局事件流"。 */
public final class InMemoryAgentTrace implements AgentTracePort {

    private final List<AgentTraceEvent> events = new ArrayList<>();

    @Override
    public void record(AgentTraceEvent event) {
        events.add(event);
    }

    @Override
    public List<AgentTraceEvent> traces(String gameId, String playerId) {
        return events.stream()
                .filter(event -> event.gameId().equals(gameId) && event.playerId().equals(playerId))
                .toList();
    }

    public List<AgentTraceEvent> all() {
        return List.copyOf(events);
    }

    public long countOf(TraceEventType type) {
        return events.stream().filter(event -> event.type() == type).count();
    }

    public boolean hasType(TraceEventType type) {
        return events.stream().anyMatch(event -> event.type() == type);
    }

    /** 全部轨迹类型都必须以 AGENT_ 开头，且不可能是领域事件类型。 */
    public boolean allWireTypesAreAgentPrefixed() {
        return events.stream().allMatch(event -> event.wireType().startsWith("AGENT_"));
    }
}
