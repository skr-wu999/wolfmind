package com.wolfmind.domain.game;

import com.wolfmind.domain.game.event.DeathAnnouncedEvent;
import com.wolfmind.domain.game.event.DomainEvent;
import com.wolfmind.domain.game.event.EventType;
import com.wolfmind.domain.game.event.GameFinishedEvent;
import com.wolfmind.domain.game.event.PhaseChangedEvent;
import com.wolfmind.domain.game.event.PlayerVotedEvent;
import com.wolfmind.domain.game.event.RoleAssignedEvent;
import com.wolfmind.domain.game.event.VoteTallyResolvedEvent;
import com.wolfmind.domain.game.event.WolfKillNominatedEvent;
import com.wolfmind.domain.game.event.WolfKillSelectedEvent;
import com.wolfmind.domain.game.model.ActionContext;
import com.wolfmind.domain.game.model.Camp;
import com.wolfmind.domain.game.model.Game;
import com.wolfmind.domain.game.model.GameId;
import com.wolfmind.domain.game.model.GamePhase;
import com.wolfmind.domain.game.model.GameStatus;
import com.wolfmind.domain.game.model.PlayerSnapshot;
import com.wolfmind.domain.game.model.Role;
import com.wolfmind.domain.game.model.RoleComposition;
import com.wolfmind.domain.game.model.SeatAssignment;
import com.wolfmind.domain.game.model.SeatNo;
import com.wolfmind.domain.game.model.SkipAction;
import com.wolfmind.domain.game.model.StartGameCommand;
import com.wolfmind.domain.game.model.VoteAction;
import com.wolfmind.domain.game.model.WitchAction;
import com.wolfmind.domain.game.model.WolfKillAction;
import com.wolfmind.domain.game.model.SeerInspectAction;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 领域主流程冒烟测试：先证明"路径是通的"，细节边界由 verifier 的独立测试覆盖。
 */
class GameFlowSmokeTest {

    private static final long SEED = 20240501L;
    private static final long T0 = 1_730_000_000_000L;

    private static StartGameCommand command(GameId gameId) {
        List<SeatAssignment> seats = new ArrayList<>();
        for (int seat = 1; seat <= 9; seat++) {
            seats.add(SeatAssignment.ai(seat, "agent_" + seat));
        }
        return new StartGameCommand(
                gameId, "room_1", seats, RoleComposition.STANDARD_NINE,
                com.wolfmind.domain.game.model.GameRules.DEFAULT);
    }

    private static ActionContext ctx(int expectedVersion) {
        return new ActionContext(
                "cmd_" + expectedVersion, T0 + expectedVersion, "SYSTEM",
                "cmd_" + expectedVersion, "cor_1", expectedVersion, SEED);
    }

    private static int seatOfRole(Game game, Role role) {
        return game.snapshot().players().stream()
                .filter(player -> player.role() == role)
                .findFirst()
                .orElseThrow()
                .seatNo()
                .value();
    }

    @Test
    @DisplayName("开局产生 11 条事件且版本连续：GAME_STARTED + 9×ROLE_ASSIGNED + PHASE_CHANGED")
    void startProducesElevenEvents() {
        GameId gameId = GameId.of("g_smoke_1");
        Game game = Game.create(gameId);
        List<DomainEvent> events = game.start(command(gameId), ctx(0));

        assertEquals(11, events.size(), "开局事件数必须是 11 条（system-design.md §3.1）");
        for (int index = 0; index < events.size(); index++) {
            assertEquals(index + 1, events.get(index).version(), "版本必须从 1 连续递增");
        }
        assertEquals(EventType.GAME_STARTED, events.get(0).type());
        assertEquals(EventType.PHASE_CHANGED, events.get(10).type());
        assertEquals(GamePhase.NIGHT_WOLF, game.phase());
        assertEquals(GameStatus.RUNNING, game.status());

        long wolves = game.snapshot().players().stream().filter(PlayerSnapshot::isWolf).count();
        assertEquals(3, wolves, "标准局狼人恰好 3 名");
        for (Role role : List.of(Role.SEER, Role.WITCH, Role.HUNTER)) {
            long count = game.snapshot().players().stream().filter(p -> p.role() == role).count();
            assertEquals(1, count, role + " 恰好 1 名");
        }
        assertTrue(events.get(1) instanceof RoleAssignedEvent);
    }

    @Test
    @DisplayName("同一 seed 的开局角色分配可重放：结果只来自事件，不重新随机")
    void startIsDeterministic() {
        GameId gameId = GameId.of("g_smoke_2");
        Game first = Game.create(gameId);
        List<DomainEvent> events = first.start(command(gameId), ctx(0));

        Game replayed = Game.create(gameId);
        events.forEach(replayed::replay);

        assertEquals(
                first.snapshot().players().stream().map(PlayerSnapshot::role).toList(),
                replayed.snapshot().players().stream().map(PlayerSnapshot::role).toList());
        assertEquals(first.version(), replayed.version());
    }

    @Test
    @DisplayName("夜间链路：狼刀定案 → 预言家查验 → 女巫用药 → 结算 → 公布死讯 → 遗言")
    void nightChainResolves() {
        GameId gameId = GameId.of("g_smoke_3");
        Game game = Game.create(gameId);
        game.start(command(gameId), ctx(0));

        int wolf1 = game.snapshot().players().stream()
                .filter(p -> p.role() == Role.WEREWOLF)
                .mapToInt(p -> p.seatNo().value())
                .sorted()
                .findFirst()
                .orElseThrow();
        int seer = seatOfRole(game, Role.SEER);
        int witch = seatOfRole(game, Role.WITCH);
        // 刀口选一名非狼人座位，避免 INV-6 与"刀自己"的歧义
        int victim = game.snapshot().players().stream()
                .filter(p -> !p.isWolf() && p.seatNo().value() != seer && p.seatNo().value() != witch)
                .mapToInt(p -> p.seatNo().value())
                .sorted()
                .findFirst()
                .orElseThrow();

        // 三名狼人全部提名同一目标
        for (int wolfSeat : game.snapshot().players().stream()
                .filter(PlayerSnapshot::isWolf)
                .mapToInt(p -> p.seatNo().value())
                .sorted()
                .toArray()) {
            game.handle(WolfKillAction.at(wolfSeat, victim), ctx(game.version()));
        }

        assertEquals(GamePhase.NIGHT_SEER, game.phase(), "狼刀定案后进入 NIGHT_SEER");
        assertTrue(game.pendingEvents().stream().anyMatch(e -> e.type() == EventType.WOLF_KILL_SELECTED));

        game.handle(SeerInspectAction.of(seer, wolf1), ctx(game.version()));
        assertEquals(GamePhase.NIGHT_WITCH, game.phase());
        assertTrue(game.pendingEvents().stream().anyMatch(e -> e.type() == EventType.SEER_INSPECTED));

        game.handle(WitchAction.save(witch, victim), ctx(game.version()));
        assertTrue(game.pendingEvents().stream().anyMatch(e -> e.type() == EventType.WITCH_SAVED));

        // 解药生效：NIGHT_SETTLE → DAY_ANNOUNCE → LAST_WORDS（本轮无死亡则直接通过）→ DAY_DISCUSS
        assertEquals(GamePhase.DAY_DISCUSS, game.phase(), "平安夜没有遗言，直接进入白天讨论");
        DeathAnnouncedEvent announced = game.pendingEvents().stream()
                .filter(DeathAnnouncedEvent.class::isInstance)
                .map(DeathAnnouncedEvent.class::cast)
                .findFirst()
                .orElseThrow();
        assertTrue(announced.peacefulNight(), "解药救下刀口，应是平安夜");
        assertTrue(game.snapshot().playerAt(SeatNo.of(victim)).alive(), "被救者必须存活");
    }

    @Test
    @DisplayName("白天投票放逐：计票产生 EXILE，被放逐者死亡并进入遗言")
    void dayVoteExiles() {
        Game game = newGameWithPresetRoles();
        int wolf = seatOfRole(game, Role.WEREWOLF);
        runNightWithoutDeaths(game);

        // 白天讨论：存活玩家全部跳过
        while (game.phase() == GamePhase.DAY_DISCUSS) {
            int speaker = game.snapshot().currentTurn().seatNo().value();
            game.handle(SkipAction.voluntary(speaker), ctx(game.version()));
        }
        assertEquals(GamePhase.DAY_VOTE, game.phase());

        // 除狼人外全部投狼人，狼人弃权。
        // 注意：pendingEvents() 只含"最近一次调用"产生的事件，因此这里跨调用累计。
        List<DomainEvent> emitted = new ArrayList<>();
        for (PlayerSnapshot player : game.snapshot().players()) {
            if (!player.alive()) {
                continue;
            }
            int seat = player.seatNo().value();
            game.handle(
                    seat == wolf ? VoteAction.abstain(seat) : VoteAction.to(seat, wolf),
                    ctx(game.version()));
            emitted.addAll(game.pendingEvents());
        }

        long votes = emitted.stream().filter(PlayerVotedEvent.class::isInstance).count();
        assertEquals(9, votes, "九名存活玩家各产生一条 PLAYER_VOTED");

        VoteTallyResolvedEvent tally = emitted.stream()
                .filter(VoteTallyResolvedEvent.class::isInstance)
                .map(VoteTallyResolvedEvent.class::cast)
                .findFirst()
                .orElseThrow(() -> new AssertionError("必须产生 VOTE_TALLY_RESOLVED"));
        assertEquals(com.wolfmind.domain.game.model.TallyResult.Result.EXILE, tally.result());
        assertEquals(
                wolf,
                Integer.parseInt(tally.exiledPlayerId().value().substring(2)),
                "被放逐者应是得票最高的狼人");
        assertFalse(game.snapshot().playerAt(SeatNo.of(wolf)).alive(), "被放逐者必须死亡");
        assertTrue(
                emitted.stream().anyMatch(e -> e.type() == EventType.PLAYER_KILLED),
                "放逐必须产生 PLAYER_KILLED（JUDGE 可见的死亡事实）");
    }

    @Test
    @DisplayName("夜里出局的猎人在死讯公布后开枪：同夜被毒者已出局，不在可选目标里")
    void nightHunterShootsAfterDeathAnnouncement() {
        Game game = newGameWithPresetRoles();
        List<DomainEvent> committed = new ArrayList<>(game.pendingEvents());
        int hunter = seatOfRole(game, Role.HUNTER);
        int seer = seatOfRole(game, Role.SEER);
        int witch = seatOfRole(game, Role.WITCH);
        int poisoned = seatOfRole(game, Role.VILLAGER);
        for (int wolf : game.snapshot().players().stream().filter(PlayerSnapshot::isWolf)
                .mapToInt(player -> player.seatNo().value()).toArray()) {
            game.handle(WolfKillAction.at(wolf, hunter), ctx(game.version()));
            committed.addAll(game.pendingEvents());
        }
        game.handle(SkipAction.voluntary(seer), ctx(game.version()));
        committed.addAll(game.pendingEvents());
        game.handle(WitchAction.poison(witch, poisoned), ctx(game.version()));
        List<DomainEvent> settle = game.pendingEvents();
        committed.addAll(settle);
        assertEquals(GamePhase.HUNTER_SHOOT, game.phase());

        int announcedAt = indexOf(settle, EventType.DEATH_ANNOUNCED);
        int shootAt = -1;
        for (int index = 0; index < settle.size(); index++) {
            if (settle.get(index) instanceof PhaseChangedEvent changed && changed.to() == GamePhase.HUNTER_SHOOT) {
                shootAt = index;
            }
        }
        assertTrue(announcedAt >= 0 && announcedAt < shootAt, "死讯必须先于开枪阶段公布：" + settle);
        DeathAnnouncedEvent announced = (DeathAnnouncedEvent) settle.get(announcedAt);
        assertEquals(List.of(hunter, poisoned).stream().sorted().toList(),
                announced.deaths().stream().map(death -> death.seatNo().value()).toList());

        var spectator = com.wolfmind.domain.game.model.ViewerContext.spectator("observer");
        var view = com.wolfmind.domain.game.view.GameViewProjector.project(game.snapshot(), spectator);
        assertFalse(view.players().stream().filter(player -> player.seatNo() == hunter).findFirst().orElseThrow().alive());
        assertFalse(view.players().stream().filter(player -> player.seatNo() == poisoned).findFirst().orElseThrow().alive());
        assertTrue(view.players().stream().allMatch(player -> player.role() == null && player.skillState() == null));

        var hunterId = game.snapshot().playerAt(SeatNo.of(hunter)).playerId();
        var hunterView = com.wolfmind.domain.game.view.GameViewProjector.project(game.snapshot(),
                com.wolfmind.domain.game.model.ViewerContext.participant("u_hunter", java.util.Set.of(hunterId), java.util.Set.of(), false));
        List<Integer> targets = hunterView.permissions().get(0).targets();
        assertFalse(targets.contains(poisoned), "同夜被毒者已公布出局，不能是开枪目标：" + targets);

        Game recovered = Game.create(game.snapshot().gameId());
        committed.forEach(recovered::replay);
        assertEquals(game.snapshot(), recovered.snapshot(), "开枪阶段的状态必须与重放一致");
        game.handle(SkipAction.voluntary(hunter), ctx(game.version()));
        game.pendingEvents().forEach(recovered::replay);
        assertEquals(game.snapshot(), recovered.snapshot(), "放弃开枪后的完整状态必须与重放一致");
    }

    private static int indexOf(List<DomainEvent> events, EventType type) {
        for (int index = 0; index < events.size(); index++) {
            if (events.get(index).type() == type) {
                return index;
            }
        }
        return -1;
    }

    @Test
    @DisplayName("阶段转换表外跳转被拒绝，且拒绝不产生任何事件")
    void illegalTransitionRejected() {
        GameId gameId = GameId.of("g_smoke_5");
        Game game = Game.create(gameId);
        game.start(command(gameId), ctx(0));

        assertEquals(GamePhase.NIGHT_WOLF, game.phase());
        int before = game.version();
        // 村民提交狼刀：角色不允许
        int villager = game.snapshot().players().stream()
                .filter(p -> p.role() == Role.VILLAGER)
                .mapToInt(p -> p.seatNo().value())
                .findFirst()
                .orElseThrow();
        try {
            game.handle(WolfKillAction.at(villager, 1), ctx(game.version()));
            assertTrue(false, "村民提交狼刀必须被拒绝");
        } catch (com.wolfmind.domain.game.model.GameException exception) {
            assertEquals(
                    com.wolfmind.domain.game.model.DomainErrorCode.ACTION_NOT_ALLOWED, exception.code());
        }
        assertEquals(before, game.version(), "被拒绝的行动不得改变版本（INV-11）");
    }

    // ------------------------------------------------------------------
    // 测试辅助
    // ------------------------------------------------------------------

    @Test
    @DisplayName("整局实时处理与全量重放得到逐字段相同的快照（INV-16）")
    void liveAndReplayAgree() {
        GameId gameId = GameId.of("g_smoke_replay");
        Game live = Game.create(gameId);
        List<DomainEvent> stream = new ArrayList<>(live.start(command(gameId), ctx(0)));

        int guard = 0;
        while (live.status() == GameStatus.RUNNING && guard++ < 400) {
            // 每次调用都按当时的 version 重建 ctx：expectedVersion 是乐观并发前置条件，
            // 复用旧 ctx 会被领域拒绝（GAME_VERSION_CONFLICT），这与生产路径一致。
            switch (live.phase()) {
                case NIGHT_WOLF -> {
                    for (int wolf : live.snapshot().players().stream()
                            .filter(p -> p.alive() && p.isWolf())
                            .mapToInt(p -> p.seatNo().value())
                            .sorted()
                            .toArray()) {
                        if (live.phase() != GamePhase.NIGHT_WOLF) {
                            break;
                        }
                        stream.addAll(live.handle(WolfKillAction.empty(wolf), ctx(live.version())));
                    }
                    stream.addAll(live.advance(ctx(live.version())));
                }
                case NIGHT_SEER, NIGHT_WITCH, HUNTER_SHOOT -> {
                    int seat = seatToAct(live);
                    if (seat < 0) {
                        stream.addAll(live.advance(ctx(live.version())));
                    } else {
                        stream.addAll(live.handle(SkipAction.voluntary(seat), ctx(live.version())));
                    }
                }
                case DAY_DISCUSS, LAST_WORDS -> {
                    int seat = seatToAct(live);
                    if (seat < 0) {
                        stream.addAll(live.advance(ctx(live.version())));
                    } else {
                        stream.addAll(live.handle(SkipAction.voluntary(seat), ctx(live.version())));
                    }
                }
                case DAY_VOTE -> {
                    int voter = live.seatsAwaitingVote().get(0).value();
                    int target = live.snapshot().players().stream()
                            .filter(PlayerSnapshot::alive)
                            .mapToInt(p -> p.seatNo().value())
                            .filter(candidate -> candidate != voter)
                            .findFirst()
                            .orElse(voter);
                    stream.addAll(live.handle(VoteAction.to(voter, target), ctx(live.version())));
                }
                default -> stream.addAll(live.advance(ctx(live.version())));
            }
        }

        assertEquals(GameStatus.FINISHED, live.status(), "脚本应当能把一局跑到结束");

        Game replayed = Game.create(gameId);
        stream.forEach(replayed::replay);

        assertEquals(live.snapshot(), replayed.snapshot(),
                "INV-16：同一事件流重放必须与实时处理逐字段一致");
        assertEquals(stream.size(), replayed.version(), "重放后的 version 等于事件流长度");
    }

    /** 当前应当表态的座位；没有待行动者时返回 {@code -1}，调用方改用 {@code advance} 推进。 */
    private static int seatToAct(Game game) {
        if (game.snapshot().currentTurn() != null) {
            return game.snapshot().currentTurn().seatNo().value();
        }
        if (game.snapshot().pendingHunter() != null) {
            return game.snapshot().pendingHunter();
        }
        // 只有"DoingPoint"存在时才由玩家/看护补一条 SkipAction；角色已死亡等情况下
        // 阶段会由 runUntilStable 自行通过，这里返回 -1 让调用方走 advance。
        return game.timeoutSkips(Long.MAX_VALUE, true).skips().stream()
                .mapToInt(skip -> skip.actor().value())
                .findFirst()
                .orElse(-1);
    }

    /** 构造一个角色可预测的对局：不使用洗牌，直接把指定 seed 的角色分配结果固定下来。 */
    private Game newGameWithPresetRoles() {
        GameId gameId = GameId.of("g_smoke_preset");
        Game game = Game.create(gameId);
        game.start(command(gameId), ctx(0));
        return game;
    }

    /** 走完一个无人死亡的夜晚：狼人空刀、预言家跳过、女巫跳过。 */
    private void runNightWithoutDeaths(Game game) {
        for (int seat : game.snapshot().players().stream()
                .filter(p -> p.role() == Role.WEREWOLF)
                .mapToInt(p -> p.seatNo().value())
                .sorted()
                .toArray()) {
            game.handle(WolfKillAction.empty(seat), ctx(game.version()));
        }
        assertEquals(GamePhase.NIGHT_SEER, game.phase(), "全员弃刀后仍进入 NIGHT_SEER");
        int seer = seatOfRole(game, Role.SEER);
        game.handle(SkipAction.voluntary(seer), ctx(game.version()));
        int witch = seatOfRole(game, Role.WITCH);
        game.handle(SkipAction.voluntary(witch), ctx(game.version()));
        assertNotNull(game.phase());
    }
}
