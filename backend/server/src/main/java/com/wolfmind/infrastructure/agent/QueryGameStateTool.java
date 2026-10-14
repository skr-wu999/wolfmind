package com.wolfmind.infrastructure.agent;

import com.wolfmind.application.agent.model.AgentView;
import com.wolfmind.application.agent.tool.ToolGatewayPort;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * {@code QueryGameStateTool}（tool-gateway.md §2.1）：只读，返回角色可见的对局视图。
 *
 * <p>投影与脱敏已经在读取视图时完成（{@code AgentViewAdapter} → {@code GameViewProjector}），
 * 本类只做一件事：按 {@code scope} 裁剪出该 scope 允许出现的信息。
 *
 * <p>三个 scope 的边界（文档只给了内容清单，没有给"是否包含关系"，这里按<b>单调包含</b>实现
 * 并记录为一次裁决：{@code PUBLIC ⊆ MY_CAMP ⊆ SELF}——只读工具是给模型"看状态"的，
 * 若三个 scope 互不包含，模型在同一回合里就得调用三次才能拼出完整视角，直接浪费预算）：
 * <ul>
 *   <li>{@code PUBLIC}：公开信息 + 各座位的公开字段；所有 {@code role} / {@code camp} / {@code skills}
 *       一律置空，{@code privateEvents} 为空；</li>
 *   <li>{@code MY_CAMP}：在 {@code PUBLIC} 之上加阵营可见信息——狼人视角的队友
 *       （{@code other[].role/camp} 由投影决定，村民拿到的仍是 {@code null}）与阵营私有事实
 *       （{@code WOLF_TEAMMATE}）。狼刀本身经 {@code QueryHistoryTool} 获取：{@code WOLF_KILL_*}
 *       是 {@code WOLF} 可见性事件，属于事件查询而不是状态视图，而 {@code AgentView.PrivateFact}
 *       的封闭词表里没有"狼刀"这一类（冻结类型，未新增）；</li>
 *   <li>{@code SELF}：在 {@code MY_CAMP} 之上加本座位的完整身份与私有事实
 *       （{@code me.role/camp/skills} 与 {@code SEER_INSPECT} / {@code NIGHT_KILL} /
 *       {@code WITCH_SAVED} / {@code WITCH_POISONED}）。</li>
 * </ul>
 *
 * <p><b>不存在 {@code GOD}</b>：{@code scope} 的取值只来自 {@link ToolCatalog#SCOPES}，
 * G2 的枚举校验会让 {@code GOD} 得到 {@code TOOL_ARGS_INVALID}（§8"观测作弊"、§13）。
 */
public final class QueryGameStateTool {

    /** payload 里 {@code QueryOutcome} 的键：Agent 侧从 {@code Ok.payload()} 取它。 */
    public static final String KEY_QUERY = "query";

    private QueryGameStateTool() {}

    /** 裁剪出该 scope 的视图并包装成成功结果。 */
    public static ToolGatewayPort.ToolResult.Ok handle(String scope, AgentView full) {
        AgentView scoped = reduce(full, scope);
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put(KEY_QUERY, ToolGatewayPort.QueryOutcome.of(scoped));
        payload.put("scope", scope);
        payload.put("version", full.version());
        payload.put("status", full.status());
        payload.put("phase", full.phase());
        payload.put("round", full.round());
        return new ToolGatewayPort.ToolResult.Ok(
                ToolCatalog.QUERY_GAME_STATE, List.of(), List.of(), full.version(), payload);
    }

    /** 按 scope 裁剪。三个 scope 都是同一份数据的子集，不重新读取任何东西。 */
    public static AgentView reduce(AgentView full, String scope) {
        AgentView.Self me = full.me();
        List<AgentView.PrivateFact> facts = full.privateEvents();
        AgentView.Self publicMe = me == null ? null : new AgentView.Self(
                me.playerId(), me.seatNo(), null, null, me.alive(), me.controllerType(), null);

        return switch (scope) {
            case "PUBLIC" -> new AgentView(
                    full.gameId(), full.version(), full.status(), full.phase(), full.round(),
                    full.turnIndex(), full.turnOrder(), full.currentTurn(), full.rules(),
                    publicMe, hideRoles(full.other()), full.votes(), full.deaths(), full.speeches(),
                    List.of(), full.winner());
            case "MY_CAMP" -> new AgentView(
                    full.gameId(), full.version(), full.status(), full.phase(), full.round(),
                    full.turnIndex(), full.turnOrder(), full.currentTurn(), full.rules(),
                    publicMe, full.other(), full.votes(), full.deaths(), full.speeches(),
                    factionFacts(facts), full.winner(), full.wolfChat());
            default -> new AgentView(
                    full.gameId(), full.version(), full.status(), full.phase(), full.round(),
                    full.turnIndex(), full.turnOrder(), full.currentTurn(), full.rules(),
                    me, full.other(), full.votes(), full.deaths(), full.speeches(),
                    Facts.all(facts), full.winner(), full.wolfChat());
        };
    }

    private static List<AgentView.SeatView> hideRoles(List<AgentView.SeatView> seats) {
        List<AgentView.SeatView> hidden = new ArrayList<>(seats.size());
        for (AgentView.SeatView seat : seats) {
            hidden.add(new AgentView.SeatView(
                    seat.playerId(), seat.seatNo(), seat.alive(), null, null, seat.controllerType()));
        }
        return List.copyOf(hidden);
    }

    /**
     * 阵营私有事实：只保留 {@code WOLF_TEAMMATE}。
     *
     * <p>为什么不能直接给全部私有事实：{@code SEER_INSPECT} / {@code NIGHT_KILL} / 用药记录
     * 是<b>本座位</b>的事实，不是阵营共享信息。女巫与狼人同属村民/狼人阵营时也不共享。
     */
    private static List<AgentView.PrivateFact> factionFacts(List<AgentView.PrivateFact> facts) {
        return Facts.only(facts, AgentView.PrivateFact.FactKind.WOLF_TEAMMATE);
    }

    /** 私有事实的筛选工具：词表是封闭的，未知 kind 一律丢弃而不是原样带出。 */
    static final class Facts {

        /** 五个 kind 的完整清单（AgentView.PrivateFact.FactKind），未知 kind 说明来源有缺陷。 */
        private static final List<AgentView.PrivateFact.FactKind> VOCABULARY = List.of(
                AgentView.PrivateFact.FactKind.SEER_INSPECT,
                AgentView.PrivateFact.FactKind.NIGHT_KILL,
                AgentView.PrivateFact.FactKind.WITCH_SAVED,
                AgentView.PrivateFact.FactKind.WITCH_POISONED,
                AgentView.PrivateFact.FactKind.WOLF_TEAMMATE);

        private Facts() {}

        static List<AgentView.PrivateFact> all(List<AgentView.PrivateFact> facts) {
            List<AgentView.PrivateFact> kept = new ArrayList<>(facts.size());
            for (AgentView.PrivateFact fact : facts) {
                if (VOCABULARY.contains(fact.kindOrNull())) {
                    kept.add(fact);
                }
            }
            return List.copyOf(kept);
        }

        static List<AgentView.PrivateFact> only(
                List<AgentView.PrivateFact> facts, AgentView.PrivateFact.FactKind kind) {
            List<AgentView.PrivateFact> kept = new ArrayList<>();
            for (AgentView.PrivateFact fact : facts) {
                if (fact.is(kind)) {
                    kept.add(fact);
                }
            }
            return List.copyOf(kept);
        }
    }
}
