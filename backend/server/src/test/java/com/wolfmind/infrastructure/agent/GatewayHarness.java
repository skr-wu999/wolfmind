package com.wolfmind.infrastructure.agent;

import com.wolfmind.application.agent.model.AgentView;
import com.wolfmind.application.agent.model.ToolSession;
import com.wolfmind.application.game.ActionCodec;
import com.wolfmind.application.game.ActionResult;
import com.wolfmind.application.game.StartGameApplicationService;
import com.wolfmind.application.game.SubmitActionApplicationService;
import com.wolfmind.application.game.SubmitActionCommand;
import com.wolfmind.domain.game.event.EventEnvelope;
import com.wolfmind.domain.game.model.Game;
import com.wolfmind.domain.game.model.GameId;
import com.wolfmind.domain.game.model.GamePhase;
import com.wolfmind.domain.game.model.GameRules;
import com.wolfmind.domain.game.model.GameSnapshot;
import com.wolfmind.domain.game.model.GameStatus;
import com.wolfmind.domain.game.model.PlayerId;
import com.wolfmind.domain.game.model.PlayerSnapshot;
import com.wolfmind.domain.game.model.Role;
import com.wolfmind.domain.game.model.RoleComposition;
import com.wolfmind.domain.game.model.SeatAssignment;
import com.wolfmind.domain.game.model.SeatNo;
import com.wolfmind.domain.game.model.StartGameCommand;
import com.wolfmind.domain.game.model.Turn;
import com.wolfmind.infrastructure.memory.InMemoryEventPublisher;
import com.wolfmind.infrastructure.memory.InMemoryEventStore;
import com.wolfmind.infrastructure.memory.InMemoryGameRepository;
import com.wolfmind.infrastructure.memory.InMemoryGameStateCache;
import com.wolfmind.infrastructure.memory.InMemoryGameViewQuery;
import com.wolfmind.infrastructure.memory.InMemoryIdempotencyPort;
import com.wolfmind.infrastructure.memory.InMemoryLock;
import com.wolfmind.infrastructure.memory.InMemorySnapshotStore;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 工具网关的集成测试夹具：真实领域 + 真实应用层 + 内存适配器 + 网关。
 *
 * <p>为什么不全部用假端口：网关的价值在于"它真的能把合法动作送进领域、把非法动作挡在领域之前"，
 * 因此写路径、幂等、可见性、审计都要用真实聚合验证；只有"矩阵逐格"这类纯判定的测试才用合成视图。
 *
 * <p><b>座位装配约定</b>：AI 座位的 {@code controller.refId} 必须等于该座位的 {@code playerId}
 * （{@code p_N}）。应用层按 {@code actorId == controller.refId} 反查座位，而工具会话只有
 * {@code playerId}；两者不一致时应用层会以 {@code SEAT_NOT_YOURS} 拒绝（网关不猜身份）。
 * 这里按该约定装配，另有一条测试专门验证"不一致时不会被放行"。
 */
final class GatewayHarness {

    static final String GAME_ID = "g_gateway_1";
    static final String ROOM_ID = "room_gateway_1";
    /** 开局事件数：GAME_STARTED + 9×ROLE_ASSIGNED + PHASE_CHANGED。 */
    static final int STARTED_VERSION = 11;
    static final int MAX_DRIVE_STEPS = 200;

    final InMemoryEventStore store = new InMemoryEventStore();
    final InMemoryGameRepository repository;
    final InMemoryLock lock = new InMemoryLock();
    final InMemoryEventPublisher publisher = new InMemoryEventPublisher();
    final InMemoryIdempotencyPort idempotency = new InMemoryIdempotencyPort();
    final FakeGatewayTime time = new FakeGatewayTime(1_700_000_000_000L);
    final RecordingAgentTrace trace = new RecordingAgentTrace();
    final InMemoryGameViewQuery viewQuery;
    final AgentViewAdapter views;
    final SubmitActionApplicationService submitService;
    final StartGameApplicationService startService;

    private int driveSequence;

    GatewayHarness() {
        InMemorySnapshotStore snapshots = new InMemorySnapshotStore();
        InMemoryGameStateCache stateCache = new InMemoryGameStateCache();
        repository = new InMemoryGameRepository(store, snapshots, stateCache);
        ActionCodec codec = new ActionCodec();
        startService = new StartGameApplicationService(lock, store, repository, publisher, time);
        submitService = new SubmitActionApplicationService(codec, idempotency, lock, repository, publisher, time);
        viewQuery = new InMemoryGameViewQuery(store, repository);
        views = new AgentViewAdapter(viewQuery);

        ActionResult started = startService.start(
                new StartGameCommand(
                        GameId.of(GAME_ID), ROOM_ID, aiSeats(), RoleComposition.STANDARD_NINE, GameRules.DEFAULT),
                "u_owner", "cor_start");
        assertTrue(started.accepted(), "前置条件：九人局必须开局成功");
        assertEquals(STARTED_VERSION, started.version(), "前置条件：开局产生 11 条事件");
    }

    /** 另开一局：用于验证座位装配约定（{@code controller.refId} 与 {@code playerId} 不一致时不被放行）。 */
    void startGame(String gameId, List<SeatAssignment> seats) {
        ActionResult started = startService.start(
                new StartGameCommand(GameId.of(gameId), ROOM_ID + "_" + gameId, seats,
                        RoleComposition.STANDARD_NINE, GameRules.DEFAULT),
                "u_owner", "cor_start_" + gameId);
        assertTrue(started.accepted(), "第二局必须开局成功：" + gameId);
    }

    /** 九个 AI 座位，agentId == playerId（见类注释的装配约定）。 */
    static List<SeatAssignment> aiSeats() {
        List<SeatAssignment> seats = new ArrayList<>();
        for (int seat = 1; seat <= 9; seat++) {
            seats.add(SeatAssignment.ai(seat, PlayerId.forSeat(SeatNo.of(seat)).value()));
        }
        return seats;
    }

    ToolGatewayAdapter gateway() {
        return gateway(ToolGatewayConfig.DEFAULT);
    }

    ToolGatewayAdapter gateway(ToolGatewayConfig config) {
        return new ToolGatewayAdapter(views, viewQuery, submitService, idempotency, trace, time, config);
    }

    // ---------------------------------------------------------------- 状态与身份

    GameSnapshot state() {
        return repository.load(GAME_ID).game().snapshot();
    }

    Game game() {
        return repository.load(GAME_ID).game();
    }

    int version() {
        return repository.load(GAME_ID).version();
    }

    /** 事件流长度：拒绝与只读调用前后必须不变。 */
    int eventCount() {
        return store.read(GAME_ID, 1, Integer.MAX_VALUE).size();
    }

    List<EventEnvelope> events() {
        return store.read(GAME_ID, 1, Integer.MAX_VALUE);
    }

    String playerIdOf(int seat) {
        return PlayerId.forSeat(SeatNo.of(seat)).value();
    }

    int seatOfRole(Role role) {
        return state().players().stream()
                .filter(player -> player.role() == role)
                .map(player -> player.seatNo().value())
                .findFirst()
                .orElseThrow(() -> new AssertionError("对局里没有 " + role));
    }

    PlayerSnapshot playerOfRole(Role role) {
        return state().players().stream()
                .filter(player -> player.role() == role)
                .findFirst()
                .orElseThrow(() -> new AssertionError("对局里没有 " + role));
    }

    AgentView view(int seat) {
        AgentView view = views.viewFor(GAME_ID, playerIdOf(seat));
        assertNotNull(view, "座位 " + seat + " 必须有视图");
        return view;
    }

    AgentView viewOf(String gameId, String playerId) {
        return views.viewFor(gameId, playerId);
    }

    int eventCountOf(String gameId) {
        return store.read(gameId, 1, Integer.MAX_VALUE).size();
    }

    /** 该座位的工具会话：身份与版本取自视图，deadline 在将来（预算不因超时失败）。 */
    ToolSession session(int seat) {
        AgentView view = view(seat);
        return new ToolSession(
                GAME_ID, playerIdOf(seat), seat, view.me().role(), view.phase(), view.version(),
                time.nowMillis() + 20_000L, 6);
    }

    ToolSession sessionOf(String gameId, String playerId, int seat) {
        AgentView view = views.viewFor(gameId, playerId);
        assertNotNull(view, playerId + " 必须有视图");
        return new ToolSession(
                gameId, playerId, seat, view.me().role(), view.phase(), view.version(),
                time.nowMillis() + 20_000L, 6);
    }

    ToolSession session(int seat, int turnVersion, String phase) {
        return new ToolSession(
                GAME_ID, playerIdOf(seat), seat, view(seat).me().role(), phase, turnVersion,
                time.nowMillis() + 20_000L, 6);
    }

    // ---------------------------------------------------------------- 驱动对局

    /** 把对局驱动到目标阶段（只用应用层提交合法动作，不经过网关）。 */
    void driveTo(GamePhase target) {
        int guard = 0;
        while (state().status() == GameStatus.RUNNING && state().phase() != target) {
            assertTrue(++guard <= MAX_DRIVE_STEPS,
                    "驱动超过 " + MAX_DRIVE_STEPS + " 步仍未到达 " + target + "，当前 " + state().phase());
            step();
        }
        assertEquals(target, state().phase(),
                "驱动未到达目标阶段（当前 " + state().phase() + "，status=" + state().status() + "）");
    }

    /** 按当前阶段提交一个合法动作。规则判定全部在领域，这里只负责"选一个合法输入"。 */
    private void step() {
        GameSnapshot snapshot = state();
        assertTrue(snapshot.status() == GameStatus.RUNNING && snapshot.phase() != GamePhase.GAME_OVER,
                "对局已结束，无法继续驱动");
        Game game = game();
        switch (snapshot.phase()) {
            case NIGHT_WOLF -> {
                List<SeatNo> pending = game.wolvesAwaitingNomination();
                assertFalse(pending.isEmpty(), "NIGHT_WOLF 必须还有待提名的狼人");
                SeatNo wolf = pending.get(0);
                act(wolf.value(), "WOLF_KILL", java.util.Map.of("targetSeatNo", victimSeat(snapshot)));
            }
            case NIGHT_SEER -> {
                SeatNo seer = aliveHolderOf(snapshot, Role.SEER);
                SeatNo target = snapshot.players().stream()
                        .filter(PlayerSnapshot::alive)
                        .map(PlayerSnapshot::seatNo)
                        .filter(seat -> !seat.equals(seer))
                        .min(SeatNo::compareTo)
                        .orElseThrow();
                act(seer.value(), "SEER_INSPECT", java.util.Map.of("targetSeatNo", target.value()));
            }
            case NIGHT_WITCH -> act(aliveHolderOf(snapshot, Role.WITCH).value(), "WITCH_PASS", java.util.Map.of());
            case LAST_WORDS, DAY_DISCUSS -> {
                Turn turn = snapshot.currentTurn();
                assertNotNull(turn, snapshot.phase() + " 必须发放回合窗口");
                act(turn.seatNo().value(), "SPEAK", java.util.Map.of("content", "驱动用发言"));
            }
            case DAY_VOTE -> {
                List<SeatNo> pending = game.seatsAwaitingVote();
                assertFalse(pending.isEmpty(), "DAY_VOTE 必须还有待投票的玩家");
                act(pending.get(0).value(), "VOTE", java.util.Map.of("abstain", true));
            }
            case HUNTER_SHOOT -> act(
                    SeatNo.of(snapshot.pendingHunter()).value(), "HUNTER_SHOOT", java.util.Map.of("giveUp", true));
            default -> throw new IllegalStateException(
                    "阶段 " + snapshot.phase() + "（status=" + snapshot.status() + "）不接受驱动");
        }
    }

    /** 狼刀目标：避开猎人与女巫，让首夜不会插入 HUNTER_SHOOT，女巫也能行动。 */
    private int victimSeat(GameSnapshot snapshot) {
        return snapshot.players().stream()
                .filter(PlayerSnapshot::alive)
                .filter(player -> !player.isWolf())
                .filter(player -> player.role() != Role.HUNTER && player.role() != Role.WITCH)
                .map(player -> player.seatNo().value())
                .min(Integer::compareTo)
                .orElseThrow();
    }

    /** 驱动用的提交：直接用应用服务，幂等键与网关的键不同，互不干扰。 */
    private void act(int seat, String actionType, java.util.Map<String, Object> params) {
        ActionResult result = submitService.submit(SubmitActionCommand.of(
                GAME_ID, playerIdOf(seat), actionType, params, version(), "drive_" + (++driveSequence)));
        assertTrue(result.accepted(), "驱动动作必须被接受（" + actionType + " by seat " + seat + "）");
    }

    private static SeatNo aliveHolderOf(GameSnapshot snapshot, Role role) {
        return snapshot.players().stream()
                .filter(PlayerSnapshot::alive)
                .filter(player -> player.role() == role)
                .map(PlayerSnapshot::seatNo)
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("没有存活的 " + role));
    }

    /** 固定时钟：审计的 latencyMs 与回合 deadline 可预测。 */
    static final class FakeGatewayTime implements com.wolfmind.application.port.TimePort {

        private long now;

        FakeGatewayTime(long now) {
            this.now = now;
        }

        @Override
        public long nowMillis() {
            return now;
        }
    }
}
