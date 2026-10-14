package com.wolfmind.application.agent.trace;

import java.util.List;
import java.util.Map;

/**
 * 决策轨迹端口：写 {@code AGENT_} 前缀事件（agent-runtime.md §12）。
 *
 * <p>硬约束：这些事件写入 {@code game:{id}:agent:{playerId}}，**绝不进入**
 * {@code game:{id}:events}，因此不会影响对局状态（C-4、C-5）。
 *
 * <p>隐私底线（§12 最后一句）：日志与轨迹不记录完整 prompt，也不记录私有事件 payload，
 * 避免日志成为隐藏信息泄漏渠道。因此 {@code payload} 只放结构化摘要。
 * 唯一例外是复读拦截的 {@code draft}：它已通过防泄漏检查，本就是准备公开的发言；泄密拦截从不记录原稿。
 */
public interface AgentTracePort {

    /**
     * 记录一条决策事件。
     *
     * @param type    {@code AGENT_} 前缀类型，取值见 {@link TraceEventType}
     * @param payload 结构化摘要；不得包含完整 prompt、密钥或他人私有 payload
     */
    void record(AgentTraceEvent event);

    /** 读取某座位某对局的轨迹，供 AI 分析与测试断言。 */
    List<AgentTraceEvent> traces(String gameId, String playerId);

    /** Runtime calls this after the last admitted turn has returned. */
    default void finishGame(String gameId) {}

    /** Expire finished caches and retry pending trace archives. */
    default void maintain() {}

    /** {@code AGENT_} 事件类型目录（event-design.md §7.4）。 */
    enum TraceEventType {
        AGENT_TURN_STARTED,
        AGENT_CONTEXT_BUILT,
        AGENT_STRATEGY_DECIDED,
        AGENT_LLM_INVOKED,
        AGENT_OUTPUT_REJECTED,
        AGENT_TOOL_CALLED,
        AGENT_TURN_FINISHED,
        AGENT_TURN_TIMEOUT,
        AGENT_TURN_FAILED
    }

    /**
     * 一条决策事件。
     *
     * @param type      事件类型
     * @param gameId    对局
     * @param playerId  座位
     * @param version   对局版本（回合定位）
     * @param phase     阶段
     * @param round     轮次
     * @param occurredAt 毫秒时间戳
     * @param payload   结构化摘要
     */
    record AgentTraceEvent(
            TraceEventType type,
            String gameId,
            String playerId,
            int version,
            String phase,
            int round,
            long occurredAt,
            Map<String, Object> payload) {

        public AgentTraceEvent {
            if (type == null) {
                throw new IllegalArgumentException("轨迹事件类型不能为空");
            }
            payload = Map.copyOf(payload);
        }

        /** 所有轨迹事件的类型前缀都是 {@code AGENT_}，用于断言"不进入对局事件流"。 */
        public String wireType() {
            return type.name();
        }
    }
}
