package com.wolfmind.application.fake;

import com.wolfmind.application.port.GameViewQueryPort;
import com.wolfmind.domain.game.event.EventEnvelope;
import com.wolfmind.domain.game.model.GamePhase;
import com.wolfmind.domain.game.model.GameStatus;
import com.wolfmind.domain.game.model.ViewerContext;
import com.wolfmind.domain.game.view.GameViewSnapshot;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** 假视图查询端口：返回预置视图并记录调用参数，用于验证"薄封装"确实只做委托。 */
public final class FakeGameViewQuery implements GameViewQueryPort {

    private final List<String> viewCalls = new ArrayList<>();
    private final List<String> replayCalls = new ArrayList<>();
    private final List<String> listCalls = new ArrayList<>();
    private ViewerContext lastViewer;
    private GameViewSnapshot view;

    public void setView(GameViewSnapshot view) {
        this.view = view;
    }

    @Override
    public GameViewSnapshot view(String gameId, ViewerContext viewer) {
        viewCalls.add(gameId);
        lastViewer = viewer;
        return view;
    }

    @Override
    public List<EventEnvelope> replay(
            String gameId, ViewerContext viewer, int fromVersion, int toVersion, int maxEvents) {
        replayCalls.add(gameId + ":" + fromVersion + "-" + toVersion + ":" + maxEvents);
        lastViewer = viewer;
        return List.of();
    }

    /**
     * 指定版本的视图。
     *
     * <p>假实现没有历史状态，因此只在 {@code atVersion} 等于当前视图版本时给出视图，
     * 其余情况抛 {@link UnsupportedOperationException}——一个"回退到当前视图"的假实现会让
     * "回放某一步"的断言在错误的数据上通过。
     */
    @Override
    public GameViewSnapshot viewAt(String gameId, ViewerContext viewer, int atVersion) {
        viewCalls.add(gameId + "@" + atVersion);
        lastViewer = viewer;
        if (view == null || view.version() != atVersion) {
            throw new UnsupportedOperationException("FakeGameViewQuery 没有版本 " + atVersion + " 的历史状态");
        }
        return view;
    }

    @Override
    public int currentVersion(String gameId) {
        return 0;
    }

    @Override
    public com.wolfmind.domain.game.model.ViewerContext viewerContextFor(
            String gameId, String viewerUserId) {
        // 假实现不做反查：返回匿名（只看 PUBLIC）。需要验证可见性的测试用真实实现。
        return com.wolfmind.domain.game.model.ViewerContext.anonymous();
    }

    @Override
    public List<Map<String, Object>> listGames(String status, String roomId, int page, int pageSize) {
        listCalls.add(status + "/" + roomId + "/" + page + "/" + pageSize);
        return List.of(Map.of("gameId", "g_1"));
    }

    public List<String> viewCalls() {
        return List.copyOf(viewCalls);
    }

    public List<String> replayCalls() {
        return List.copyOf(replayCalls);
    }

    public List<String> listCalls() {
        return List.copyOf(listCalls);
    }

    public ViewerContext lastViewer() {
        return lastViewer;
    }

    /** 测试用的最小合法视图：只填公共字段，脱敏结果不在这里重新实现。 */
    public static GameViewSnapshot sampleView(String gameId, int version) {
        return new GameViewSnapshot(
                gameId,
                version,
                GameStatus.RUNNING,
                GamePhase.NIGHT_WOLF,
                1,
                0,
                List.of(1, 2),
                null,
                Map.of("speechMaxLength", 500),
                null,
                List.of(),
                List.of(),
                List.of(),
                false,
                null,
                null,
                // permissions：匿名读取者没有可执行动作。真实规则由 ActionPermissions 计算，
                // 这个夹具只提供"形状合法"的空列表。
                List.of());
    }
}
