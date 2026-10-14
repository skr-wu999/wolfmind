package com.wolfmind.infrastructure.memory;

import com.wolfmind.application.port.NotificationPort;
import com.wolfmind.domain.game.event.EventEnvelope;

import java.util.ArrayList;
import java.util.List;

/**
 * 内存通知端口：记录"事务提交之后"发生的对外动作，供测试断言编排顺序。
 *
 * <p>它不得反过来修改对局状态（INV-13）。这里只做记录，不回调任何领域对象，
 * 因此顺序错误（例如在提交之前通知）会在测试里直接表现为调用记录为空。
 */
public final class InMemoryNotification implements NotificationPort {

    private final List<Commit> commits = new ArrayList<>();
    private final List<String> finishedGames = new ArrayList<>();

    @Override
    public void onEventsCommitted(String gameId, int version, List<EventEnvelope> events) {
        if (gameId == null || gameId.isBlank()) {
            throw new IllegalArgumentException("gameId 不能为空");
        }
        synchronized (commits) {
            commits.add(new Commit(gameId, version, List.copyOf(events)));
        }
    }

    @Override
    public void onGameFinished(String gameId) {
        if (gameId == null || gameId.isBlank()) {
            throw new IllegalArgumentException("gameId 不能为空");
        }
        synchronized (finishedGames) {
            finishedGames.add(gameId);
        }
    }

    /** 已收到的提交通知。 */
    public List<Commit> commits() {
        synchronized (commits) {
            return List.copyOf(commits);
        }
    }

    /** 已收到终态通知的对局，按顺序。 */
    public List<String> finishedGames() {
        synchronized (finishedGames) {
            return List.copyOf(finishedGames);
        }
    }

    /** 一次提交通知。 */
    public record Commit(String gameId, int version, List<EventEnvelope> events) {}
}
