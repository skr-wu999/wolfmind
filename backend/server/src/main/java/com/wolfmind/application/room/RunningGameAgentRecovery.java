package com.wolfmind.application.room;

import com.wolfmind.application.port.GameRepositoryPort;
import com.wolfmind.application.port.GameViewQueryPort;
import com.wolfmind.domain.game.model.GameSnapshot;
import com.wolfmind.domain.game.model.PlayerSnapshot;
import com.wolfmind.domain.game.model.SeatAssignment;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 启动时为持久化存储里仍在运行的对局补建 AI Agent。
 *
 * <p>只补建 Agent 实例，不改对局状态、不写事件：恢复后的第一个 AI 回合照常走提交路径。
 * 重启前的 Agent 短期记忆不恢复，Agent 从当前授权视图重新开始推理。
 */
public final class RunningGameAgentRecovery {

    private static final Logger LOG = LoggerFactory.getLogger(RunningGameAgentRecovery.class);
    private static final int PAGE_SIZE = 100;

    private final GameViewQueryPort gameViews;
    private final GameRepositoryPort repository;
    private final StartRoomGameApplicationService startRoomGame;

    public RunningGameAgentRecovery(
            GameViewQueryPort gameViews,
            GameRepositoryPort repository,
            StartRoomGameApplicationService startRoomGame) {
        this.gameViews = Objects.requireNonNull(gameViews, "gameViews");
        this.repository = Objects.requireNonNull(repository, "repository");
        this.startRoomGame = Objects.requireNonNull(startRoomGame, "startRoomGame");
    }

    /** @return 补建了 Agent 的对局数 */
    public int recover() {
        int recoveredGames = 0;
        for (int page = 1; ; page++) {
            List<Map<String, Object>> running = gameViews.listGames("RUNNING", null, page, PAGE_SIZE);
            for (Map<String, Object> item : running) {
                if (item.get("gameId") instanceof String gameId && recoverGame(gameId) > 0) {
                    recoveredGames++;
                }
            }
            if (running.size() < PAGE_SIZE) {
                break;
            }
        }
        if (recoveredGames > 0) {
            LOG.info("重启恢复：为 {} 个运行中对局补建了 AI Agent", recoveredGames);
        }
        return recoveredGames;
    }

    private int recoverGame(String gameId) {
        try {
            GameSnapshot snapshot = repository.load(gameId).game().snapshot();
            List<SeatAssignment> seats = new ArrayList<>(snapshot.players().size());
            for (PlayerSnapshot player : snapshot.players()) {
                seats.add(new SeatAssignment(player.seatNo(), player.controller(), player.ready()));
            }
            return startRoomGame.restoreAgents(gameId, seats);
        } catch (RuntimeException error) {
            LOG.warn("重启恢复：对局 {} 的 Agent 补建失败，AI 回合将走超时跳过", gameId, error);
            return 0;
        }
    }
}
