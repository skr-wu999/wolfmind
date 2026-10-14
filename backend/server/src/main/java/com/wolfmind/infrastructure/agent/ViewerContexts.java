package com.wolfmind.infrastructure.agent;

import com.wolfmind.application.agent.model.AgentView;
import com.wolfmind.domain.game.model.PlayerId;
import com.wolfmind.domain.game.model.ViewerContext;
import com.wolfmind.domain.game.view.GameViewSnapshot;

import java.util.LinkedHashSet;
import java.util.Set;

/**
 * 读取者上下文的唯一构造点。
 *
 * <p><b>这是本包唯一引用 {@code domain.game.model} 的两个文件之一</b>（另一个是
 * {@link DomainRejectionTranslator}）。为什么无法完全避免：{@code ViewerContext} 与
 * {@code PlayerId} 就是 {@code GameViewQueryPort} 的入参类型（"谁在读"），端口签名冻结，
 * 不构造它们就无法读取视图。两个类型都是<b>读取者身份</b>，不含任何规则信息：
 * 角色、阶段、目标、规则参数一律经 {@code AgentView} / 字符串流转（DEP-7、C-5）。
 * {@code AgentViewAdapterTest} 里有一条边界测试守着这个白名单，防止它扩大。
 */
public final class ViewerContexts {

    private ViewerContexts() {}

    /**
     * 第一步：只声明"我是这个座位"。
     *
     * <p>此时还不知道自己是不是狼人，因此 {@code wolfPlayerIds} 为空——投影会把自己的角色
     * 给出（本人角色永远可见），但会遮住队友。
     */
    public static ViewerContext identity(String playerId) {
        return ViewerContext.participant(playerId, Set.of(PlayerId.of(playerId)), Set.of(), false);
    }

    /**
     * 第二步：按已有投影补上狼队可见性。
     *
     * <p>{@code wolfPlayerIds} 取自<b>投影已经给出的</b> {@code WEREWOLF} 角色：投影对非队友
     * 已经把 {@code role} 置空，因此这里拿到的集合天然等于"我（若是狼）+ 我能看到的队友"。
     * 这正是 {@code InMemoryGameViewQuery.contextFor} 的做法，我没有再写第二份判定（AP-6）。
     *
     * <p>座位不在本局时退化为"只有身份、没有狼队"，投影给出的 {@code me} 为 {@code null}，
     * 调用方据此判定身份不成立。
     */
    public static ViewerContext forSeat(GameViewSnapshot snapshot, String playerId) {
        if (snapshot == null) {
            return identity(playerId);
        }
        GameViewSnapshot.Participant self = snapshot.players().stream()
                .filter(participant -> playerId.equals(participant.playerId().value()))
                .findFirst()
                .orElse(null);
        if (self == null) {
            return identity(playerId);
        }
        Set<PlayerId> wolves = new LinkedHashSet<>();
        for (GameViewSnapshot.Participant participant : snapshot.players()) {
            if (participant.role() != null && "WEREWOLF".equals(participant.role().name())) {
                wolves.add(participant.playerId());
            }
        }
        return ViewerContext.participant(playerId, Set.of(self.playerId()), wolves, false);
    }

    /**
     * 从 Agent 形状的视图构造读取者上下文（{@code QueryHistoryTool} 用）。
     *
     * <p>狼队信息同样只取"投影已经给出的" {@code WEREWOLF} 角色：狼人的视图里有队友，
     * 村民的视图里没有，因此这里不会给村民多一分可见性。赛后（{@code revealed}）由
     * {@code GameViewQueryPort} 内部按状态放开，不需要在这里判断。
     */
    public static ViewerContext forAgentView(AgentView view, String playerId) {
        Set<PlayerId> wolves = new LinkedHashSet<>();
        if (view != null) {
            if (view.me() != null && "WEREWOLF".equals(view.me().role())) {
                wolves.add(PlayerId.of(view.me().playerId()));
            }
            for (AgentView.SeatView seat : view.other()) {
                if ("WEREWOLF".equals(seat.role())) {
                    wolves.add(PlayerId.of(seat.playerId()));
                }
            }
        }
        return ViewerContext.participant(playerId, Set.of(PlayerId.of(playerId)), wolves, false);
    }

    /**
     * 内部裁判读取者：<b>只用于规则要求的定向披露</b>（女巫需要知道当夜刀口，见
     * {@code AgentViewAdapter} 的 NIGHT_KILL 说明）。调用方必须只从中取那一条事实，
     * 不得把它当作"顺便看看全部事件"的入口——那是 AP-6 与作弊（tool-gateway.md §13）。
     */
    static ViewerContext nightKillReader() {
        // 此可信上下文不进入 REST/WS 或模型；调用方只返回当夜刀口这一条规则事实。
        return new ViewerContext("SYSTEM", Set.of(), Set.of(), false, true);
    }
}
