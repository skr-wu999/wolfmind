package com.wolfmind.application.agent.fake;

import com.wolfmind.application.agent.model.AgentView;
import com.wolfmind.application.agent.view.AgentViewQueryPort;

import java.util.LinkedHashMap;
import java.util.Map;

/** 视图查询假实现：按 (gameId, playerId) 返回预置视图。 */
public final class FakeAgentViewQuery implements AgentViewQueryPort {

    private final Map<String, AgentView> views = new LinkedHashMap<>();

    public FakeAgentViewQuery put(String gameId, String playerId, AgentView view) {
        views.put(gameId + ":" + playerId, view);
        return this;
    }

    /** 为 9 个座位各放一个视图：角色按标准局顺序给出。 */
    public static FakeAgentViewQuery nineSeats(
            String gameId, java.util.List<String> rolesBySeat, java.util.List<String> controllerRefs) {
        FakeAgentViewQuery query = new FakeAgentViewQuery();
        for (int index = 0; index < rolesBySeat.size(); index++) {
            int seatNo = index + 1;
            AgentView view = FakeAgentView.of(rolesBySeat.get(index), seatNo, otherSeats(seatNo), "DAY_DISCUSS");
            query.put(gameId, controllerRefs.get(index), view);
        }
        return query;
    }

    private static java.util.List<Integer> otherSeats(int ownSeat) {
        java.util.List<Integer> others = new java.util.ArrayList<>();
        for (int seat = 1; seat <= 9; seat++) {
            if (seat != ownSeat) {
                others.add(seat);
            }
        }
        return others;
    }

    @Override
    public AgentView viewFor(String gameId, String playerId) {
        return views.get(gameId + ":" + playerId);
    }

    @Override
    public boolean isSeatInGame(String gameId, String playerId) {
        return views.containsKey(gameId + ":" + playerId);
    }
}
