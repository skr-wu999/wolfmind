package com.wolfmind.infrastructure.agent;

import com.wolfmind.application.agent.trace.AgentTracePort;

import java.util.ArrayList;
import java.util.List;

/**
 * 测试用轨迹端口：把审计记录留在内存里，供断言字段完整性与隐私（不含发言全文）。
 *
 * <p>它不写对局事件流，因此"审计没有污染 {@code game:{id}:events}"这条断言才有意义：
 * 事件流长度由真实事件存储单独计数。
 */
final class RecordingAgentTrace implements AgentTracePort {

    private final List<AgentTraceEvent> events = new ArrayList<>();

    @Override
    public void record(AgentTraceEvent event) {
        events.add(event);
    }

    @Override
    public List<AgentTraceEvent> traces(String gameId, String playerId) {
        return events.stream()
                .filter(event -> event.gameId().equals(gameId))
                .filter(event -> playerId == null || event.playerId().equals(playerId))
                .toList();
    }

    List<AgentTraceEvent> all() {
        return List.copyOf(events);
    }

    List<AgentTraceEvent> toolCalls() {
        return events.stream()
                .filter(event -> event.type() == TraceEventType.AGENT_TOOL_CALLED)
                .toList();
    }

    int size() {
        return events.size();
    }

    /** 最后一条审计记录；没有记录时直接让测试失败。 */
    AgentTraceEvent last() {
        if (events.isEmpty()) {
            throw new AssertionError("没有任何轨迹记录");
        }
        return events.get(events.size() - 1);
    }

    /** 把一条审计记录的 payload 摊平成"值是否含某文本"的检查，用于隐私断言。 */
    boolean anyPayloadContains(String text) {
        return events.stream().anyMatch(event -> event.payload().values().stream()
                .anyMatch(value -> String.valueOf(value).contains(text)));
    }
}
