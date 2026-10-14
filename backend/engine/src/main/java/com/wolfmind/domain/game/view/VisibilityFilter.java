package com.wolfmind.domain.game.view;

import com.wolfmind.domain.game.event.DomainEvent;
import com.wolfmind.domain.game.event.EventEnvelope;
import com.wolfmind.domain.game.model.GameStatus;
import com.wolfmind.domain.game.model.ViewerContext;
import com.wolfmind.domain.game.model.Visibility;

import java.util.List;

/**
 * 可见性过滤：判断"这条事件能不能给这个读取者看"（event-design.md §4）。
 *
 * <p>判定逻辑只有 {@link ViewerContext#canSee(DomainEvent)} 一份实现，本类只是把它适配到
 * 信封形态并批量过滤。WebSocket 推送、REST 回放、AI 的 {@code QueryGameStateTool} 与
 * {@code QueryHistoryTool} 全部复用这里，**任何地方另写一份过滤逻辑都是缺陷**（AP-6）。
 */
public final class VisibilityFilter {

    private VisibilityFilter() {}

    /** 单条事件是否可见。 */
    public static boolean visible(DomainEvent event, ViewerContext viewer) {
        return viewer.canSee(event);
    }

    /**
     * 单条信封是否可见。
     *
     * <p>信封不知道自己对应的领域事件是否 {@code PRIVATE} 指向谁，因此这里按"可见性分类 +
     * 载荷中的 {@code playerId} 线索"判定：{@code PRIVATE} 事件的载荷总是带
     * {@code playerId} / {@code seerId} / {@code witchId} 这类"本人"标识，判定取其中之一。
     * 这条规则与 {@code DomainEvent.targetPlayerId()} 保持一致，并有一致性测试守着。
     */
    public static boolean visible(EventEnvelope envelope, ViewerContext viewer) {
        if (viewer.revealed()) {
            return true;
        }
        if (viewer.spectator()) return envelope.visibility() == Visibility.PUBLIC;
        Visibility visibility = envelope.visibility();
        return switch (visibility) {
            case PUBLIC -> true;
            case WOLF -> isWolfViewer(viewer);
            case PRIVATE -> {
                String owner = privateOwnerOf(envelope);
                yield owner != null && viewer.participantPlayerIds().stream()
                        .anyMatch(playerId -> playerId.value().equals(owner));
            }
            case JUDGE -> false;
        };
    }

    /** 按读取者身份与赛后披露状态裁剪事件。 */
    public static List<EventEnvelope> filter(List<EventEnvelope> envelopes, ViewerContext viewer) {
        return envelopes.stream().filter(envelope -> visible(envelope, viewer)).toList();
    }

    /** 便于应用层在已知对局状态时直接判定"赛后公开"。 */
    public static ViewerContext withReveal(ViewerContext viewer, GameStatus status) {
        if (status == GameStatus.FINISHED || status == GameStatus.ABORTED) {
            return new ViewerContext(
                    viewer.viewerUserId(),
                    viewer.participantPlayerIds(),
                    viewer.wolfPlayerIds(),
                    viewer.spectator(),
                    true);
        }
        return viewer;
    }

    private static boolean isWolfViewer(ViewerContext viewer) {
        return viewer.participantPlayerIds().stream().anyMatch(viewer.wolfPlayerIds()::contains);
    }

    /**
     * {@code PRIVATE} 事件的"本人"标识。
     *
     * <p>顺序即优先级：{@code playerId} 是统一语言里的本人字段，其余是各事件的行动者字段。
     */
    private static String privateOwnerOf(EventEnvelope envelope) {
        String[] ownerKeys = {"playerId", "seerId", "witchId"};
        for (String key : ownerKeys) {
            Object value = envelope.payload().get(key);
            if (value != null) {
                return String.valueOf(value);
            }
        }
        return null;
    }
}
