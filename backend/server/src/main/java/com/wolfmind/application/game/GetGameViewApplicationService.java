package com.wolfmind.application.game;

import com.wolfmind.application.port.GameViewQueryPort;
import com.wolfmind.domain.game.event.EventEnvelope;
import com.wolfmind.domain.game.model.ViewerContext;
import com.wolfmind.domain.game.view.GameViewSnapshot;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 读取对局视图的用例（api-spec.md §5.2、§6、§5.4）。
 *
 * <p>只读用例不产生事件（ddd-design.md §6.2）：它只做一次委托。脱敏由领域侧的
 * {@code GameViewProjector} 完成，可见性只有一份实现，REST、WebSocket 首次同步与 AI 工具共用它；
 * 任何"在这里再过滤一次"的写法都是 AP-6，也是隐藏信息泄漏的典型成因。
 *
 * <p>本类不含任何状态判断——包括"这个版本能不能看"，那是投影与可见性矩阵的职责。
 */
public final class GetGameViewApplicationService {

    /** 分页默认值与上限（api-spec.md §1：pageSize 默认 20、上限 100）。 */
    private static final int DEFAULT_PAGE_SIZE = 20;
    private static final int MAX_PAGE_SIZE = 100;

    private final GameViewQueryPort query;

    public GetGameViewApplicationService(GameViewQueryPort query) {
        this.query = Objects.requireNonNull(query, "query");
    }

    /** 角色可见的对局视图；{@code viewer} 决定 {@code me} 与 {@code players[].role}。 */
    public GameViewSnapshot view(String gameId, ViewerContext viewer) {
        return query.view(gameId, viewer);
    }

    /** 事件回放区间；读取者不可见的事件不会出现在结果里（过滤同样只有一份实现）。 */
    public List<EventEnvelope> replay(
            String gameId, ViewerContext viewer, int fromVersion, int toVersion, int maxEvents) {
        return query.replay(gameId, viewer, fromVersion, toVersion, maxEvents);
    }

    /** 对局列表；分页参数按 api-spec.md §1 归一化，越界取值收敛到合法区间而不是报错。 */
    public List<Map<String, Object>> listGames(String status, String roomId, int page, int pageSize) {
        int safePage = Math.max(page, 1);
        int safePageSize = pageSize <= 0 ? DEFAULT_PAGE_SIZE : Math.min(pageSize, MAX_PAGE_SIZE);
        return query.listGames(status, roomId, safePage, safePageSize);
    }
}
