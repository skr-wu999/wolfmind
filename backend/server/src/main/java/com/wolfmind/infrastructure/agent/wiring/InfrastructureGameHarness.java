package com.wolfmind.infrastructure.agent.wiring;

import com.wolfmind.infrastructure.agent.AgentViewAdapter;
import com.wolfmind.infrastructure.agent.ToolGatewayAdapter;
import com.wolfmind.application.game.ActionCodec;
import com.wolfmind.application.game.SubmitActionApplicationService;
import com.wolfmind.application.port.EventPublisherPort;
import com.wolfmind.application.port.GameLockPort;
import com.wolfmind.application.port.GameRepositoryPort;
import com.wolfmind.application.port.GameViewQueryPort;
import com.wolfmind.application.port.IdempotencyPort;
import com.wolfmind.application.port.TimePort;
import com.wolfmind.domain.game.model.ActionContext;
import com.wolfmind.domain.game.model.Game;
import com.wolfmind.domain.game.model.GameId;
import com.wolfmind.domain.game.model.GameRules;
import com.wolfmind.domain.game.model.PlayerId;
import com.wolfmind.domain.game.model.RoleComposition;
import com.wolfmind.domain.game.model.SeatAssignment;
import com.wolfmind.domain.game.model.StartGameCommand;
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

/**
 * AI 对局基础设施装配：把**真实应用层与真实适配器**接成一条完整的写路径。
 *
 * <p>它的存在是为了让"9 个 AI 完成一局"的验收测试跑在**生产同构**的链路上，而不是测试替身上：
 * <pre>
 *   Agent → ToolGatewayAdapter → SubmitActionApplicationService → Game.handle → 事件流
 * </pre>
 * 因此这条测试同时验证了网关（G1..G13）、应用层（幂等/锁/版本）、领域（规则/事件）三层。
 *
 * <p>唯一的内存化部分是存储实现（Redis/SQLite 的同形替换点），这正是 Phase 3 的边界：
 * Agent 子系统与存储无关（agent-runtime.md §15 禁止 Runtime 直接读写 Redis/SQLite）。
 *
 * <p><b>身份约定</b>（gateway-dev 的裁决，已固化在这里）：`SubmitActionApplicationService`
 * 按 `actorId == controller.refId` 反查座位，而工具会话只带 `playerId`，因此 AI 座位的
 * `agentId` 必须等于 `playerId`（即 `p_{seat}`）。本类用 {@link #playerIdForSeat} 统一生成，
 * 避免调用方各自拼字符串拼错。
 */
public final class InfrastructureGameHarness {

    private final InMemoryEventStore eventStore = new InMemoryEventStore();
    private final InMemorySnapshotStore snapshotStore = new InMemorySnapshotStore();
    private final InMemoryGameStateCache stateCache = new InMemoryGameStateCache();
    private final InMemoryLock lock = new InMemoryLock();
    private final InMemoryIdempotencyPort idempotency = new InMemoryIdempotencyPort();
    private final InMemoryEventPublisher publisher = new InMemoryEventPublisher();
    private final TimePort time;

    private final InMemoryGameRepository repository;
    private final InMemoryGameViewQuery viewQuery;
    private final SubmitActionApplicationService submitService;

    private long nowMillis;

    public InfrastructureGameHarness(long startMillis) {
        this.nowMillis = startMillis;
        // 可控时钟：AI 回合的 deadline 与轨迹时间戳都基于它，因此测试可以精确推演超时路径。
        this.time = () -> InfrastructureGameHarness.this.nowMillis;
        this.repository = new InMemoryGameRepository(eventStore, snapshotStore, stateCache);
        this.viewQuery = new InMemoryGameViewQuery(eventStore, repository);
        this.submitService = new SubmitActionApplicationService(
                new ActionCodec(), idempotency, lock, repository, publisher, time);
    }

    /** 座位对应的 playerId，也是 AI 座位的 agentId —— 两者必须一致（见类注释）。 */
    public static String playerIdForSeat(int seatNo) {
        return PlayerId.forSeat(com.wolfmind.domain.game.model.SeatNo.of(seatNo)).value();
    }

    /** 座位对应的控制者引用标识：AI 座位用 playerId。 */
    public static String controllerRefForSeat(int seatNo) {
        return playerIdForSeat(seatNo);
    }

    /**
     * 用 9 个 AI 座位开局，并把开局事件写入事件存储（使仓储能加载到对局）。
     *
     * @param roleComposition 角色配置
     * @param rules           规则参数
     * @param randomSeed      角色分配种子（固定值 → 可复现）
     */
    public Game startNineAiGame(String gameId, RoleComposition roleComposition, GameRules rules, long randomSeed) {
        List<SeatAssignment> seats = new ArrayList<>();
        for (int seat = 1; seat <= 9; seat++) {
            // controller.refId == playerId，满足应用层"提交者即座位控制者"的校验
            seats.add(SeatAssignment.ai(seat, controllerRefForSeat(seat)));
        }
        Game game = Game.create(GameId.of(gameId));
        ActionContext context = new ActionContext(
                "cmd_start_" + gameId, nowMillis, "SYSTEM", "cmd_start", "cor_start", 0, randomSeed);
        List<com.wolfmind.domain.game.event.DomainEvent> events =
                game.start(new StartGameCommand(GameId.of(gameId), "room_e2e", seats, roleComposition, rules),
                        context);

        // 把开局事件落进事件存储：之后所有写入都经应用层，因此必须已有 version 1..11
        List<com.wolfmind.domain.game.event.EventEnvelope> envelopes = new ArrayList<>();
        int version = 1;
        for (com.wolfmind.domain.game.event.DomainEvent event : events) {
            envelopes.add(com.wolfmind.domain.game.event.EventEnvelope.of(event, gameId, version++));
        }
        eventStore.append(gameId, 0, envelopes);
        stateCache.put(game.snapshot());
        snapshotStore.save(new com.wolfmind.application.port.SnapshotStorePort.Snapshot(
                gameId, game.version(),
                com.wolfmind.infrastructure.memory.GameSnapshotCodec.encode(game.snapshot()),
                com.wolfmind.infrastructure.memory.GameSnapshotCodec.checksum(
                        com.wolfmind.infrastructure.memory.GameSnapshotCodec.encode(game.snapshot()))));
        return game;
    }

    /** 推进时钟；AI 回合的 deadline 与轨迹时间戳都依赖它。 */
    public void advanceClock(long millis) {
        this.nowMillis += millis;
    }

    /**
     * 把时钟直接设到给定时刻（只允许向前）。
     *
     * <p>与 {@link #advanceClock(long)} 的区别：增量推进适合测试里"精确推演超时"，
     * 而真实模型对局需要把时钟对齐到**真实耗时**，否则驱动自己造的时钟会提前越过
     * 下一个座位的回合截止时刻（表现为模型有输出却被判 deadline 过期）。
     */
    public void setClockTo(long millis) {
        if (millis > this.nowMillis) {
            this.nowMillis = millis;
        }
    }

    public long now() {
        return nowMillis;
    }

    public GameViewQueryPort viewQuery() {
        return viewQuery;
    }

    public SubmitActionApplicationService submitService() {
        return submitService;
    }

    public GameRepositoryPort repository() {
        return repository;
    }

    public InMemoryEventStore eventStore() {
        return eventStore;
    }

    public IdempotencyPort idempotency() {
        return idempotency;
    }

    public GameLockPort lock() {
        return lock;
    }

    public EventPublisherPort publisher() {
        return publisher;
    }

    public TimePort time() {
        return time;
    }

    /** 便于断言：从事件存储读回整条事件流（而不是内存里的聚合）。 */
    public List<com.wolfmind.domain.game.event.EventEnvelope> eventStream(String gameId) {
        return eventStore.read(gameId, 1, Integer.MAX_VALUE);
    }

    /** 便于断言：事件流里的领域事件类型序列。 */
    public List<String> eventTypes(String gameId) {
        return eventStream(gameId).stream()
                .map(com.wolfmind.domain.game.event.EventEnvelope::type)
                .toList();
    }
}
