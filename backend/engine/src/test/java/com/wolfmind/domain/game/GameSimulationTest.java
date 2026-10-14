package com.wolfmind.domain.game;

import com.wolfmind.domain.game.event.DeathAnnouncedEvent;
import com.wolfmind.domain.game.event.DomainEvent;
import com.wolfmind.domain.game.event.EventCodec;
import com.wolfmind.domain.game.event.EventEnvelope;
import com.wolfmind.domain.game.event.EventType;
import com.wolfmind.domain.game.event.GameAbortedEvent;
import com.wolfmind.domain.game.event.GameFinishedEvent;
import com.wolfmind.domain.game.event.GameStartedEvent;
import com.wolfmind.domain.game.event.HunterShotEvent;
import com.wolfmind.domain.game.event.HunterSkippedEvent;
import com.wolfmind.domain.game.event.PhaseChangedEvent;
import com.wolfmind.domain.game.event.PlayerKilledEvent;
import com.wolfmind.domain.game.event.RoleAssignedEvent;
import com.wolfmind.domain.game.event.VoteTallyResolvedEvent;
import com.wolfmind.domain.game.event.WolfKillSelectedEvent;
import com.wolfmind.domain.game.model.Action;
import com.wolfmind.domain.game.model.ActionContext;
import com.wolfmind.domain.game.model.Camp;
import com.wolfmind.domain.game.model.DeathCause;
import com.wolfmind.domain.game.model.DeathRecord;
import com.wolfmind.domain.game.model.DomainErrorCode;
import com.wolfmind.domain.game.model.Game;
import com.wolfmind.domain.game.model.GameException;
import com.wolfmind.domain.game.model.GameId;
import com.wolfmind.domain.game.model.GamePhase;
import com.wolfmind.domain.game.model.GameRules;
import com.wolfmind.domain.game.model.GameSnapshot;
import com.wolfmind.domain.game.model.GameStatus;
import com.wolfmind.domain.game.model.HunterShotAction;
import com.wolfmind.domain.game.model.PlayerController;
import com.wolfmind.domain.game.model.PlayerId;
import com.wolfmind.domain.game.model.PlayerSnapshot;
import com.wolfmind.domain.game.model.Role;
import com.wolfmind.domain.game.model.RoleComposition;
import com.wolfmind.domain.game.model.SeatAssignment;
import com.wolfmind.domain.game.model.SeatNo;
import com.wolfmind.domain.game.model.SeerInspectAction;
import com.wolfmind.domain.game.model.SkillState;
import com.wolfmind.domain.game.model.SkipAction;
import com.wolfmind.domain.game.model.SkipReason;
import com.wolfmind.domain.game.model.SpeechAction;
import com.wolfmind.domain.game.model.StartGameCommand;
import com.wolfmind.domain.game.model.TallyResult;
import com.wolfmind.domain.game.model.Turn;
import com.wolfmind.domain.game.model.VoteAction;
import com.wolfmind.domain.game.model.ViewerContext;
import com.wolfmind.domain.game.model.WitchAction;
import com.wolfmind.domain.game.model.WinCondition;
import com.wolfmind.domain.game.model.WitchMode;
import com.wolfmind.domain.game.model.WolfKillAction;
import com.wolfmind.domain.game.model.WolfKillTieBreak;
import com.wolfmind.domain.game.service.NightResolutionPolicy;
import com.wolfmind.domain.game.service.WinConditionPolicy;
import com.wolfmind.domain.game.service.WolfKillPolicy;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiFunction;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 九人局确定性模拟与边界用例。
 *
 * <p>用文档而不是实现来写期望：
 * <ul>
 *   <li>开局事件序列、版本连续性：domain-model.md §11、ddd-design.md §4.2 INV-11；</li>
 *   <li>阶段跳转与插入阶段：ddd-design.md §3.2 转换表；</li>
 *   <li>终局后拒绝一切行动：ddd-design.md §4.1 INV-12；</li>
 *   <li>重放确定性与乱序拒绝：ddd-design.md §4.2 INV-16、event-design.md §11、domain-model.md §11；</li>
 *   <li>计票边界（全员弃权 / 平票）：domain-model.md §6.2、event-design.md §7.2；</li>
 *   <li>女巫同夜双药、被毒猎人不开枪：ddd-design.md §4.1 INV-7 / INV-8、domain-model.md §6.3；</li>
 *   <li>胜负判定顺序：domain-model.md §6.2、§11；</li>
 *   <li>狼刀平票取最小座位：domain-model.md §3、§6.2、§11；</li>
 *   <li>越权提交被拒且零事件：domain-model.md §6.1 V2/V4/V5、§11。</li>
 * </ul>
 *
 * <p>驱动策略是确定性的（最小座位优先、无随机、无墙钟），因此同一份代码每次跑出同一条事件流。
 */
class GameSimulationTest {

    private static final GameId GAME_ID = GameId.of("g_sim_0001");
    private static final String ROOM_ID = "room_sim_0001";
    private static final long BASE_TIMESTAMP = 1_730_000_000_000L;
    private static final long SEED = 20_240_501L;
    private static final int MAX_STEPS = 2_000;

    // ------------------------------------------------------------------
    // 确定性驱动
    // ------------------------------------------------------------------

    /** 女巫本夜的决定。 */
    private record WitchPlan(WitchMode mode, Integer target) {

        static WitchPlan pass() {
            return new WitchPlan(WitchMode.PASS, null);
        }

        static WitchPlan save(int target) {
            return new WitchPlan(WitchMode.SAVE, target);
        }

        static WitchPlan poison(int target) {
            return new WitchPlan(WitchMode.POISON, target);
        }
    }

    /**
     * 模拟驱动器：每次提交一个合法行动，直到对局停在"等待行动"的阶段。
     *
     * <p>不做任何规则判断，只做文档允许的 {@code handle} 调用；被拒绝的行动会让测试直接失败，
     * 因为那意味着驱动器或实现有问题。
     */
    private static final class Driver {

        final Game game = Game.create(GAME_ID);
        final List<DomainEvent> stream = new ArrayList<>();

        private long now = BASE_TIMESTAMP;
        private int sequence;

        /** 狼刀目标；{@code null} 表示空刀。 */
        Function<GameSnapshot, Integer> wolfPicker = GameSimulationTest::lowestAliveNonWolf;
        /** 查验目标。 */
        BiFunction<GameSnapshot, SeatNo, SeatNo> seerPicker = GameSimulationTest::lowestAliveOther;
        /** 女巫决定；默认不用药。 */
        BiFunction<GameSnapshot, SeatNo, WitchPlan> witchPicker = (state, witch) -> WitchPlan.pass();
        /** 投票目标；{@code null} 表示弃权。 */
        BiFunction<GameSnapshot, SeatNo, Integer> votePicker = GameSimulationTest::lowestAliveOtherSeat;
        /** 开枪目标；{@code null} 表示放弃开枪。 */
        BiFunction<GameSnapshot, SeatNo, Integer> hunterPicker = GameSimulationTest::lowestAliveOtherSeat;
        /** 遗言阶段改用 SkipAction（超时兜底）而不是发言：用于验证兜底路径同样可重放。 */
        boolean skipLastWords;
        GameRules rules = GameRules.DEFAULT;

        ActionContext context() {
            now += 1000L;
            return ActionContext.forTest("cmd_" + (++sequence), now, "SYSTEM");
        }

        void start() {
            StartGameCommand command = new StartGameCommand(
                    GAME_ID, ROOM_ID, nineBotSeats(), RoleComposition.STANDARD_NINE, rules);
            stream.addAll(game.start(command, context().withRandomSeed(SEED)));
        }

        GameSnapshot state() {
            return game.snapshot();
        }

        private void submit(Action action, SeatNo actor) {
            stream.addAll(game.handle(action, context().withActor(
                    actor == null || action instanceof SkipAction skip && skip.reason() == SkipReason.TIMEOUT
                            ? "SYSTEM" : "p_" + actor.value())));
        }

        /** 提交一步行动；阶段必须处于"等待行动"的状态。 */
        void step() {
            GameSnapshot state = state();
            switch (state.phase()) {
                case NIGHT_WOLF -> {
                    List<SeatNo> pending = game.wolvesAwaitingNomination();
                    require(!pending.isEmpty(), "NIGHT_WOLF 阶段没有待提名的存活狼人，状态机没有推进");
                    SeatNo wolf = pending.get(0);
                    Integer target = wolfPicker.apply(state);
                    submit(target == null
                            ? WolfKillAction.empty(wolf.value())
                            : WolfKillAction.at(wolf.value(), target), wolf);
                }
                case NIGHT_SEER -> {
                    SeatNo seer = holderOf(state, Role.SEER);
                    SeatNo target = seerPicker.apply(state, seer);
                    submit(SeerInspectAction.of(seer.value(), target.value()), seer);
                }
                case NIGHT_WITCH -> {
                    SeatNo witch = holderOf(state, Role.WITCH);
                    WitchPlan plan = witchPicker.apply(state, witch);
                    Action action = switch (plan.mode()) {
                        case SAVE -> WitchAction.save(witch.value(), plan.target());
                        case POISON -> WitchAction.poison(witch.value(), plan.target());
                        case PASS -> WitchAction.pass(witch.value());
                    };
                    submit(action, witch);
                }
                case LAST_WORDS, DAY_DISCUSS -> {
                    Turn turn = state.currentTurn();
                    require(turn != null, state.phase() + " 阶段没有发放回合窗口，无法推进");
                    if (state.phase() == GamePhase.LAST_WORDS && skipLastWords) {
                        submit(SkipAction.timeout(turn.seatNo().value()), turn.seatNo());
                    } else {
                        submit(SpeechAction.of(turn.seatNo().value(), "验证用发言"), turn.seatNo());
                    }
                }
                case DAY_VOTE -> {
                    List<SeatNo> pending = game.seatsAwaitingVote();
                    require(!pending.isEmpty(), "DAY_VOTE 阶段没有待投票的存活玩家，状态机没有推进");
                    SeatNo voter = pending.get(0);
                    Integer target = votePicker.apply(state, voter);
                    submit(target == null
                            ? VoteAction.abstain(voter.value())
                            : VoteAction.to(voter.value(), target), voter);
                }
                case HUNTER_SHOOT -> {
                    // domain-model.md §4：HUNTER_SHOOT 阶段接受 HunterShotAction（仅该猎人），
                    // 超时则由 SkipAction 兜底；猎人此时必然已死亡（本阶段由"猎人死亡"插入）
                    SeatNo hunter = SeatNo.of(state.pendingHunter());
                    Integer target = hunterPicker.apply(state, hunter);
                    submit(target == null
                            ? HunterShotAction.giveUp(hunter.value())
                            : HunterShotAction.at(hunter.value(), target), hunter);
                }
                default -> throw new IllegalStateException(
                        "阶段 " + state.phase() + " 不接受行动，驱动器无法推进");
            }
        }

        /** 一直推进到指定阶段（或对局结束）。 */
        void driveTo(GamePhase target) {
            int steps = 0;
            while (game.status() == GameStatus.RUNNING && game.phase() != target) {
                require(++steps <= MAX_STEPS, "推进 " + MAX_STEPS + " 步仍未到达阶段 " + target);
                step();
            }
        }

        /** 一直推进到对局结束。 */
        void driveUntilFinished() {
            int steps = 0;
            while (game.status() == GameStatus.RUNNING) {
                require(++steps <= MAX_STEPS, "推进 " + MAX_STEPS + " 步仍未分出胜负");
                step();
            }
        }

        /** 跑完整个投票阶段（含结算）。 */
        void drivePastVote() {
            int steps = 0;
            while (game.phase() == GamePhase.DAY_VOTE) {
                require(++steps <= MAX_STEPS, "投票阶段推进超过 " + MAX_STEPS + " 步");
                step();
            }
        }

        private static void require(boolean condition, String message) {
            if (!condition) {
                throw new IllegalStateException(message);
            }
        }
    }

    private static List<SeatAssignment> nineBotSeats() {
        return java.util.stream.IntStream.rangeClosed(1, 9)
                .mapToObj(seat -> SeatAssignment.bot(seat, "verify_bot"))
                .toList();
    }

    // ------------------------------------------------------------------
    // 确定性策略
    // ------------------------------------------------------------------

    /** 最小存活非狼座位；全弃刀时为 {@code null}（INV-6：狼刀目标不得为狼人）。 */
    private static Integer lowestAliveNonWolf(GameSnapshot state) {
        return state.players().stream()
                .filter(PlayerSnapshot::alive)
                .filter(player -> !player.isWolf())
                .map(player -> player.seatNo().value())
                .min(Integer::compareTo)
                .orElse(null);
    }

    private static SeatNo lowestAliveOther(GameSnapshot state, SeatNo self) {
        return state.players().stream()
                .filter(PlayerSnapshot::alive)
                .filter(player -> !player.seatNo().equals(self))
                .map(PlayerSnapshot::seatNo)
                .min(Comparator.naturalOrder())
                .orElseThrow(() -> new IllegalStateException("没有其他存活玩家"));
    }

    private static Integer lowestAliveOtherSeat(GameSnapshot state, SeatNo self) {
        return lowestAliveOther(state, self).value();
    }

    /**
     * 狼刀优先打猎人（用于构造猎人开枪场景）；猎人已死时退回最小存活非狼座位。
     *
     * <p>必须保证目标存活：domain-model.md §6.1 V7 要求狼刀目标存活且非狼人（INV-6）。
     */
    private static Integer hunterFirstThenLowest(GameSnapshot state) {
        SeatNo hunter = seatOfRole(state, Role.HUNTER);
        if (state.playerAt(hunter).alive()) {
            return hunter.value();
        }
        return lowestAliveNonWolf(state);
    }

    private static List<Integer> aliveSeats(GameSnapshot state) {
        return state.players().stream()
                .filter(PlayerSnapshot::alive)
                .map(player -> player.seatNo().value())
                .sorted()
                .toList();
    }

    private static List<Integer> aliveNonWolfSeats(GameSnapshot state) {
        return state.players().stream()
                .filter(PlayerSnapshot::alive)
                .filter(player -> !player.isWolf())
                .map(player -> player.seatNo().value())
                .sorted()
                .toList();
    }

    private static SeatNo holderOf(GameSnapshot state, Role role) {
        return state.players().stream()
                .filter(PlayerSnapshot::alive)
                .filter(player -> player.role() == role)
                .map(PlayerSnapshot::seatNo)
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("没有存活的 " + role));
    }

    private static SeatNo seatOfRole(GameSnapshot state, Role role) {
        return state.players().stream()
                .filter(player -> player.role() == role)
                .map(PlayerSnapshot::seatNo)
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("对局里没有 " + role));
    }

    // ------------------------------------------------------------------
    // 断言工具
    // ------------------------------------------------------------------

    private static <T extends DomainEvent> List<T> eventsOf(List<DomainEvent> stream, Class<T> type) {
        return stream.stream().filter(type::isInstance).map(type::cast).toList();
    }

    private static List<DomainEvent> eventsByType(List<DomainEvent> stream, EventType type) {
        return stream.stream().filter(event -> event.type() == type).toList();
    }

    /**
     * 轮次时间线：所有 {@code PHASE_CHANGED} 的 (from→to, round) 序列 + {@code GAME_FINISHED.rounds}。
     *
     * <p>INV-16 失败时，这个序列是判断"哪些状态没有进事件"的第一手证据。
     */
    private static String roundTimeline(List<DomainEvent> stream) {
        StringBuilder builder = new StringBuilder();
        for (DomainEvent event : stream) {
            if (event instanceof PhaseChangedEvent change) {
                builder.append(change.from()).append("->").append(change.to())
                        .append("@round=").append(change.round()).append("; ");
            } else if (event instanceof GameFinishedEvent finished) {
                builder.append("GAME_FINISHED@rounds=").append(finished.rounds()).append("; ");
            }
        }
        return builder.toString();
    }

    private static void assertVersionStreamIsContiguous(List<DomainEvent> stream) {
        Map<Integer, Integer> seen = new LinkedHashMap<>();
        for (int index = 0; index < stream.size(); index++) {
            DomainEvent event = stream.get(index);
            assertEquals(index + 1, event.version(),
                    "ddd-design.md §4.1 INV-11、event-design.md §14.2：version 必须从 1 连续递增、无空洞、无重复；"
                            + "第 " + index + " 条事件是 " + event.type() + "@" + event.version());
            assertNull(seen.put(event.version(), index),
                    "event-design.md §14.2：同一 gameId 内 version 不得重复；重复的是 " + event.version());
        }
    }

    // ------------------------------------------------------------------
    // 1. 完整九人局
    // ------------------------------------------------------------------

    @Test
    @DisplayName("九人局跑到 GAME_FINISHED：版本连续、开局 11 条事件、终局状态符合胜负规则")
    void ninePlayerGameRunsToGameFinished() {
        Driver driver = new Driver();
        driver.start();
        driver.driveUntilFinished();

        List<DomainEvent> stream = driver.stream;
        assertVersionStreamIsContiguous(stream);

        assertEquals(GameStatus.FINISHED, driver.game.status(),
                "domain-model.md §2.3：分出胜负后状态必须是 FINISHED");
        assertEquals(GamePhase.GAME_OVER, driver.game.phase(),
                "ddd-design.md §3.2：分出胜负后进入终态 GAME_OVER");
        assertEquals(stream.size(), driver.game.version(),
                "ddd-design.md §4.1 INV-11：version 等于已产生事件数");
        assertEquals(EventType.GAME_FINISHED, stream.get(stream.size() - 1).type(),
                "ddd-design.md §3.2：GAME_OVER 是终态，GAME_FINISHED 必须是最后一条事件");

        // domain-model.md §11：开局事件序列 = GAME_STARTED + 9 条 ROLE_ASSIGNED + PHASE_CHANGED
        assertEquals(EventType.GAME_STARTED, stream.get(0).type(),
                "domain-model.md §7：开局第一条事件是 GAME_STARTED");
        assertEquals(9, eventsOf(stream, RoleAssignedEvent.class).size(),
                "domain-model.md §11：开局必须产出 9 条 ROLE_ASSIGNED（每人一条）");
        assertEquals(EventType.PHASE_CHANGED, stream.get(10).type(),
                "domain-model.md §2.2 与 §7：开局共 11 条事件，第 11 条是进入 NIGHT_WOLF 的 PHASE_CHANGED");

        GameStartedEvent started = eventsOf(stream, GameStartedEvent.class).get(0);
        assertEquals(9, started.seats().size(), "ddd-design.md §4.1 INV-1：标准局座位数恰好为 9");
        assertEquals(1, started.startedRound(), "domain-model.md §2.1：轮次从 1 开始");

        // domain-model.md §11：洗牌后狼人恰好 3 名，神职各 1 名
        Map<Role, Integer> composition = new LinkedHashMap<>();
        for (RoleAssignedEvent assigned : eventsOf(stream, RoleAssignedEvent.class)) {
            composition.merge(assigned.role(), 1, Integer::sum);
        }
        assertEquals(3, composition.getOrDefault(Role.WEREWOLF, 0),
                "domain-model.md §11：标准局狼人恰好 3 名");
        assertEquals(1, composition.getOrDefault(Role.SEER, 0), "domain-model.md §11：预言家恰好 1 名");
        assertEquals(1, composition.getOrDefault(Role.WITCH, 0), "domain-model.md §11：女巫恰好 1 名");
        assertEquals(1, composition.getOrDefault(Role.HUNTER, 0), "domain-model.md §11：猎人恰好 1 名");
        assertEquals(3, composition.getOrDefault(Role.VILLAGER, 0), "domain-model.md §11：平民恰好 3 名");

        // event-design.md §14.7：AGENT_ 前缀事件不得出现在 game:{id}:events
        assertTrue(stream.stream().noneMatch(event -> event.type().code().startsWith("AGENT_")),
                "event-design.md §14.7：AGENT_ 前缀事件不得出现在对局事件流里");

        // 终局状态必须满足胜负规则本身（独立复核 WinConditionPolicy）
        Optional<WinConditionPolicy.Verdict> verdict =
                WinConditionPolicy.check(driver.game.snapshot().players(),
                        driver.game.snapshot().rules().winCondition());
        assertTrue(verdict.isPresent(),
                "domain-model.md §6.2：GAME_FINISHED 产生时胜负条件必须成立");
        assertEquals(verdict.get().winner(), driver.game.snapshot().winner(),
                "domain-model.md §6.2：GAME_FINISHED.winner 必须等于 WinConditionPolicy 的判定");
    }

    @Test
    @DisplayName("阶段跳转全部在 ddd-design.md §3.2 转换表内，且阶段链连续")
    void phaseTransitionsStayInsideDocumentedTable() {
        Driver driver = new Driver();
        driver.start();
        driver.driveUntilFinished();

        List<PhaseChangedEvent> changes = eventsOf(driver.stream, PhaseChangedEvent.class);
        assertFalse(changes.isEmpty(), "ddd-design.md §3.2：任何阶段跳转都必须产生 PHASE_CHANGED");

        assertEquals(GamePhase.GAME_INIT, changes.get(0).from(),
                "ddd-design.md §3.2：第一阶段跳转从 GAME_INIT 出发");
        for (int index = 0; index < changes.size(); index++) {
            PhaseChangedEvent change = changes.get(index);
            assertTrue(change.from().allowsNext(change.to()),
                    "ddd-design.md §3.2 转换表：出现了表外跳转 " + change.from() + " -> " + change.to());
            if (index > 0) {
                assertEquals(changes.get(index - 1).to(), change.from(),
                        "ddd-design.md §3.2 与 event-design.md §14.4：阶段链必须连续，"
                                + "上一条 PHASE_CHANGED 的 to 必须等于这一条的 from");
            }
            if (change.to() == GamePhase.HUNTER_SHOOT) {
                assertTrue(Set.of(GamePhase.NIGHT_SETTLE, GamePhase.DAY_ANNOUNCE,
                                GamePhase.LAST_WORDS, GamePhase.VOTE_SETTLE).contains(change.from()),
                        "ddd-design.md §3.2：HUNTER_SHOOT 的插入点只有 NIGHT_SETTLE / DAY_ANNOUNCE / "
                                + "LAST_WORDS / VOTE_SETTLE，实际来自 " + change.from());
                assertNotNull(change.resumePhase(),
                        "ddd-design.md §3.2：进入 HUNTER_SHOOT 必须记录 resumePhase");
                assertTrue(change.to().allowsNext(change.resumePhase()),
                        "ddd-design.md §3.2：resumePhase 必须是 HUNTER_SHOOT 的合法后继，实际 "
                                + change.resumePhase());
            }
        }
        assertEquals(GamePhase.GAME_OVER, driver.game.phase(),
                "ddd-design.md §3.2：对局结束时阶段必须是 GAME_OVER");
    }

    @Test
    @DisplayName("进入 GAME_OVER 也必须产生 PHASE_CHANGED（domain-model.md §4 的「进入时」列）")
    void gameOverEntryMustEmitPhaseChanged() {
        Driver driver = new Driver();
        driver.start();
        driver.driveUntilFinished();

        List<PhaseChangedEvent> changes = eventsOf(driver.stream, PhaseChangedEvent.class);
        assertEquals(GamePhase.GAME_OVER, driver.game.phase(),
                "前置条件：对局必须已进入 GAME_OVER");
        assertTrue(changes.stream().anyMatch(change -> change.to() == GamePhase.GAME_OVER),
                "domain-model.md §4 的阶段表给出每个阶段的「进入时」动作，GAME_OVER 行的进入动作是 PHASE_CHANGED；"
                        + "event-design.md §14.4 也要求「每个阶段跳转都有 PHASE_CHANGED」、"
                        + "ddd-design.md §3.2 规定「全部跳转都产生 PHASE_CHANGED」。"
                        + "当前实现把 phase 直接置为 GAME_OVER，事件流里没有这次跳转；"
                        + "事件流时间线：" + roundTimeline(driver.stream));
    }

    @Test
    @DisplayName("GAME_FINISHED 之后任何行动都被拒且零事件（INV-12）")
    void noActionIsAcceptedAfterGameFinished() {
        Driver driver = new Driver();
        driver.start();
        driver.driveUntilFinished();

        int versionAtEnd = driver.game.version();
        List<Action> rejected = List.of(
                SkipAction.voluntary(1),
                VoteAction.abstain(1),
                WolfKillAction.empty(1),
                SpeechAction.of(1, "已结束还想发言"),
                HunterShotAction.giveUp(1));

        for (Action action : rejected) {
            GameException exception = assertThrows(GameException.class,
                    () -> driver.game.handle(action, driver.context()),
                    "ddd-design.md §4.1 INV-12：GAME_OVER 之后不接受任何行动；action=" + action.type());
            assertEquals(DomainErrorCode.GAME_ALREADY_FINISHED, exception.code(),
                    "domain-model.md §6.1 V1：对局不在 RUNNING 时的拒绝码是 GAME_ALREADY_FINISHED；"
                            + "action=" + action.type());
        }

        assertEquals(versionAtEnd, driver.game.version(),
                "ddd-design.md §4.1 INV-11：被拒绝的命令不产生事件，version 不得变化");
        assertTrue(driver.game.pendingEvents().isEmpty(),
                "AGENTS.md §5.4：被拒绝的命令不产生成功行动事件");
    }

    // ------------------------------------------------------------------
    // 2. 重放
    // ------------------------------------------------------------------

    @Test
    @DisplayName("同一条事件流重放两次得到逐字段相同的快照，且等于实时处理的状态（INV-16）")
    void replayTwiceProducesIdenticalSnapshot() {
        Driver driver = new Driver();
        driver.start();
        driver.driveUntilFinished();

        Game first = Game.create(GAME_ID);
        Game second = Game.create(GAME_ID);
        for (DomainEvent event : driver.stream) {
            first.replay(event);
        }
        for (DomainEvent event : driver.stream) {
            second.replay(event);
        }

        assertEquals(driver.game.snapshot(), first.snapshot(),
                "ddd-design.md §4.2 INV-16、domain-model.md §11：重放同一事件流必须得到与实时处理逐字段相同的状态；"
                        + "差异字段见下一条消息；事件流里的轮次时间线：" + roundTimeline(driver.stream));
        assertEquals(first.snapshot(), second.snapshot(),
                "ddd-design.md §4.2 INV-16：重放两次必须逐字段相同");
        assertEquals(driver.stream.size(), first.version(),
                "event-design.md §11：重放后的 version 等于事件流长度");
        assertEquals(GameStatus.FINISHED, first.status(),
                "domain-model.md §2.3：重放结束事件后状态必须是 FINISHED");
    }

    @Test
    @DisplayName("乱序与重复重放都被拒绝（REPLAY_OUT_OF_ORDER）")
    void outOfOrderReplayIsRejected() {
        Driver driver = new Driver();
        driver.start();
        driver.driveUntilFinished();

        List<DomainEvent> stream = driver.stream;
        assertTrue(stream.size() > 5, "事件流过短，无法构造乱序用例");

        Game partial = Game.create(GAME_ID);
        partial.replay(stream.get(0));
        partial.replay(stream.get(1));

        GameException skipped = assertThrows(GameException.class,
                () -> partial.replay(stream.get(3)),
                "ddd-design.md §4.2 INV-16：跳号重放必须被拒绝");
        assertEquals(DomainErrorCode.REPLAY_OUT_OF_ORDER, skipped.code(),
                "domain-model.md §11：乱序重放的拒绝码是 REPLAY_OUT_OF_ORDER，实际 " + skipped.codeString());

        GameException duplicated = assertThrows(GameException.class, () -> {
            partial.replay(stream.get(2));
            partial.replay(stream.get(2));
        }, "ddd-design.md §4.1 INV-11、INV-14：同一事件重复重放必须被拒绝");
        assertEquals(DomainErrorCode.REPLAY_OUT_OF_ORDER, duplicated.code(),
                "domain-model.md §11：重复重放同样破坏版本连续性，拒绝码是 REPLAY_OUT_OF_ORDER");
    }

    // ------------------------------------------------------------------
    // 3. 计票边界
    // ------------------------------------------------------------------

    @Test
    @DisplayName("全员弃权：ALL_ABSTAIN、无人出局、不产生放逐死亡")
    void allAbstainExilesNobody() {
        Driver driver = new Driver();
        // 空刀让首夜无死亡，这样"零 PLAYER_KILLED"只可能来自"无人出局"
        driver.wolfPicker = state -> null;
        driver.votePicker = (state, voter) -> null;
        driver.start();
        driver.driveTo(GamePhase.DAY_VOTE);
        driver.drivePastVote();

        List<VoteTallyResolvedEvent> tallies = eventsOf(driver.stream, VoteTallyResolvedEvent.class);
        assertEquals(1, tallies.size(),
                "domain-model.md §6.2：每个 VOTE_SETTLE 恰好产生一条 VOTE_TALLY_RESOLVED");
        VoteTallyResolvedEvent tally = tallies.get(0);

        assertEquals(TallyResult.Result.ALL_ABSTAIN, tally.result(),
                "event-design.md §7.2：最高票为 0 时 result 必须是 ALL_ABSTAIN");
        assertEquals(0, tally.maxVotes(), "event-design.md §7.2：ALL_ABSTAIN 时 maxVotes 为 0");
        assertNull(tally.exiledPlayerId(), "event-design.md §7.2：ALL_ABSTAIN 时无人出局");
        assertEquals(List.of(), tally.tally(), "event-design.md §7.2：ALL_ABSTAIN 时 tally 为空");

        assertEquals(0, eventsByType(driver.stream, EventType.PLAYER_KILLED).size(),
                "domain-model.md §6.2：无人出局时不得产生 PLAYER_KILLED");
        assertEquals(GamePhase.NIGHT_WOLF, driver.game.phase(),
                "ddd-design.md §3.2：VOTE_SETTLE 在无人出局且未分胜负时进入下一轮 NIGHT_WOLF");
        assertEquals(2, driver.game.round(), "domain-model.md §2.1：未分胜负时轮次 +1");
    }

    @Test
    @DisplayName("平票：TIE、无人出局、tieSeats 记录并列座位")
    void tieVoteExilesNobody() {
        Driver driver = new Driver();
        // 空刀保证 9 名玩家全部存活，最低两席互投、其余弃权 → 1:1 平票
        driver.wolfPicker = state -> null;
        driver.votePicker = (state, voter) -> {
            List<Integer> alive = aliveSeats(state);
            int first = alive.get(0);
            int second = alive.get(1);
            if (voter.value() == first) {
                return second;
            }
            if (voter.value() == second) {
                return first;
            }
            return null;
        };
        driver.start();
        driver.driveTo(GamePhase.DAY_VOTE);
        driver.drivePastVote();

        VoteTallyResolvedEvent tally = eventsOf(driver.stream, VoteTallyResolvedEvent.class).get(0);
        assertEquals(TallyResult.Result.TIE, tally.result(),
                "event-design.md §7.2：最高票并列时 result 必须是 TIE");
        assertNull(tally.exiledPlayerId(),
                "domain-model.md §3（voteTieRule=NO_EXILE）：平票时无人出局");
        assertEquals(1, tally.maxVotes(), "event-design.md §7.2：平票时 maxVotes 为并列最高票数 1");
        assertEquals(List.of(1, 2), tally.tieSeats(),
                "event-design.md §7.2：tieSeats 必须按升序记录并列座位");
        assertEquals(0, eventsByType(driver.stream, EventType.PLAYER_KILLED).size(),
                "domain-model.md §6.2：平票不出局，不得产生放逐死亡");
        assertEquals(GamePhase.NIGHT_WOLF, driver.game.phase(),
                "ddd-design.md §3.2：VOTE_SETTLE 无人出局时进入下一轮 NIGHT_WOLF");
    }

    // ------------------------------------------------------------------
    // 4. 女巫与猎人
    // ------------------------------------------------------------------

    @Test
    @DisplayName("女巫同夜第二次用药被拒且零事件（INV-7）")
    void witchSecondPotionInSameNightIsRejected() {
        Driver driver = new Driver();
        driver.wolfPicker = state -> {
            SeatNo witch = seatOfRole(state, Role.WITCH);
            return state.players().stream()
                    .filter(PlayerSnapshot::alive)
                    .filter(player -> !player.isWolf())
                    .filter(player -> !player.seatNo().equals(witch))
                    .map(player -> player.seatNo().value())
                    .min(Integer::compareTo)
                    .orElse(null);
        };
        driver.witchPicker = (state, witch) -> WitchPlan.save(state.wolfKillTarget());
        driver.start();
        driver.driveTo(GamePhase.NIGHT_WITCH);

        SeatNo witch = holderOf(driver.state(), Role.WITCH);
        driver.step();
        assertTrue(driver.stream.stream().anyMatch(event -> event.type() == EventType.WITCH_SAVED),
                "domain-model.md §7：WitchAction{SAVE} 必须产生 WITCH_SAVED 事件");

        int versionAfterSave = driver.game.version();
        SeatNo poisonTarget = lowestAliveOther(driver.state(), witch);
        GameException exception = assertThrows(GameException.class,
                () -> driver.game.handle(
                        WitchAction.poison(witch.value(), poisonTarget.value()), driver.context()),
                "ddd-design.md §4.1 INV-7：女巫在同一夜不得同时使用解药与毒药");
        assertEquals(DomainErrorCode.ACTION_NOT_ALLOWED, exception.code(),
                "ddd-design.md §4.1 INV-7 的违反表现是 ACTION_NOT_ALLOWED，实际 " + exception.codeString());
        assertEquals(versionAfterSave, driver.game.version(),
                "AGENTS.md §5.4：被拒绝的命令不产生事件，version 不得变化");
        assertTrue(driver.game.pendingEvents().isEmpty(),
                "ddd-design.md §4.1 INV-11：被拒绝的行动必须零事件");
    }

    @Test
    void antidoteIsConsumedAcrossNightsReplayAndSnapshotRestore() {
        Driver driver = new Driver();
        driver.wolfPicker = state -> state.players().stream().filter(p -> p.alive() && p.role() == Role.VILLAGER)
                .findFirst().orElseThrow().seatNo().value();
        driver.votePicker = (state, voter) -> null;
        driver.witchPicker = (state, witch) -> state.round() == 1 ? WitchPlan.save(state.wolfKillTarget()) : WitchPlan.pass();
        driver.start();
        driver.driveTo(GamePhase.NIGHT_WITCH); driver.step();
        driver.driveTo(GamePhase.NIGHT_WITCH);
        SeatNo witch = holderOf(driver.state(), Role.WITCH);
        assertEquals(2, driver.state().round());
        assertFalse(driver.state().playerAt(witch).skillState().antidoteAvailable());
        assertTrue(driver.state().playerAt(witch).skillState().poisonAvailable());
        Game restored = Game.restore(driver.state());
        var rejected = assertThrows(GameException.class, () -> restored.handle(
                WitchAction.save(witch.value(), driver.state().wolfKillTarget()), driver.context()));
        assertEquals(DomainErrorCode.SKILL_UNAVAILABLE, rejected.code());
        assertEquals(driver.state().version(), restored.version());
        Game replay = Game.create(GAME_ID);
        driver.stream.forEach(replay::replay);
        assertEquals(driver.state(), replay.snapshot());
        var view = com.wolfmind.domain.game.view.GameViewProjector.project(driver.state(),
                com.wolfmind.domain.game.model.ViewerContext.participant("witch", Set.of(PlayerId.forSeat(witch)), Set.of(), false));
        assertTrue(view.permissions().stream().noneMatch(p -> p.type().equals("WITCH_SAVE")));
        driver.stream.addAll(driver.game.handle(WitchAction.poison(witch.value(), lowestAliveWolf(driver.state())), driver.context()));
        assertFalse(driver.state().playerAt(witch).skillState().poisonAvailable());
    }

    @Test
    void poisonIsConsumedAcrossNightsAndUnavailableInPermissions() {
        Driver driver = new Driver();
        driver.wolfPicker = state -> null;
        driver.votePicker = (state, voter) -> null;
        driver.witchPicker = (state, witch) -> state.round() == 1 ? WitchPlan.poison(lowestAliveWolf(state)) : WitchPlan.pass();
        driver.start(); driver.driveTo(GamePhase.NIGHT_WITCH); driver.step();
        driver.driveTo(GamePhase.NIGHT_WITCH);
        SeatNo witch = holderOf(driver.state(), Role.WITCH);
        assertEquals(2, driver.state().round());
        assertFalse(driver.state().playerAt(witch).skillState().poisonAvailable());
        assertTrue(driver.state().playerAt(witch).skillState().antidoteAvailable());
        var rejected = assertThrows(GameException.class, () -> driver.game.handle(
                WitchAction.poison(witch.value(), lowestAliveWolf(driver.state())), driver.context()));
        assertEquals(DomainErrorCode.SKILL_UNAVAILABLE, rejected.code());
        assertTrue(driver.game.pendingEvents().isEmpty());
        var view = com.wolfmind.domain.game.view.GameViewProjector.project(driver.state(),
                ViewerContext.participant("witch", Set.of(PlayerId.forSeat(witch)), Set.of(), false));
        assertTrue(view.permissions().stream().noneMatch(p -> p.type().equals("WITCH_POISON")));
    }

    @Test
    @DisplayName("被毒的猎人不产生 HUNTER_SHOOT 阶段与 HUNTER_SHOT 事件（INV-8）")
    void poisonedHunterNeverShoots() {
        Driver driver = new Driver();
        driver.wolfPicker = state -> {
            SeatNo witch = seatOfRole(state, Role.WITCH);
            SeatNo hunter = seatOfRole(state, Role.HUNTER);
            return state.players().stream()
                    .filter(PlayerSnapshot::alive)
                    .filter(player -> !player.isWolf())
                    .filter(player -> !player.seatNo().equals(witch))
                    .filter(player -> !player.seatNo().equals(hunter))
                    .map(player -> player.seatNo().value())
                    .min(Integer::compareTo)
                    .orElse(null);
        };
        // 屠边下对局会持续到猎人死后，之后女巫不再对死者用药
        driver.witchPicker = (state, witch) -> {
            SeatNo hunter = seatOfRole(state, Role.HUNTER);
            return state.playerAt(hunter).alive() ? WitchPlan.poison(hunter.value()) : WitchPlan.pass();
        };
        driver.start();
        driver.driveUntilFinished();

        SeatNo hunter = seatOfRole(driver.state(), Role.HUNTER);
        assertFalse(driver.state().playerAt(hunter).alive(),
                "domain-model.md §6.3：被毒的猎人必须死亡");
        assertEquals(0, eventsByType(driver.stream, EventType.HUNTER_SHOT).size(),
                "ddd-design.md §4.1 INV-8、domain-model.md §11：被毒死亡的猎人不产生 HUNTER_SHOOT 事件");
        assertTrue(eventsOf(driver.stream, PhaseChangedEvent.class).stream()
                        .noneMatch(change -> change.to() == GamePhase.HUNTER_SHOOT),
                "ddd-design.md §4.1 INV-8：被毒死亡时不得进入 HUNTER_SHOOT 阶段");

        List<PlayerKilledEvent> hunterDeaths = eventsOf(driver.stream, PlayerKilledEvent.class).stream()
                .filter(death -> death.seatNo().equals(hunter))
                .toList();
        assertEquals(1, hunterDeaths.size(), "猎人必须恰好有一条 PLAYER_KILLED");
        assertTrue(hunterDeaths.get(0).causedBy(DeathCause.WITCH_POISON),
                "event-design.md §7.2：被毒死亡的 causes 必须含 WITCH_POISON");
    }

    @Test
    @DisplayName("猎人放弃开枪产生 HUNTER_SKIPPED（domain-model.md §11）")
    void hunterGiveUpProducesHunterSkipped() {
        Driver driver = new Driver();
        driver.wolfPicker = GameSimulationTest::hunterFirstThenLowest;
        driver.hunterPicker = (state, hunter) -> null;
        driver.start();
        driver.driveTo(GamePhase.HUNTER_SHOOT);
        driver.step();

        List<HunterSkippedEvent> skipped = eventsOf(driver.stream, HunterSkippedEvent.class);
        assertEquals(1, skipped.size(),
                "domain-model.md §7：SkipAction | HUNTER | HUNTER_SHOOT 产生 HUNTER_SKIPPED");
        assertEquals(0, eventsByType(driver.stream, EventType.HUNTER_SHOT).size(),
                "domain-model.md §7：放弃开枪不得产生 HUNTER_SHOT");

        driver.driveUntilFinished();
        assertVersionStreamIsContiguous(driver.stream);
    }

    @Test
    void hunterTimeoutHonorsGrantedTurnDeadline() {
        Driver driver = new Driver();
        driver.wolfPicker = GameSimulationTest::hunterFirstThenLowest;
        driver.start();
        driver.driveTo(GamePhase.HUNTER_SHOOT);
        var state = driver.state();
        assertNotNull(state.currentTurn());
        long deadline = state.currentTurn().deadline();
        assertTrue(driver.game.timeoutSkips(deadline - 1, true).skips().isEmpty());
        var skips = driver.game.timeoutSkips(deadline, false).skips();
        assertEquals(1, skips.size());
        assertEquals(state.pendingHunter(), skips.getFirst().actor().value());
    }

    @Test
    @DisplayName("猎人开枪必须带走目标并立即判定胜负（domain-model.md §11）")
    void hunterShotKillsTargetImmediately() {
        Driver driver = new Driver();
        driver.wolfPicker = GameSimulationTest::hunterFirstThenLowest;
        driver.hunterPicker = GameSimulationTest::lowestAliveOtherSeat;
        driver.start();
        driver.driveTo(GamePhase.HUNTER_SHOOT);

        // 猎人此刻必然已死亡：ddd-design.md §3.2 规定 HUNTER_SHOOT 由"结算点产生猎人死亡"插入
        SeatNo hunter = SeatNo.of(driver.state().pendingHunter());
        int target = driver.hunterPicker.apply(driver.state(), hunter);
        driver.step();

        HunterShotEvent shot = eventsOf(driver.stream, HunterShotEvent.class).stream()
                .reduce((first, second) -> second)
                .orElseThrow(() -> new AssertionError(
                        "domain-model.md §7：HunterShotAction（非放弃）必须产生 HUNTER_SHOT 事件"));
        assertEquals(target, shot.targetSeatNo().value(),
                "event-design.md §7.2：HUNTER_SHOT.targetSeatNo 必须是被带走的目标");

        PlayerSnapshot victim = driver.state().playerAt(SeatNo.of(target));
        assertFalse(victim.alive(),
                "domain-model.md §11「猎人开枪 | 开枪带走目标后立即判定胜负」、§5.4（DeathCause 含 HUNTER_SHOT）："
                        + "开枪后目标必须立刻死亡，实际座位 " + target + " 仍然存活");
        List<PlayerKilledEvent> victimDeaths = eventsOf(driver.stream, PlayerKilledEvent.class).stream()
                .filter(death -> death.seatNo().equals(victim.seatNo()))
                .toList();
        assertFalse(victimDeaths.isEmpty(),
                "domain-model.md §6.3 与 §5.4：被枪击必须产生 PLAYER_KILLED 且 causes 含 HUNTER_SHOT");
        assertTrue(victimDeaths.get(0).causedBy(DeathCause.HUNTER_SHOT),
                "domain-model.md §5.4：DeathCause 含 HUNTER_SHOT，被枪击的死亡原因必须是它");
        assertFalse(driver.state().playerAt(hunter).skillState().shotAvailable(),
                "一次性技能开枪后必须消耗");
        assertEquals(null, driver.state().pendingHunter());
        assertFalse(driver.state().phase() == GamePhase.HUNTER_SHOOT,
                "开枪后必须离开插入阶段");
        assertEquals(1, driver.state().deaths().stream()
                .filter(death -> death.seatNo().value() == target).count(), "同一目标只登记一次死亡");
        Game replayed = Game.create(GAME_ID);
        for (DomainEvent event : driver.stream) {
            replayed.replay(event);
            assertTrue(replayed.pendingEvents().isEmpty(), "重放不能生成新的待提交事件");
        }
        assertEquals(driver.state(), replayed.snapshot());
    }

    // ------------------------------------------------------------------
    // 5. 胜负与狼刀裁决
    // ------------------------------------------------------------------

    @Test
    @DisplayName("最后一狼与最后一民同时死亡判村民胜；狼数等于村人数判狼人胜")
    void winConditionOrderDecidesSimultaneousDeaths() {
        PlayerSnapshot villager = playerSnapshot(1, Role.VILLAGER, true);
        PlayerSnapshot wolf = playerSnapshot(6, Role.WEREWOLF, true);

        // domain-model.md §6.3：狼刀村民 1、女巫毒狼 6 → 同夜两人都死
        NightResolutionPolicy.Resolution resolution =
                NightResolutionPolicy.resolve(List.of(villager, wolf), 1, null, 6, 2);
        assertEquals(List.of(1, 6),
                resolution.deaths().stream().map(death -> death.seatNo().value()).toList(),
                "domain-model.md §6.3 第 4 步：死亡按座位号升序输出");
        assertEquals(Set.of(DeathCause.WOLF_KILL), Set.copyOf(resolution.deaths().get(0).causes()),
                "domain-model.md §6.3：座位 1 的死因是狼刀");
        assertEquals(Set.of(DeathCause.WITCH_POISON), Set.copyOf(resolution.deaths().get(1).causes()),
                "domain-model.md §6.3：座位 6 的死因是毒药");

        List<PlayerSnapshot> afterNight = List.of(
                playerSnapshot(1, Role.VILLAGER, false),
                playerSnapshot(6, Role.WEREWOLF, false));
        for (WinCondition condition : WinCondition.values()) {
            Optional<WinConditionPolicy.Verdict> verdict = WinConditionPolicy.check(afterNight, condition);
            assertTrue(verdict.isPresent(), "domain-model.md §6.2：最后一狼与最后一民同时死亡必须分出胜负");
            assertEquals(Camp.VILLAGE, verdict.get().winner(),
                    "domain-model.md §6.2 与 §11：判定顺序固定为先判狼全灭，因此同时死亡时判村民胜（" + condition + "）");
        }

        // 狼数 = 村民阵营数 → 狼人胜（WOLVES_OUTNUMBER）
        List<PlayerSnapshot> equalCounts = List.of(
                playerSnapshot(1, Role.VILLAGER, true),
                playerSnapshot(2, Role.VILLAGER, true),
                playerSnapshot(6, Role.WEREWOLF, true),
                playerSnapshot(7, Role.WEREWOLF, true));
        assertEquals(Camp.WOLF,
                WinConditionPolicy.check(equalCounts, WinCondition.WOLVES_OUTNUMBER).orElseThrow().winner(),
                "domain-model.md §3（winCondition=WOLVES_OUTNUMBER）：狼数 ≥ 村民阵营存活数时狼人胜");

        // 狼全灭 → 村民胜
        List<PlayerSnapshot> noWolves = List.of(
                playerSnapshot(1, Role.VILLAGER, true),
                playerSnapshot(6, Role.WEREWOLF, false));
        for (WinCondition condition : WinCondition.values()) {
            assertEquals(Camp.VILLAGE, WinConditionPolicy.check(noWolves, condition).orElseThrow().winner(),
                    "domain-model.md §6.2：狼全灭判村民胜（" + condition + "）");
        }
    }

    @Test
    @DisplayName("屠边：人数持平不结束；平民全灭或神职全灭判狼人胜")
    void sideKillEndsOnlyWhenOneSideIsEliminated() {
        // g_r_mv5ggj6c_1 第二夜后的局面：3 狼对 2 平民 + 猎人，人数持平
        List<PlayerSnapshot> parity = List.of(
                playerSnapshot(1, Role.WITCH, false),
                playerSnapshot(2, Role.VILLAGER, false),
                playerSnapshot(3, Role.VILLAGER, true),
                playerSnapshot(4, Role.SEER, false),
                playerSnapshot(5, Role.WEREWOLF, true),
                playerSnapshot(6, Role.WEREWOLF, true),
                playerSnapshot(7, Role.HUNTER, true),
                playerSnapshot(8, Role.VILLAGER, true),
                playerSnapshot(9, Role.WEREWOLF, true));
        assertTrue(WinConditionPolicy.check(parity, WinCondition.SIDE_KILL).isEmpty(),
                "屠边：平民与神职都还有人存活时，人数持平也不结束");
        assertEquals(WinConditionPolicy.REASON_WOLVES_OUTNUMBER_VILLAGE,
                WinConditionPolicy.check(parity, WinCondition.WOLVES_OUTNUMBER).orElseThrow().reason(),
                "同一局面在 WOLVES_OUTNUMBER 下判狼人胜（旧对局按开局规则重放）");

        List<PlayerSnapshot> godsDead = List.of(
                playerSnapshot(1, Role.WITCH, false),
                playerSnapshot(2, Role.VILLAGER, true),
                playerSnapshot(4, Role.SEER, false),
                playerSnapshot(5, Role.WEREWOLF, true),
                playerSnapshot(7, Role.HUNTER, false));
        WinConditionPolicy.Verdict gods = WinConditionPolicy.check(godsDead, WinCondition.SIDE_KILL).orElseThrow();
        assertEquals(Camp.WOLF, gods.winner());
        assertEquals(WinConditionPolicy.REASON_ALL_GODS_ELIMINATED, gods.reason());

        List<PlayerSnapshot> villagersDead = List.of(
                playerSnapshot(1, Role.WITCH, true),
                playerSnapshot(2, Role.VILLAGER, false),
                playerSnapshot(3, Role.VILLAGER, false),
                playerSnapshot(5, Role.WEREWOLF, true));
        WinConditionPolicy.Verdict villagers =
                WinConditionPolicy.check(villagersDead, WinCondition.SIDE_KILL).orElseThrow();
        assertEquals(Camp.WOLF, villagers.winner());
        assertEquals(WinConditionPolicy.REASON_ALL_VILLAGERS_ELIMINATED, villagers.reason());

        // 开局就没有神职的配置：神职这一边不参与判定，不能开局即判狼人胜
        List<PlayerSnapshot> noGodsAtAll = List.of(
                playerSnapshot(1, Role.VILLAGER, true),
                playerSnapshot(2, Role.VILLAGER, true),
                playerSnapshot(5, Role.WEREWOLF, true));
        assertTrue(WinConditionPolicy.check(noGodsAtAll, WinCondition.SIDE_KILL).isEmpty(),
                "屠边：开局不存在的一边不参与判定");
    }

    @Test
    @DisplayName("狼刀平票按最小座位定案（domain-model.md §3 wolfKillTieBreak=MIN_SEAT）")
    void wolfKillTieIsResolvedToLowestSeat() {
        // 纯函数：3 号狼提名 6 号、4 号狼提名 7 号 → 1:1 平票 → 取座位号最小者
        List<WolfKillSelectedEvent.Nomination> tie = List.of(
                new WolfKillSelectedEvent.Nomination(3, 7),
                new WolfKillSelectedEvent.Nomination(4, 6));
        WolfKillPolicy.Decision decision = WolfKillPolicy.decide(tie, WolfKillTieBreak.MIN_SEAT);
        assertEquals(6, decision.targetSeat(),
                "domain-model.md §6.2 与 §11：狼刀平票取座位号最小者");
        assertFalse(decision.emptyKill(), "domain-model.md §6.2：有人提名时不是空刀");

        // 全员弃刀 → 空刀（仍然产生事件）
        WolfKillPolicy.Decision empty = WolfKillPolicy.decide(
                List.of(new WolfKillSelectedEvent.Nomination(3, null),
                        new WolfKillSelectedEvent.Nomination(4, null)),
                WolfKillTieBreak.MIN_SEAT);
        assertTrue(empty.emptyKill(), "domain-model.md §6.2：全员弃刀则空刀");
        assertNull(empty.targetSeat(), "domain-model.md §6.2：空刀没有目标");
    }

    @Test
    @DisplayName("聚合内狼刀平票同样取最小座位，空刀产生 targetId=null 的事件且无人死亡")
    void wolfKillTieAndEmptyKillInsideAggregate() {
        // 三名狼人：前两名提名两个不同目标，第三名弃刀 → 1:1 平票 → 取最小座位
        AtomicInteger nominationIndex = new AtomicInteger();
        List<Integer> nominatedTargets = new ArrayList<>();
        Driver driver = new Driver();
        driver.wolfPicker = state -> {
            List<Integer> targets = aliveNonWolfSeats(state);
            int index = nominationIndex.getAndIncrement();
            if (index >= 2 || targets.size() < 2) {
                return null;
            }
            int target = targets.get(index);
            nominatedTargets.add(target);
            return target;
        };
        driver.start();
        driver.driveTo(GamePhase.NIGHT_SEER);

        WolfKillSelectedEvent selected = eventsOf(driver.stream, WolfKillSelectedEvent.class).get(0);
        assertEquals(2, nominatedTargets.size(), "前置条件：应有两次有效提名");
        assertEquals(Math.min(nominatedTargets.get(0), nominatedTargets.get(1)),
                selected.targetSeatNo().value(),
                "domain-model.md §3 与 §11：狼刀提名平票时取座位号最小者，保证确定性");
        assertEquals(3, selected.nominations().size(),
                "event-design.md §7.2：nominations 必须记录全部狼人的提名（含弃刀）");
    }

    @Test
    @DisplayName("全员弃刀形成空刀：WOLF_KILL_SELECTED.targetId 为 null，平安夜仍然产生 DEATH_ANNOUNCED")
    void allWolvesEmptyKillProducesPeacefulNight() {
        Driver driver = new Driver();
        driver.wolfPicker = state -> null;
        driver.start();
        driver.driveTo(GamePhase.DAY_DISCUSS);

        WolfKillSelectedEvent selected = eventsOf(driver.stream, WolfKillSelectedEvent.class).get(0);
        assertTrue(selected.isEmptyKill(),
                "event-design.md §7.2：存活狼人全部弃刀时 targetId 为 null，仍然产生事件");
        assertNull(selected.targetSeatNo(), "event-design.md §7.2：空刀没有目标座位");

        List<DeathAnnouncedEvent> announced = eventsOf(driver.stream, DeathAnnouncedEvent.class);
        assertEquals(1, announced.size(),
                "event-design.md §7.2：平安夜仍然产生 DEATH_ANNOUNCED，不能靠「没有事件」推断");
        assertTrue(announced.get(0).peacefulNight(),
                "event-design.md §7.2：deaths 为空数组表示平安夜");
        assertEquals(0, eventsByType(driver.stream, EventType.PLAYER_KILLED).size(),
                "domain-model.md §6.3：空刀时当夜无人死亡");
    }

    // ------------------------------------------------------------------
    // 6. 越权
    // ------------------------------------------------------------------

    @Test
    @DisplayName("越权提交（村民狼刀 / 死者投票 / 非当前回合发言）被拒且零事件")
    void unauthorizedActionsAreRejectedWithZeroEvents() {
        Driver driver = new Driver();
        driver.start();

        // 村民提交狼刀 → V4 角色不符
        SeatNo villager = driver.state().players().stream()
                .filter(player -> player.alive() && player.role() == Role.VILLAGER)
                .map(PlayerSnapshot::seatNo)
                .findFirst()
                .orElseThrow();
        SeatNo victim = lowestAliveOther(driver.state(), villager);
        int versionBefore = driver.game.version();
        GameException wrongRole = assertThrows(GameException.class,
                () -> driver.game.handle(
                        WolfKillAction.at(villager.value(), victim.value()), driver.context()),
                "domain-model.md §6.1 V4：行动者角色不具备该能力时必须拒绝");
        assertEquals(DomainErrorCode.ACTION_NOT_ALLOWED, wrongRole.code(),
                "domain-model.md §6.1 V4 的拒绝码是 ACTION_NOT_ALLOWED");
        assertEquals(versionBefore, driver.game.version(),
                "AGENTS.md §5.4：被拒绝的命令不产生事件");
        assertTrue(driver.game.pendingEvents().isEmpty(), "被拒绝的行动必须零事件");

        // 推进到白天讨论，让非当前回合玩家抢发言 → V5
        driver.driveTo(GamePhase.DAY_DISCUSS);
        Turn turn = driver.state().currentTurn();
        assertNotNull(turn, "domain-model.md §4：DAY_DISCUSS 必须发放 TURN_GRANTED");
        SeatNo other = driver.state().players().stream()
                .filter(PlayerSnapshot::alive)
                .map(PlayerSnapshot::seatNo)
                .filter(seat -> !seat.equals(turn.seatNo()))
                .findFirst()
                .orElseThrow();
        int versionBeforeTurn = driver.game.version();
        GameException turnNotYours = assertThrows(GameException.class,
                () -> driver.game.handle(SpeechAction.of(other.value(), "抢发言"), driver.context()),
                "domain-model.md §6.1 V5：需要回合窗口的阶段，行动者必须等于 currentTurn.playerId");
        assertEquals(DomainErrorCode.TURN_NOT_YOURS, turnNotYours.code(),
                "domain-model.md §6.1 V5 的拒绝码是 TURN_NOT_YOURS");
        assertEquals(versionBeforeTurn, driver.game.version(), "被拒绝的命令不产生事件");

        // 推进到投票阶段：已死亡的玩家不能投票 → V2
        driver.driveTo(GamePhase.DAY_VOTE);
        List<Integer> deadSeats = driver.state().players().stream()
                .filter(player -> !player.alive())
                .map(player -> player.seatNo().value())
                .toList();
        assertFalse(deadSeats.isEmpty(),
                "前置条件：此前夜间的狼刀必须已经造成死亡，否则无法验证死者投票");
        int deadVoter = deadSeats.get(0);
        int versionBeforeVote = driver.game.version();
        SeatNo voteTarget = driver.state().players().stream()
                .filter(PlayerSnapshot::alive)
                .map(PlayerSnapshot::seatNo)
                .findFirst()
                .orElseThrow();
        GameException deadCannotVote = assertThrows(GameException.class,
                () -> driver.game.handle(
                        VoteAction.to(deadVoter, voteTarget.value()), driver.context()),
                "domain-model.md §6.1 V2 与 §4.1 INV-9：死者不再拥有回合、不可被投票、不可行动");
        assertEquals(DomainErrorCode.ACTION_NOT_ALLOWED, deadCannotVote.code(),
                "domain-model.md §6.1 V2 的拒绝码是 ACTION_NOT_ALLOWED");
        assertEquals(versionBeforeVote, driver.game.version(), "被拒绝的命令不产生事件");
        assertTrue(driver.game.pendingEvents().isEmpty(), "被拒绝的行动必须零事件");
    }

    @Test
    @DisplayName("遗言阶段用超时兜底（SkipAction）后事件流仍可确定性重放（INV-16）")
    void lastWordsTimeoutSkipReplaysDeterministically() {
        Driver driver = new Driver();
        driver.skipLastWords = true;
        driver.start();
        driver.driveUntilFinished();

        assertVersionStreamIsContiguous(driver.stream);
        assertTrue(driver.stream.stream().anyMatch(event -> event.type() == EventType.SPEECH_SKIPPED),
                "前置条件：驱动策略要求在遗言阶段提交 SkipAction，因此必须出现过 SPEECH_SKIPPED");

        Game replayed = Game.create(GAME_ID);
        for (DomainEvent event : driver.stream) {
            replayed.replay(event);
        }
        assertEquals(driver.game.snapshot(), replayed.snapshot(),
                "ddd-design.md §4.2 INV-16：SkipAction 兜底与普通发言走同一条 handle 路径，"
                        + "因此含兜底的事件流重放后必须与实时状态逐字段一致。"
                        + "event-design.md §14.2 也要求兜底不能绕过领域。事件流时间线："
                        + roundTimeline(driver.stream));
    }

    @Test
    @DisplayName("首夜死亡并完成遗言后必须回到当天的 DAY_DISCUSS（ddd-design.md §3.2 主流程）")
    void nightDeathLastWordsLeadBackToDayDiscussion() {
        Driver driver = new Driver();
        driver.start();
        driver.driveTo(GamePhase.LAST_WORDS);

        GameSnapshot atLastWords = driver.state();
        assertEquals(GamePhase.LAST_WORDS, atLastWords.phase(),
                "前置条件：首夜有人死亡且 firstNightLastWords=true，应进入 LAST_WORDS");
        assertEquals(GamePhase.DAY_DISCUSS, atLastWords.pendingAfterLastWords(),
                "ddd-design.md §3.2 主流程：夜间死亡「公布死讯 → LAST_WORDS → DAY_DISCUSS」，"
                        + "因此进入 LAST_WORDS 时 pendingAfterLastWords 必须是 DAY_DISCUSS"
                        + "（NIGHT_WOLF 只用于被放逐者遗言之后的下一轮）；实际 " + atLastWords.pendingAfterLastWords());

        driver.driveTo(GamePhase.DAY_DISCUSS);
        assertEquals(1, driver.game.round(),
                "ddd-design.md §3.2：首夜遗言之后仍属第 1 轮的白天讨论，不得跳过白天直接进入第 2 轮夜晚。"
                        + "阶段时间线：" + roundTimeline(driver.stream));
        assertTrue(eventsOf(driver.stream, PhaseChangedEvent.class).stream()
                        .anyMatch(change -> change.from() == GamePhase.LAST_WORDS
                                && change.to() == GamePhase.DAY_DISCUSS),
                "ddd-design.md §3.2：必须存在 LAST_WORDS -> DAY_DISCUSS 的阶段跳转");
    }

    @Test
    @DisplayName("同一遗言阶段有多名死者时，每名死者按座位顺序各获得一次回合")
    void everyLastWordsSpeakerGetsExactlyOneTurn() {
        Driver driver = new Driver();
        driver.witchPicker = (state, witch) -> state.round() == 1
                ? WitchPlan.poison(lowestAliveWolf(state))
                : WitchPlan.pass();
        driver.start();
        driver.driveTo(GamePhase.LAST_WORDS);

        List<Integer> nightDeaths = driver.state().deaths().stream()
                .filter(death -> death.round() == 1)
                .map(death -> death.seatNo().value())
                .sorted()
                .toList();
        assertEquals(2, nightDeaths.size(), "前置条件：首夜狼刀 + 女巫毒狼，应有两名死者");

        driver.driveTo(GamePhase.DAY_DISCUSS);

        assertEquals(nightDeaths, lastWordsGrantedSeats(driver.stream),
                "domain-model.md §3 firstNightLastWords：首夜每名死者各有一次遗言，按座位顺序；"
                        + "回合必须由遗言游标推进，不能反复发给队首");
        Game replayed = replayPrefix(driver.stream, driver.stream.size());
        assertEquals(driver.state(), replayed.snapshot());
    }

    @Test
    @DisplayName("放逐后的遗言只发给放逐者，不再发给清晨已发表遗言的夜间死者")
    void exileLastWordsDoNotRepeatMorningSpeakers() {
        Driver driver = new Driver();
        driver.start();
        driver.driveTo(GamePhase.DAY_VOTE);
        int nightVictim = driver.state().deaths().get(0).seatNo().value();
        int voteStart = driver.stream.size();

        driver.driveTo(GamePhase.NIGHT_WOLF);

        List<Integer> exiled = driver.state().deaths().stream()
                .filter(death -> death.round() == 1 && death.causedBy(DeathCause.EXILE))
                .map(death -> death.seatNo().value())
                .toList();
        assertEquals(1, exiled.size(), "前置条件：默认投票策略应放逐一名玩家");
        List<Integer> granted = lastWordsGrantedSeats(driver.stream.subList(voteStart, driver.stream.size()));
        assertFalse(granted.contains(nightVictim),
                "首夜死者 " + nightVictim + " 已在清晨发表遗言，放逐后的遗言阶段不得再次发给他；实际 " + granted);
        assertEquals(1, granted.stream().filter(exiled.get(0)::equals).count(),
                "domain-model.md §3 exileLastWords：被放逐者恰好获得一次遗言回合；实际 " + granted);
    }

    @Test
    @DisplayName("第 2 天放逐的猎人开枪带走的目标有遗言（dayShotLastWords），且可重放")
    void dayShotTargetGetsLastWords() {
        Driver driver = dayShotDriver(GameRules.DEFAULT);

        DayShot shot = driveDayTwoShot(driver);

        assertTrue(shot.granted().contains(shot.hunter()), "被放逐的猎人有遗言；实际 " + shot.granted());
        assertTrue(shot.granted().contains(shot.target()),
                "domain-model.md §3 dayShotLastWords：白天被带走的 " + shot.target() + " 号应有遗言；实际 " + shot.granted());
        assertEquals(driver.state(), replayPrefix(driver.stream, driver.stream.size()).snapshot());
    }

    @Test
    @DisplayName("dayShotLastWords=false 时白天被带走者按后续夜规则没有遗言（旧对局的行为）")
    void dayShotLastWordsCanBeDisabled() {
        DayShot shot = driveDayTwoShot(dayShotDriver(withRules(false, GameRules.DEFAULT.stalledRoundsLimit())));

        assertTrue(shot.granted().contains(shot.hunter()));
        assertFalse(shot.granted().contains(shot.target()), "实际 " + shot.granted());
    }

    @Test
    @DisplayName("连续 3 轮无人出局（全员挂机）：第 3 轮结算后 GAME_ABORTED(STALLED)，且可重放")
    void stalledGameIsAbortedAfterThreeRoundsWithoutDeath() {
        Driver driver = new Driver();
        driver.wolfPicker = state -> null;
        driver.votePicker = (state, voter) -> null;
        driver.start();

        driver.driveUntilFinished();

        DomainEvent last = driver.stream.get(driver.stream.size() - 1);
        GameAbortedEvent aborted = assertInstanceOf(GameAbortedEvent.class, last, "终态事件必须是最后一条");
        assertEquals(Game.STALLED_REASON, aborted.reason());
        assertEquals(GameStatus.ABORTED, driver.state().status());
        assertEquals(Game.STALLED_REASON, driver.state().finishReason());
        assertEquals(GamePhase.GAME_OVER, driver.state().phase());
        assertEquals(3, driver.state().round());
        assertEquals(0, eventsByType(driver.stream, EventType.PLAYER_KILLED).size());
        assertEquals(driver.state(), replayPrefix(driver.stream, driver.stream.size()).snapshot());
    }

    @Test
    @DisplayName("停滞计数看最近 N 轮：首夜有人死亡时，第 4 轮结算后才中止")
    void stalledWindowRestartsAfterADeath() {
        Driver driver = new Driver();
        driver.wolfPicker = state -> state.round() == 1 ? lowestAliveNonWolf(state) : null;
        driver.votePicker = (state, voter) -> null;
        driver.start();

        driver.driveUntilFinished();

        assertEquals(GameStatus.ABORTED, driver.state().status());
        assertEquals(4, driver.state().round());
    }

    @Test
    @DisplayName("stalledRoundsLimit=0 不中止；旧开局事件缺该键时按 0 解码")
    void stalledAbortCanBeDisabled() {
        Driver driver = new Driver();
        driver.rules = withRules(GameRules.DEFAULT.dayShotLastWords(), 0);
        driver.wolfPicker = state -> null;
        driver.votePicker = (state, voter) -> null;
        driver.start();
        while (driver.state().round() < 6) {
            driver.step();
        }
        assertEquals(GameStatus.RUNNING, driver.state().status());

        Driver fresh = new Driver();
        fresh.start();
        EventEnvelope current = EventEnvelope.of(fresh.stream.get(0), GAME_ID.value(), 1);
        Map<String, Object> rules = new LinkedHashMap<>(asStringMap(current.payload().get("rules")));
        assertEquals(3, rules.remove("stalledRoundsLimit"));
        Map<String, Object> payload = new LinkedHashMap<>(current.payload());
        payload.put("rules", rules);
        EventEnvelope legacy = new EventEnvelope(current.eventId(), current.gameId(), current.version(),
                current.type(), current.schemaVersion(), current.occurredAt(), current.phase(), current.round(),
                current.actorId(), current.visibility(), current.causationId(), current.correlationId(), payload);
        assertEquals(0, ((GameStartedEvent) EventCodec.decode(legacy)).rules().stalledRoundsLimit());
    }

    private static GameRules withRules(boolean dayShotLastWords, int stalledRoundsLimit) {
        GameRules d = GameRules.DEFAULT;
        return new GameRules(d.speechMaxLength(), d.turnDeadlineMs(), d.agentTurnDeadlineMs(),
                d.voteDeadlineMs(), d.firstNightLastWords(), d.laterNightLastWords(), d.exileLastWords(),
                dayShotLastWords, d.witchSelfSave(), d.witchSelfSaveFirstNightOnly(),
                d.witchSaveTargetMustBeKilled(), d.allowWolfEmptyKill(),
                d.wolfKillTieBreak(), d.voteTieRule(), d.hunterCanShootWhenPoisoned(), d.winCondition(),
                d.abstainAllowed(), stalledRoundsLimit);
    }

    @Test
    @DisplayName("默认规则：女巫首夜可以自救，第二夜起不可；witchSelfSave=false 时全程不可")
    void witchSelfSaveDefaultsToFirstNightOnly() {
        GameRules d = GameRules.DEFAULT;
        assertTrue(d.witchCanSelfSave(1), "主流九人局：女巫首夜可以自救");
        assertFalse(d.witchCanSelfSave(2), "主流九人局：第二夜起不可自救");
        assertEquals(WinCondition.SIDE_KILL, d.winCondition(), "主流九人局：屠边");

        GameRules never = new GameRules(d.speechMaxLength(), d.turnDeadlineMs(), d.agentTurnDeadlineMs(),
                d.voteDeadlineMs(), d.firstNightLastWords(), d.laterNightLastWords(), d.exileLastWords(),
                d.dayShotLastWords(), false, true, d.witchSaveTargetMustBeKilled(), d.allowWolfEmptyKill(),
                d.wolfKillTieBreak(), d.voteTieRule(), d.hunterCanShootWhenPoisoned(), d.winCondition(),
                d.abstainAllowed(), d.stalledRoundsLimit());
        assertFalse(never.witchCanSelfSave(1));
    }

    @Test
    @DisplayName("首夜被刀的女巫可以自救：领域接受 WITCH_SAVE 自己，当夜无人死亡")
    void witchSavesHerselfOnFirstNight() {
        Driver driver = new Driver();
        driver.wolfPicker = state -> seatOfRole(state, Role.WITCH).value();
        driver.witchPicker = (state, witch) -> WitchPlan.save(state.wolfKillTarget());
        driver.start();
        driver.driveTo(GamePhase.DAY_DISCUSS);

        SeatNo witch = holderOf(driver.state(), Role.WITCH);
        assertTrue(driver.stream.stream().anyMatch(event -> event.type() == EventType.WITCH_SAVED),
                "首夜女巫对自己使用解药必须被领域接受");
        assertTrue(driver.state().playerAt(witch).alive(), "首夜自救后女巫仍存活");
        assertTrue(eventsOf(driver.stream, PlayerKilledEvent.class).isEmpty(), "首夜自救：当夜无人死亡");
    }

    @Test
    @DisplayName("第二夜被刀的女巫不能自救：WITCH_SAVE 自己被拒且零事件")
    void witchCannotSaveHerselfAfterFirstNight() {
        Driver driver = new Driver();
        driver.wolfPicker = state -> state.round() == 1 ? null : seatOfRole(state, Role.WITCH).value();
        driver.votePicker = (state, voter) -> null;
        driver.start();
        driver.driveTo(GamePhase.NIGHT_WITCH);
        driver.step();
        driver.driveTo(GamePhase.NIGHT_WITCH);
        assertEquals(2, driver.state().round());

        SeatNo witch = holderOf(driver.state(), Role.WITCH);
        assertEquals(Integer.valueOf(witch.value()), driver.state().wolfKillTarget(), "第二夜狼刀女巫");
        int versionBefore = driver.game.version();
        GameException exception = assertThrows(GameException.class,
                () -> driver.game.handle(WitchAction.save(witch.value(), witch.value()), driver.context()));
        assertEquals(DomainErrorCode.ACTION_NOT_ALLOWED, exception.code());
        assertEquals(versionBefore, driver.game.version(), "被拒绝的命令不产生事件");
    }

    @Test
    @DisplayName("旧对局的开局事件没有 witchSelfSaveFirstNightOnly：按 false 解码（当时 witchSelfSave=true 即全程可自救）")
    void legacyGameStartedDecodesWitchSelfSaveFirstNightOnlyAsFalse() {
        Driver driver = new Driver();
        driver.start();
        EventEnvelope current = EventEnvelope.of(driver.stream.get(0), GAME_ID.value(), 1);
        Map<String, Object> rules = new LinkedHashMap<>(asStringMap(current.payload().get("rules")));
        assertEquals(true, rules.remove("witchSelfSaveFirstNightOnly"));
        Map<String, Object> payload = new LinkedHashMap<>(current.payload());
        payload.put("rules", rules);
        EventEnvelope legacy = new EventEnvelope(current.eventId(), current.gameId(), current.version(),
                current.type(), current.schemaVersion(), current.occurredAt(), current.phase(), current.round(),
                current.actorId(), current.visibility(), current.causationId(), current.correlationId(), payload);

        assertFalse(((GameStartedEvent) EventCodec.decode(legacy)).rules().witchSelfSaveFirstNightOnly());
        assertTrue(((GameStartedEvent) EventCodec.decode(current)).rules().witchSelfSaveFirstNightOnly());
    }

    @Test
    @DisplayName("旧对局的开局事件没有 dayShotLastWords：按 false 解码，重放结果与当时一致")
    void legacyGameStartedDecodesDayShotLastWordsAsFalse() {
        Driver driver = new Driver();
        driver.start();
        EventEnvelope current = EventEnvelope.of(driver.stream.get(0), GAME_ID.value(), 1);
        Map<String, Object> rules = new LinkedHashMap<>(asStringMap(current.payload().get("rules")));
        assertEquals(true, rules.remove("dayShotLastWords"));
        Map<String, Object> payload = new LinkedHashMap<>(current.payload());
        payload.put("rules", rules);
        EventEnvelope legacy = new EventEnvelope(current.eventId(), current.gameId(), current.version(),
                current.type(), current.schemaVersion(), current.occurredAt(), current.phase(), current.round(),
                current.actorId(), current.visibility(), current.causationId(), current.correlationId(), payload);

        GameStartedEvent decoded = (GameStartedEvent) EventCodec.decode(legacy);

        assertFalse(decoded.rules().dayShotLastWords());
        assertTrue(((GameStartedEvent) EventCodec.decode(current)).rules().dayShotLastWords());
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asStringMap(Object value) {
        return (Map<String, Object>) value;
    }

    private record DayShot(int hunter, int target, List<Integer> granted) {}

    /** 首夜刀一名非猎人好人、之后空刀；首日全体弃权，次日全体投猎人；猎人开枪带走最小号存活狼人。 */
    private static Driver dayShotDriver(GameRules rules) {
        Driver driver = new Driver();
        driver.rules = rules;
        driver.wolfPicker = state -> state.round() == 1
                ? state.players().stream()
                        .filter(PlayerSnapshot::alive)
                        .filter(player -> !player.isWolf() && player.role() != Role.HUNTER)
                        .map(player -> player.seatNo().value())
                        .min(Integer::compareTo)
                        .orElseThrow()
                : null;
        driver.votePicker = (state, voter) -> {
            int hunter = seatOfRole(state, Role.HUNTER).value();
            return state.round() == 1 || voter.value() == hunter ? null : hunter;
        };
        driver.hunterPicker = (state, hunter) -> lowestAliveWolf(state);
        return driver;
    }

    private static DayShot driveDayTwoShot(Driver driver) {
        driver.start();
        driver.driveTo(GamePhase.DAY_VOTE);
        driver.drivePastVote();
        driver.driveTo(GamePhase.DAY_VOTE);
        assertEquals(2, driver.state().round(), "前置条件：首日全体弃权，不放逐");
        int hunter = seatOfRole(driver.state(), Role.HUNTER).value();
        int voteStart = driver.stream.size();

        driver.driveTo(GamePhase.NIGHT_WOLF);

        HunterShotEvent shot = eventsOf(driver.stream.subList(voteStart, driver.stream.size()), HunterShotEvent.class)
                .stream().findFirst()
                .orElseThrow(() -> new AssertionError("前置条件：猎人被放逐后应开枪"));
        return new DayShot(hunter, shot.targetSeatNo().value(),
                lastWordsGrantedSeats(driver.stream.subList(voteStart, driver.stream.size())));
    }

    private static List<Integer> lastWordsGrantedSeats(List<DomainEvent> events) {
        return eventsOf(events, com.wolfmind.domain.game.event.TurnGrantedEvent.class).stream()
                .filter(grant -> grant.turnPhase() == GamePhase.LAST_WORDS)
                .map(grant -> grant.seatNo().value())
                .toList();
    }

    private static int lowestAliveWolf(GameSnapshot state) {
        return state.players().stream()
                .filter(PlayerSnapshot::alive)
                .filter(PlayerSnapshot::isWolf)
                .map(player -> player.seatNo().value())
                .min(Integer::compareTo)
                .orElseThrow();
    }

    @Test
    @DisplayName("重放到 LAST_WORDS / HUNTER_SHOOT 时必须还原路由字段（INV-16，应用层每次 load 都依赖它）")
    void replayRestoresPhaseRoutingFields() {
        // 应用层在"还没有快照"时每次命令都靠全量重放重建聚合（InMemoryGameRepository.load），
        // 因此"重放到中间状态"必须是合法的聚合状态，不能丢字段。
        Driver lastWordsDriver = new Driver();
        lastWordsDriver.start();
        lastWordsDriver.driveTo(GamePhase.LAST_WORDS);
        Game lastWordsReplay = replayPrefix(lastWordsDriver.stream, lastWordsDriver.stream.size());

        Driver hunterDriver = new Driver();
        hunterDriver.wolfPicker = GameSimulationTest::hunterFirstThenLowest;
        hunterDriver.start();
        hunterDriver.driveTo(GamePhase.HUNTER_SHOOT);
        Game hunterReplay = replayPrefix(hunterDriver.stream, hunterDriver.stream.size());

        assertAll(
                () -> assertEquals(lastWordsDriver.state().pendingAfterLastWords(),
                        lastWordsReplay.snapshot().pendingAfterLastWords(),
                        "ddd-design.md §4.2 INV-16 与 event-design.md §11：重放到 LAST_WORDS 时必须还原"
                                + " pendingAfterLastWords（遗言结束后回到哪里）。它在命令路径的 transition() 里赋值、"
                                + "没有进事件，因此靠重放重建的聚合会得到 null，"
                                + "随后 finishLastWords 会把「遗言结束」误判成「进入下一轮夜晚」，跳过整个白天"),
                () -> assertEquals(hunterDriver.state().pendingHunter(),
                        hunterReplay.snapshot().pendingHunter(),
                        "ddd-design.md §4.2 INV-16 与 event-design.md §11：重放到 HUNTER_SHOOT 时必须还原"
                                + " pendingHunter，否则 finishHunterPhase 会在 pendingHunter == null 时直接收尾，"
                                + "猎人开枪被静默跳过"));
    }

    /** 把事件流的前 {@code count} 条重放进一个新聚合。 */
    private static Game replayPrefix(List<DomainEvent> stream, int count) {
        Game replayed = Game.create(GAME_ID);
        for (int index = 0; index < count; index++) {
            replayed.replay(stream.get(index));
        }
        return replayed;
    }

    // ------------------------------------------------------------------
    // 7. 超时兜底
    // ------------------------------------------------------------------

    @Test
    @DisplayName("投票阶段超时：未投票者逐个转成弃权 SkipAction，计票结果为 ALL_ABSTAIN 且无人出局")
    void votePhaseTimeoutBecomesAbstainForEveryPendingSeat() {
        Driver driver = new Driver();
        driver.wolfPicker = state -> null;
        driver.start();
        driver.driveTo(GamePhase.DAY_VOTE);

        List<SeatNo> awaiting = driver.game.seatsAwaitingVote();
        assertFalse(awaiting.isEmpty(), "前置条件：投票阶段必须还有未投票的存活座位");

        List<SkipAction> skips = driver.game.skipActionsOnTimeout(Long.MAX_VALUE, true);
        assertEquals(awaiting.size(), skips.size(),
                "ddd-design.md §3.2：超时是合法输入，未投票的存活玩家每人一条 SkipAction；"
                        + "期望 " + awaiting.size() + " 条，实际 " + skips.size());
        assertEquals(awaiting, skips.stream().map(SkipAction::actor).toList(),
                "domain-model.md §4（DAY_VOTE 超时行为）：未提交者记为弃权票，覆盖每个未投票存活座位");
        assertTrue(skips.stream().allMatch(skip -> skip.reason() == SkipReason.TIMEOUT),
                "domain-model.md §5.4：超时兜底必须用 SkipReason.TIMEOUT，而不是 VOLUNTARY");

        int versionBefore = driver.game.version();
        for (SkipAction skip : skips) {
            driver.submit(skip, skip.actor());
        }
        List<DomainEvent> fallbackSlice = driver.stream.subList(versionBefore, driver.stream.size());
        assertEquals(awaiting.size(),
                fallbackSlice.stream().filter(event -> event.type() == EventType.PLAYER_VOTED).count(),
                "domain-model.md §6.1 V6 与 §7：每条超时弃权票必须产生一条 PLAYER_VOTED，"
                        + "超时不得被静默忽略");
        assertEquals(1,
                fallbackSlice.stream().filter(event -> event.type() == EventType.VOTE_TALLY_RESOLVED).count(),
                "domain-model.md §6.2：全员提交后 VOTE_SETTLE 恰好产生一条 VOTE_TALLY_RESOLVED");

        VoteTallyResolvedEvent tally = eventsOf(driver.stream, VoteTallyResolvedEvent.class)
                .get(eventsOf(driver.stream, VoteTallyResolvedEvent.class).size() - 1);
        assertEquals(TallyResult.Result.ALL_ABSTAIN, tally.result(),
                "event-design.md §7.2：全员弃权时 result 必须是 ALL_ABSTAIN");
        assertNull(tally.exiledPlayerId(), "domain-model.md §6.2：无人出局");
        assertEquals(0, eventsByType(driver.stream, EventType.PLAYER_KILLED).size(),
                "domain-model.md §6.2：全员弃权不出局，不得产生 PLAYER_KILLED");
        assertEquals(GamePhase.NIGHT_WOLF, driver.game.phase(),
                "ddd-design.md §3.2：VOTE_SETTLE 无人出局时进入下一轮 NIGHT_WOLF");
    }

    @Test
    @DisplayName("各阶段超时都产生事件，且含超时兜底的事件流重放后状态一致")
    void timeoutFallbacksProduceEventsAndReplayDeterministically() {
        Driver driver = new Driver();
        driver.start();

        // NIGHT_WOLF：未提名的存活狼人各一条弃刀
        List<SkipAction> wolfSkips = driver.game.skipActionsOnTimeout(Long.MAX_VALUE, true);
        assertEquals(driver.game.wolvesAwaitingNomination().size(), wolfSkips.size(),
                "domain-model.md §4（NIGHT_WOLF 超时行为）：未提交者视为弃刀，每人一条 SkipAction");
        int beforeWolfSkips = driver.game.version();
        for (SkipAction skip : wolfSkips) {
            driver.submit(skip, skip.actor());
        }
        assertTrue(driver.game.version() > beforeWolfSkips,
                "ddd-design.md §3.2：超时兜底必须产生事件，不能被静默忽略");
        assertEquals(EventType.WOLF_KILL_NOMINATED,
                driver.stream.get(beforeWolfSkips).type(),
                "domain-model.md §7：SkipAction | WEREWOLF | NIGHT_WOLF 产生弃刀提名事件");

        // 白天回合：Turn.deadline 到期后返回当前回合持有者的 SkipAction
        driver.driveTo(GamePhase.DAY_DISCUSS);
        Turn turn = driver.state().currentTurn();
        assertNotNull(turn, "domain-model.md §4：DAY_DISCUSS 必须发放回合窗口");
        assertTrue(driver.game.skipActionsOnTimeout(turn.deadline() - 1, false).isEmpty(),
                "domain-model.md §5.4：回合窗口未到期时不得产生兜底行动");
        List<SkipAction> speechSkips = driver.game.skipActionsOnTimeout(turn.deadline(), false);
        assertEquals(List.of(turn.seatNo()), speechSkips.stream().map(SkipAction::actor).toList(),
                "domain-model.md §4（DAY_DISCUSS 超时行为）：未发言者 SPEECH_SKIPPED，发放下一回合");
        int beforeSpeechSkip = driver.game.version();
        driver.submit(speechSkips.get(0), turn.seatNo());
        assertEquals(EventType.SPEECH_SKIPPED, driver.stream.get(beforeSpeechSkip).type(),
                "domain-model.md §7：SkipAction 在当前回合产生 SPEECH_SKIPPED");

        driver.driveUntilFinished();
        assertVersionStreamIsContiguous(driver.stream);

        // 含超时兜底的事件流同样必须可确定性重放（INV-16）
        Game replayed = Game.create(GAME_ID);
        for (DomainEvent event : driver.stream) {
            replayed.replay(event);
        }
        assertEquals(driver.game.snapshot(), replayed.snapshot(),
                "ddd-design.md §4.2 INV-16：超时兜底同样是领域事件，重放后状态必须逐字段一致");
    }

    // ------------------------------------------------------------------
    // 夹具
    // ------------------------------------------------------------------

    private static PlayerSnapshot playerSnapshot(int seatNo, Role role, boolean alive) {
        return new PlayerSnapshot(
                PlayerId.forSeat(SeatNo.of(seatNo)),
                SeatNo.of(seatNo),
                role,
                role.camp(),
                alive,
                PlayerController.bot("bot"),
                true,
                SkillState.forRole(role));
    }
}
