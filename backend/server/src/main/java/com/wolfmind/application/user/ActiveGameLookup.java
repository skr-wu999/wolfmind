package com.wolfmind.application.user;

import com.wolfmind.application.port.GameViewQueryPort;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * 按账号找进行中的对局，供 {@code GET /auth/me} 的 {@code activeGameId}（api-spec.md §3.3）。
 *
 * <p>参与关系只按服务端的座位控制者反查（{@link GameViewQueryPort#viewerContextFor}），
 * 与对局视图的可见性判定是同一份规则；不读房间成员或客户端声明。
 */
@FunctionalInterface
public interface ActiveGameLookup {

    ActiveGameLookup NONE = userId -> Optional.empty();

    Optional<String> activeGameOf(String userId);

    /**
     * 扫描进行中的对局，返回该账号控制真人座位的第一局。
     *
     * <p>同一账号同时坐在多局里时只返回一局：契约字段是单值，前端只用它显示"继续对局"入口。
     */
    static ActiveGameLookup fromRunningGames(GameViewQueryPort views) {
        Objects.requireNonNull(views, "views");
        int pageSize = 100;
        return userId -> {
            if (userId == null || userId.isBlank()) {
                return Optional.empty();
            }
            for (int page = 1; ; page++) {
                List<Map<String, Object>> games = views.listGames("RUNNING", null, page, pageSize);
                for (Map<String, Object> game : games) {
                    String gameId = String.valueOf(game.get("gameId"));
                    if (views.viewerContextFor(gameId, userId).isParticipant()) {
                        return Optional.of(gameId);
                    }
                }
                if (games.size() < pageSize) {
                    return Optional.empty();
                }
            }
        };
    }
}
