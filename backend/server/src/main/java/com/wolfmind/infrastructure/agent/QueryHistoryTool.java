package com.wolfmind.infrastructure.agent;

import com.wolfmind.application.agent.model.AgentView;
import com.wolfmind.application.agent.model.ToolSession;
import com.wolfmind.application.agent.tool.ToolGatewayPort;
import com.wolfmind.application.port.GameViewQueryPort;
import com.wolfmind.domain.game.event.EventEnvelope;
import com.wolfmind.domain.game.view.VisibilityFilter;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * {@code QueryHistoryTool}（tool-gateway.md §2.1）：只读，按可见性过滤后分页返回事件。
 *
 * <p>两条纪律：
 * <ol>
 *   <li><b>可见性只有一份实现</b>：事件先用 {@code GameViewQueryPort.replay}（它内部调用
 *       {@code VisibilityFilter}）取回，再用 {@link VisibilityFilter#filter} 过滤一次。
 *       第二次过滤是防御性的重复调用，<b>不是</b>第二份判定——如果哪天某个
 *       {@code GameViewQueryPort} 实现忘了过滤，这里仍然不会泄漏（AP-6）；</li>
 *   <li><b>不产生事件</b>：读路径只读，调用前后 {@code game:{id}:events} 长度不变（§12）。</li>
 * </ol>
 *
 * <p>{@code limit} 的上限来自 {@code gateway.queryHistory.maxLimit}（默认 100，§11），
 * 已由 G2 校验；本类只负责按区间读取与映射。
 */
public final class QueryHistoryTool {

    /** 单次读取的硬上限：与 {@code InMemoryGameViewQuery} / api-spec §6 的 500 对齐。 */
    public static final int REPLAY_MAX_EVENTS = 500;

    private final GameViewQueryPort query;

    public QueryHistoryTool(GameViewQueryPort query) {
        if (query == null) {
            throw new IllegalArgumentException("QueryHistoryTool 需要 GameViewQueryPort");
        }
        this.query = query;
    }

    /** 读取并返回结果。参数形态与可见性判定都已经在前面的门里完成。 */
    public ToolGatewayPort.ToolResult.Ok handle(
            ToolSession session, Integer fromVersion, Integer toVersion, int limit, AgentView view) {
        int from = fromVersion == null ? 1 : Math.max(1, fromVersion);
        int to = toVersion == null ? view.version() : toVersion;
        List<Map<String, Object>> events = to < from
                ? List.of()
                : read(session, view, from, to, Math.min(limit, REPLAY_MAX_EVENTS));

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put(QueryGameStateTool.KEY_QUERY, ToolGatewayPort.QueryOutcome.of(events));
        payload.put("fromVersion", from);
        payload.put("toVersion", to);
        payload.put("limit", limit);
        payload.put("count", events.size());
        return new ToolGatewayPort.ToolResult.Ok(
                ToolCatalog.QUERY_HISTORY, List.of(), List.of(), view.version(), payload);
    }

    private List<Map<String, Object>> read(
            ToolSession session, AgentView view, int from, int to, int limit) {
        // 读取者上下文需要狼队信息，而狼队信息只在角色可见视图里：只用"我是这个座位"的身份
        // 去查历史，狼人会看不到 WOLF 事件（提名与狼刀），而那正是狼队视角必须有的信息。
        var viewer = ViewerContexts.forAgentView(view, session.playerId());
        List<EventEnvelope> visible = VisibilityFilter.filter(
                query.replay(session.gameId(), viewer, from, to, limit), viewer);
        List<Map<String, Object>> events = new ArrayList<>(visible.size());
        for (EventEnvelope envelope : visible) {
            events.add(toMap(envelope));
        }
        return List.copyOf(events);
    }

    private static Map<String, Object> toMap(EventEnvelope envelope) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("eventId", envelope.eventId());
        item.put("type", envelope.type());
        item.put("version", envelope.version());
        item.put("round", envelope.round());
        item.put("phase", envelope.phase());
        item.put("occurredAt", envelope.occurredAt());
        item.put("visibility", envelope.visibility().name());
        item.put("actorId", envelope.actorId());
        item.put("payload", envelope.payload());
        return Map.copyOf(item);
    }
}
