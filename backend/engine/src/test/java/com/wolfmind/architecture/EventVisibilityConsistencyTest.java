package com.wolfmind.architecture;

import com.wolfmind.domain.game.event.DomainEvent;
import com.wolfmind.domain.game.event.EventEnvelope;
import com.wolfmind.domain.game.model.PlayerId;
import com.wolfmind.domain.game.model.ViewerContext;
import com.wolfmind.domain.game.model.Visibility;
import com.wolfmind.domain.game.view.VisibilityFilter;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 两条可见性入口必须给出同一答案（event-design.md §4 硬规则 2、反模式 AP-6）。
 *
 * <p>§4 硬规则 2 原文："{@code visibility} 是事件的属性，判定逻辑只有一份（{@code VisibilityFilter}），
 * WebSocket 推送、{@code QueryGameStateTool}、{@code QueryHistoryTool}、回放接口全部复用它。任何地方出现
 * '另外写一份过滤逻辑'都是缺陷。"
 *
 * <p>实现里对应两个入口：
 * <ul>
 *   <li>{@link ViewerContext#canSee(DomainEvent)}——按事件对象的 {@code targetPlayerId()} 判定（领域内路径）；</li>
 *   <li>{@link VisibilityFilter#visible(EventEnvelope, ViewerContext)}——按信封的 {@code visibility} 与
 *       payload 里的本人标识判定（持久化/推送路径）。</li>
 * </ul>
 * 两者必须是同一份语义。信封不知道自己对应的领域事件，所以它从 payload 的
 * {@code playerId} / {@code seerId} / {@code witchId} 推断 PRIVATE 的本人；本测试对**每一个**文档登记的
 * Game 事件、每一种读取者逐格比对，任何新增的 PRIVATE 事件漏写这些键都会在这里失败。
 */
class EventVisibilityConsistencyTest {

    private static final Set<PlayerId> WOLF_SEATS = Set.of(
            EventFixtures.player(3), EventFixtures.player(6), EventFixtures.player(7));

    /** 覆盖 event-design.md §4 矩阵的每一行，外加几个"本人/非本人"的具体座位。 */
    private static Map<String, ViewerContext> viewers() {
        Map<String, ViewerContext> viewers = new LinkedHashMap<>();
        viewers.put("匿名", ViewerContext.anonymous());
        viewers.put("观战者", ViewerContext.spectator("u_spectator"));
        viewers.put("参与者-村民(座位1)", ViewerContext.participant(
                "u_1", Set.of(EventFixtures.player(1)), WOLF_SEATS, false));
        viewers.put("参与者-预言家(座位2)", ViewerContext.participant(
                "u_2", Set.of(EventFixtures.player(2)), WOLF_SEATS, false));
        viewers.put("参与者-女巫(座位4)", ViewerContext.participant(
                "u_4", Set.of(EventFixtures.player(4)), WOLF_SEATS, false));
        viewers.put("参与者-狼人(座位6)", ViewerContext.participant(
                "u_6", Set.of(EventFixtures.player(6)), WOLF_SEATS, false));
        viewers.put("赛后参与者(座位1)", ViewerContext.participant(
                "u_1", Set.of(EventFixtures.player(1)), WOLF_SEATS, true));
        return viewers;
    }

    /** 文档登记的 23 个 Game 事件实例（与 EventCatalogTest 的夹具同源）。 */
    private static List<DomainEvent> events() {
        List<DomainEvent> events = new ArrayList<>();
        events.add(EventFixtures.gameStarted());
        events.add(EventFixtures.roleAssigned());
        events.add(EventFixtures.phaseChanged());
        events.add(EventFixtures.turnGranted());
        events.add(EventFixtures.wolfKillNominated());
        events.add(EventFixtures.wolfKillNominatedEmpty());
        events.add(EventFixtures.wolfKillSelected());
        events.add(EventFixtures.wolfKillSelectedEmpty());
        events.add(EventFixtures.seerInspected());
        events.add(EventFixtures.seerSkipped());
        events.add(EventFixtures.witchSaved());
        events.add(EventFixtures.witchPoisoned());
        events.add(EventFixtures.witchPassed());
        events.add(EventFixtures.hunterShot());
        events.add(EventFixtures.hunterSkipped());
        events.add(EventFixtures.playerSpoke());
        events.add(EventFixtures.speechSkipped());
        events.add(EventFixtures.playerVoted());
        events.add(EventFixtures.playerVotedAbstain());
        events.add(EventFixtures.voteTallyResolved());
        events.add(EventFixtures.playerKilled());
        events.add(EventFixtures.deathAnnounced());
        events.add(EventFixtures.deathAnnouncedPeacefulNight());
        events.add(EventFixtures.gameFinished());
        events.add(EventFixtures.gameAborted());
        events.add(EventFixtures.gameRecovered());
        events.add(EventFixtures.playerControllerChanged());
        return events;
    }

    @Test
    @DisplayName("事件级与信封级可见性判定对每个事件、每种读取者都一致")
    void bothVisibilityEntryPointsAgree() {
        List<String> mismatches = new ArrayList<>();
        Map<String, ViewerContext> viewers = viewers();

        for (DomainEvent event : events()) {
            EventEnvelope envelope = EventEnvelope.of(event, EventFixtures.GAME_ID, event.version());
            for (Map.Entry<String, ViewerContext> viewer : viewers.entrySet()) {
                boolean byEvent = viewer.getValue().canSee(event);
                boolean byEnvelope = VisibilityFilter.visible(envelope, viewer.getValue());
                if (byEvent != byEnvelope) {
                    mismatches.add(event.type() + " / " + viewer.getKey()
                            + "：事件级=" + byEvent + "，信封级=" + byEnvelope);
                }
            }
        }

        assertTrue(mismatches.isEmpty(),
                "event-design.md §4 硬规则 2（判定只有一份实现）与 AP-6：事件级与信封级必须给出同一答案；不一致："
                        + mismatches);
    }

    @Test
    @DisplayName("信封级过滤必须能识别每一个 PRIVATE 事件的本人")
    void envelopeFilterKnowsPrivateOwnerForEveryPrivateEvent() {
        List<String> unrecognized = new ArrayList<>();
        ViewerContext anonymous = ViewerContext.anonymous();

        for (DomainEvent event : events()) {
            if (event.visibility() != com.wolfmind.domain.game.model.Visibility.PRIVATE) {
                continue;
            }
            EventEnvelope envelope = EventEnvelope.of(event, EventFixtures.GAME_ID, event.version());
            // 匿名读取者永远看不到 PRIVATE；这里真正要验的是"本人能看到"：
            // 缺少 playerId/seerId/witchId 时 VisibilityFilter 会把 PRIVATE 判给任何人（或没人），
            // 从而与事件的 targetPlayerId() 分叉。
            PlayerId owner = event.targetPlayerId();
            if (owner == null) {
                unrecognized.add(event.type() + "：PRIVATE 事件没有 targetPlayerId()");
                continue;
            }
            ViewerContext self = ViewerContext.participant("u_self", Set.of(owner), WOLF_SEATS, false);
            if (!VisibilityFilter.visible(envelope, self)) {
                unrecognized.add(event.type() + "：本人 " + owner.value()
                        + " 读不到自己的 PRIVATE 事件（信封缺少 playerId/seerId/witchId）");
            }
            if (VisibilityFilter.visible(envelope, anonymous)) {
                unrecognized.add(event.type() + "：匿名读取者不应看到 PRIVATE 事件");
            }
        }

        assertTrue(unrecognized.isEmpty(),
                "event-design.md §4：PRIVATE 事件的信封必须能被判定出本人（payload 的 playerId/seerId/witchId）；"
                        + "问题：" + unrecognized);
    }

    @Test
    @DisplayName("进行中观战仅公开事件，赛后完整披露；领域与信封过滤一致")
    void publicSpectatorAndRevealedViewsAgree() {
        ViewerContext spectator = ViewerContext.spectator("u_spec");
        ViewerContext revealed = ViewerContext.participant(
                "u_1", Set.of(EventFixtures.player(1)), Set.of(), true);

        for (DomainEvent event : events()) {
            boolean publicEvent = event.visibility() == Visibility.PUBLIC;
            assertEquals(publicEvent, spectator.canSee(event), event.type().name());
            assertTrue(revealed.canSee(event),
                    "event-design.md §4 矩阵：赛后回放（任意登录用户）可见全部事件，例外 " + event.type());
            EventEnvelope envelope = EventEnvelope.of(event, EventFixtures.GAME_ID, event.version());
            assertEquals(publicEvent, VisibilityFilter.visible(envelope, spectator), event.type().name());
            assertEquals(true, VisibilityFilter.visible(envelope, revealed),
                    "event-design.md §4：信封级过滤对赛后读取者必须全放行，例外 " + event.type());
        }
    }
}
