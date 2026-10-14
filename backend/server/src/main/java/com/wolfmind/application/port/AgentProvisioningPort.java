package com.wolfmind.application.port;

import java.util.List;

/** 房间与恢复用例只提供已验证的座位，不依赖 Agent 配置或领域对象。 */
public interface AgentProvisioningPort {
    record Seat(String playerId, int seatNo, boolean aiControlled) {}
    int prepare(String gameId, List<Seat> seats);
    int restore(String gameId, List<Seat> seats);
}
