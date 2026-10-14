package com.wolfmind.domain.game.model;

import com.wolfmind.domain.game.event.DomainEvent;

import java.util.Set;

/**
 * 读取者身份：可见性判定的唯一输入（event-design.md §4）。
 *
 * <p>可见性矩阵只有一份实现（{@code VisibilityFilter}），WebSocket 推送、REST 视图、
 * {@code QueryGameStateTool}、{@code QueryHistoryTool}、回放接口全部复用它。任何地方出现
 * "另外写一份过滤逻辑"都是缺陷（AP-6）。
 *
 * @param viewerUserId   读取者的账号 id；匿名读取（赛前观战未登录）为 {@code null}
 * @param participantPlayerIds 读取者在本局控制的全部座位（同一个账号可能同时控制多个座位）
 * @param wolfPlayerIds  本局狼人阵营的全部座位；仅当读取者是参与者且为狼人时才允许填充
 * @param spectator      是否以观战者身份读取（未参与本局）
 * @param revealed       是否已赛后公开：{@code GAME_FINISHED} / {@code GAME_ABORTED} 后为 {@code true}
 */
public record ViewerContext(
        String viewerUserId,
        Set<PlayerId> participantPlayerIds,
        Set<PlayerId> wolfPlayerIds,
        boolean spectator,
        boolean revealed) {

    public ViewerContext {
        participantPlayerIds = participantPlayerIds == null ? Set.of() : Set.copyOf(participantPlayerIds);
        wolfPlayerIds = wolfPlayerIds == null ? Set.of() : Set.copyOf(wolfPlayerIds);
    }

    /** 未登录的旁观读取者（只能看 {@code PUBLIC}）。 */
    public static ViewerContext anonymous() {
        return new ViewerContext(null, Set.of(), Set.of(), false, false);
    }

    /** 观战者：进行中仅公开记录，赛后由 revealed 开放完整记录。 */
    public static ViewerContext spectator(String viewerUserId) {
        return new ViewerContext(viewerUserId, Set.of(), Set.of(), true, false);
    }

    /** 参与者：按角色可见性读取。 */
    public static ViewerContext participant(
            String viewerUserId, Set<PlayerId> myPlayerIds, Set<PlayerId> wolfPlayerIds, boolean revealed) {
        return new ViewerContext(viewerUserId, myPlayerIds, wolfPlayerIds, false, revealed);
    }

    public boolean isParticipant() {
        return !participantPlayerIds.isEmpty();
    }

    /**
     * 本读取者是否可见该事件（event-design.md §4 矩阵）。
     *
     * <p>规则：
     * <ol>
     *   <li>赛后（{@code revealed}）：全部可见；</li>
     *   <li>{@code PUBLIC}：全部可见；</li>
     *   <li>{@code WOLF}：仅当读取者控制狼人座位；</li>
     *   <li>{@code PRIVATE}：仅当读取者控制了该事件的目标玩家；</li>
     *   <li>{@code JUDGE}：参与者与匿名读取者不可见。</li>
     * </ol>
     */
    public boolean canSee(DomainEvent event) {
        if (revealed) {
            return true;
        }
        if (spectator) return event.visibility() == Visibility.PUBLIC;
        return switch (event.visibility()) {
            case PUBLIC -> true;
            case WOLF -> participantPlayerIds.stream().anyMatch(wolfPlayerIds::contains);
            case PRIVATE -> {
                PlayerId target = event.targetPlayerId();
                yield target != null && participantPlayerIds.contains(target);
            }
            case JUDGE -> false;
        };
    }
}
