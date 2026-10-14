package com.wolfmind.architecture;

import com.wolfmind.domain.game.event.DeathAnnouncedEvent;
import com.wolfmind.domain.game.event.EventMeta;
import com.wolfmind.domain.game.event.GameAbortedEvent;
import com.wolfmind.domain.game.event.GameFinishedEvent;
import com.wolfmind.domain.game.event.GameRecoveredEvent;
import com.wolfmind.domain.game.event.GameStartedEvent;
import com.wolfmind.domain.game.event.HunterShotEvent;
import com.wolfmind.domain.game.event.HunterSkippedEvent;
import com.wolfmind.domain.game.event.PhaseChangedEvent;
import com.wolfmind.domain.game.event.PlayerControllerChangedEvent;
import com.wolfmind.domain.game.event.PlayerKilledEvent;
import com.wolfmind.domain.game.event.PlayerSpokeEvent;
import com.wolfmind.domain.game.event.PlayerVotedEvent;
import com.wolfmind.domain.game.event.RoleAssignedEvent;
import com.wolfmind.domain.game.event.SeerInspectedEvent;
import com.wolfmind.domain.game.event.SeerSkippedEvent;
import com.wolfmind.domain.game.event.SpeechSkippedEvent;
import com.wolfmind.domain.game.event.TurnGrantedEvent;
import com.wolfmind.domain.game.event.VoteTallyResolvedEvent;
import com.wolfmind.domain.game.event.WitchPassedEvent;
import com.wolfmind.domain.game.event.WitchPoisonedEvent;
import com.wolfmind.domain.game.event.WitchSavedEvent;
import com.wolfmind.domain.game.event.WolfChatSentEvent;
import com.wolfmind.domain.game.event.WolfKillNominatedEvent;
import com.wolfmind.domain.game.event.WolfKillSelectedEvent;
import com.wolfmind.domain.game.model.Camp;
import com.wolfmind.domain.game.model.ControllerType;
import com.wolfmind.domain.game.model.DeathCause;
import com.wolfmind.domain.game.model.GameId;
import com.wolfmind.domain.game.model.GamePhase;
import com.wolfmind.domain.game.model.GameRules;
import com.wolfmind.domain.game.model.PlayerId;
import com.wolfmind.domain.game.model.Role;
import com.wolfmind.domain.game.model.RoleComposition;
import com.wolfmind.domain.game.model.SeatAssignment;
import com.wolfmind.domain.game.model.SeatNo;
import com.wolfmind.domain.game.model.SkipReason;
import com.wolfmind.domain.game.model.TallyResult;

import java.util.List;

/**
 * 事件夹具：给事件目录一致性测试与可见性矩阵测试提供确定性的事件实例。
 *
 * <p>所有实例的参数值都不影响测试结论（测试断言 {@code type()}、{@code visibility()}、
 * {@code payload()} 键集合与 {@code targetPlayerId()}），只要求构造成功。
 * 信封字段的取值固定，不依赖时钟与随机数，因此测试可重复执行。
 */
public final class EventFixtures {

    /** 测试用对局标识。 */
    public static final String GAME_ID = "g_verify_0001";
    /** 测试用房间标识。 */
    public static final String ROOM_ID = "r_verify_0001";
    /** 固定时间戳，避免测试读取墙钟。 */
    public static final long OCCURRED_AT = 1_730_000_000_000L;

    private EventFixtures() {}

    /** 座位号 1..9 对应的玩家标识。 */
    public static PlayerId player(int seat) {
        return PlayerId.of("p_" + seat);
    }

    /** 座位号 1..9。 */
    public static SeatNo seat(int seat) {
        return SeatNo.of(seat);
    }

    /** 信封：固定 eventId / actorId / causationId / correlationId，只让 version / phase / round 变化。 */
    public static EventMeta meta(int version, GamePhase phase, int round) {
        return new EventMeta(
                "evt_" + version,
                version,
                OCCURRED_AT,
                phase,
                round,
                "SYSTEM",
                "cmd_" + version,
                "cor_" + version);
    }

    public static EventMeta meta(int version) {
        return meta(version, GamePhase.NIGHT_WOLF, 1);
    }

    /** 九个座位的开局分配（BOT 控制，避免测试依赖真人或 AI）。 */
    public static List<SeatAssignment> nineSeats() {
        return java.util.stream.IntStream.rangeClosed(1, 9)
                .mapToObj(seat -> SeatAssignment.bot(seat, "strategy_default"))
                .toList();
    }

    // ---- event-design.md §7.2 Game 聚合事件 ----

    public static GameStartedEvent gameStarted() {
        List<GameStartedEvent.SeatSpec> seats = nineSeats().stream()
                .map(GameStartedEvent.SeatSpec::of)
                .toList();
        return new GameStartedEvent(
                meta(1, GamePhase.GAME_INIT, 1),
                GameId.of(GAME_ID),
                ROOM_ID,
                seats,
                RoleComposition.STANDARD_NINE,
                GameRules.DEFAULT,
                List.of(1, 2, 3, 4, 5, 6, 7, 8, 9),
                1);
    }

    public static RoleAssignedEvent roleAssigned() {
        return new RoleAssignedEvent(meta(2), player(2), seat(2), Role.SEER, Camp.VILLAGE);
    }

    /** 指定目标玩家的 ROLE_ASSIGNED，用于逐座位断言 PRIVATE 的"仅本人可见"。 */
    public static RoleAssignedEvent roleAssignedTo(PlayerId playerId, SeatNo seatNo) {
        return new RoleAssignedEvent(meta(2), playerId, seatNo, Role.VILLAGER, Camp.VILLAGE);
    }

    public static PhaseChangedEvent phaseChanged() {
        return new PhaseChangedEvent(
                meta(3, GamePhase.NIGHT_WOLF, 1),
                GamePhase.GAME_INIT,
                GamePhase.NIGHT_WOLF,
                0,
                null,
                null);
    }

    public static TurnGrantedEvent turnGranted() {
        return new TurnGrantedEvent(
                meta(4, GamePhase.DAY_DISCUSS, 1),
                player(1),
                seat(1),
                GamePhase.DAY_DISCUSS,
                0,
                OCCURRED_AT + 30_000L,
                ControllerType.BOT);
    }

    public static PlayerSpokeEvent playerSpoke() {
        return new PlayerSpokeEvent(
                meta(5, GamePhase.DAY_DISCUSS, 1),
                player(1),
                seat(1),
                "speech_1",
                "我是村民",
                0,
                GamePhase.DAY_DISCUSS);
    }

    public static SpeechSkippedEvent speechSkipped() {
        return new SpeechSkippedEvent(meta(6, GamePhase.DAY_DISCUSS, 1), player(1), seat(1), SkipReason.TIMEOUT, 0);
    }

    public static WolfKillNominatedEvent wolfKillNominated() {
        return new WolfKillNominatedEvent(meta(7), player(3), seat(3), player(6), seat(6), false);
    }

    public static WolfChatSentEvent wolfChatSent() {
        return new WolfChatSentEvent(meta(7), player(3), seat(3), WolfChatSentEvent.chatIdFor(GAME_ID, 7), "刀 6 号");
    }

    /** 空刀提名：{@code targetId / targetSeatNo} 为 {@code null}（domain-model.md §7 狼人弃刀）。 */
    public static WolfKillNominatedEvent wolfKillNominatedEmpty() {
        return new WolfKillNominatedEvent(meta(7), player(3), seat(3), null, null, true);
    }

    public static WolfKillSelectedEvent wolfKillSelected() {
        return new WolfKillSelectedEvent(
                meta(8),
                player(6),
                seat(6),
                List.of(new WolfKillSelectedEvent.Nomination(3, 6)));
    }

    /** 空刀定案：{@code targetId} 为 {@code null}（event-design.md §7.2 明确说明）。 */
    public static WolfKillSelectedEvent wolfKillSelectedEmpty() {
        return new WolfKillSelectedEvent(
                meta(8),
                null,
                null,
                List.of(new WolfKillSelectedEvent.Nomination(3, null)));
    }

    public static SeerInspectedEvent seerInspected() {
        return new SeerInspectedEvent(meta(9, GamePhase.NIGHT_SEER, 1), player(2), seat(2), player(6), seat(6), Camp.WOLF);
    }

    public static SeerSkippedEvent seerSkipped() {
        return new SeerSkippedEvent(meta(10, GamePhase.NIGHT_SEER, 1), player(2), seat(2), SkipReason.VOLUNTARY);
    }

    public static WitchSavedEvent witchSaved() {
        return new WitchSavedEvent(meta(11, GamePhase.NIGHT_WITCH, 1), player(4), seat(4), player(6), seat(6));
    }

    public static WitchPoisonedEvent witchPoisoned() {
        return new WitchPoisonedEvent(meta(12, GamePhase.NIGHT_WITCH, 1), player(4), seat(4), player(7), seat(7));
    }

    public static WitchPassedEvent witchPassed() {
        return new WitchPassedEvent(meta(13, GamePhase.NIGHT_WITCH, 1), player(4), seat(4), SkipReason.VOLUNTARY);
    }

    public static HunterShotEvent hunterShot() {
        return new HunterShotEvent(meta(14, GamePhase.HUNTER_SHOOT, 1), player(3), seat(3), player(8), seat(8));
    }

    public static HunterSkippedEvent hunterSkipped() {
        return new HunterSkippedEvent(meta(15, GamePhase.HUNTER_SHOOT, 1), player(3), seat(3), SkipReason.TIMEOUT);
    }

    public static PlayerVotedEvent playerVoted() {
        return new PlayerVotedEvent(meta(16, GamePhase.DAY_VOTE, 1), player(1), seat(1), player(6), seat(6), false);
    }

    /** 弃权票：INV-10 要求 {@code abstain = true} 且 {@code targetSeat = null}（ddd-design.md §4.1）。 */
    public static PlayerVotedEvent playerVotedAbstain() {
        return new PlayerVotedEvent(meta(16, GamePhase.DAY_VOTE, 1), player(1), seat(1), null, null, true);
    }

    public static VoteTallyResolvedEvent voteTallyResolved() {
        return new VoteTallyResolvedEvent(
                meta(17, GamePhase.VOTE_SETTLE, 1),
                TallyResult.Result.EXILE,
                List.of(new VoteTallyResolvedEvent.TallyEntry(6, 3)),
                3,
                player(6),
                List.of());
    }

    public static PlayerKilledEvent playerKilled() {
        return new PlayerKilledEvent(
                meta(18, GamePhase.NIGHT_SETTLE, 1),
                player(6),
                seat(6),
                List.of(DeathCause.WOLF_KILL),
                DeathCause.WOLF_KILL);
    }

    public static DeathAnnouncedEvent deathAnnounced() {
        return new DeathAnnouncedEvent(
                meta(19, GamePhase.DAY_ANNOUNCE, 1),
                List.of(new DeathAnnouncedEvent.DeathEntry(player(6), seat(6))));
    }

    /** 平安夜：{@code deaths} 为空数组，事件仍然产生（event-design.md §7.2）。 */
    public static DeathAnnouncedEvent deathAnnouncedPeacefulNight() {
        return new DeathAnnouncedEvent(meta(19, GamePhase.DAY_ANNOUNCE, 1), List.of());
    }

    public static GameFinishedEvent gameFinished() {
        return new GameFinishedEvent(
                meta(20, GamePhase.GAME_OVER, 2),
                Camp.VILLAGE,
                GameFinishedEvent.REASON_ALL_WOLVES_ELIMINATED,
                2,
                List.of(seat(1), seat(2)),
                List.of());
    }

    public static GameAbortedEvent gameAborted() {
        return new GameAbortedEvent(meta(21, GamePhase.NIGHT_WOLF, 1), "NOT_ENOUGH_PLAYERS", 20);
    }

    public static GameRecoveredEvent gameRecovered() {
        return new GameRecoveredEvent(meta(22, GamePhase.NIGHT_WOLF, 1), 20, 22, "SNAPSHOT");
    }

    public static PlayerControllerChangedEvent playerControllerChanged() {
        return new PlayerControllerChangedEvent(
                meta(23),
                player(1),
                seat(1),
                ControllerType.HUMAN,
                "u_1",
                ControllerType.AI,
                "agent_1");
    }
}
