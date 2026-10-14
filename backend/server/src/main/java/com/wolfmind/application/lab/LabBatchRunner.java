package com.wolfmind.application.lab;

import com.wolfmind.application.agent.AgentRuntime;
import com.wolfmind.application.agent.llm.LlmSettings;
import com.wolfmind.application.agent.model.AgentConfig;
import com.wolfmind.application.agent.model.TurnBudget;
import com.wolfmind.application.game.StartGameApplicationService;
import com.wolfmind.application.port.GameRepositoryPort;
import com.wolfmind.application.port.TimePort;
import com.wolfmind.domain.game.model.ControllerType;
import com.wolfmind.domain.game.model.GameId;
import com.wolfmind.domain.game.model.GameRules;
import com.wolfmind.domain.game.model.GameSnapshot;
import com.wolfmind.domain.game.model.GameStatus;
import com.wolfmind.domain.game.model.PlayerController;
import com.wolfmind.domain.game.model.PlayerId;
import com.wolfmind.domain.game.model.PlayerSnapshot;
import com.wolfmind.domain.game.model.RoleComposition;
import com.wolfmind.domain.game.model.SeatAssignment;
import com.wolfmind.domain.game.model.SeatNo;
import com.wolfmind.domain.game.model.StartGameCommand;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 批量实验运行器（api-spec.md §8）：把一批对局按并发上限逐步开出来，并同步它们的进度。
 *
 * <p><b>它不推进对局。</b>对局由应用层回合调度推进，与真人对局共用同一条路径。
 * 运行器只做两件生产代码里没有的事：批量的创建与批量的进度汇总。
 *
 * <p><b>为什么"先注册后发牌、再换配置"。</b>角色由 {@code Game.start} 在开局时随机分配，
 * 因此"哪份配置该坐哪个座位"只能在开局之后才知道。而如果等到那时才注册 Agent，
 * 驱动会在窗口内派发到一个未注册的座位（{@code AGENT_NOT_REGISTERED}），该回合只能等超时——
 * 一局白等 30 秒，50 局的批次就是几十分钟。
 * 因此顺序是：①为 9 个座位注册规则基线 Agent（不调模型、毫秒级）②开局 ③读出座位角色
 * ④把命中实验室配置的座位换成目标配置。窗口内最多影响"某一局某一个座位的第一个回合"，
 * 而那个回合仍是合法的规则基线动作，不会卡住对局。
 *
 * <p><b>一份配置可以坐多个座位。</b>同一角色有多份配置时按开局序号轮转，于是"同一角色放三份配置"
 * 就是一场 A/B/C 对比；未命中任何配置的角色由规则基线补齐，保证整局仍能跑完。
 */
public final class LabBatchRunner {

    private static final Logger LOG = LoggerFactory.getLogger(LabBatchRunner.class);

    /** 实验室对局的 gameId 前缀；驱动据此跳过发言阅读停顿。 */
    public static final String GAME_ID_PREFIX = "g_lab_";

    /** 每轮每个批次最多新建几局：一次 tick 里创建太多会长时间占用调度线程。 */
    private static final int MAX_LAUNCH_PER_TICK = 2;

    private final LabStorePort store;
    private final StartGameApplicationService startGame;
    private final GameRepositoryPort repository;
    private final AgentRuntime agentRuntime;
    private final LlmSettings llmSettings;
    private final TimePort time;
    private final int serverMaxConcurrentGames;

    public LabBatchRunner(
            LabStorePort store,
            StartGameApplicationService startGame,
            GameRepositoryPort repository,
            AgentRuntime agentRuntime,
            LlmSettings llmSettings,
            TimePort time) {
        this(store, startGame, repository, agentRuntime, llmSettings, time, Integer.MAX_VALUE);
    }

    /**
     * @param serverMaxConcurrentGames 全局并发上限（{@code lab.maxConcurrentGames}）。
     *        它是**全局**的：两个批次各自 7 局并发加起来就是 14 局，
     *        而"服务端上限"说的显然不是每一批的上限
     */
    public LabBatchRunner(
            LabStorePort store,
            StartGameApplicationService startGame,
            GameRepositoryPort repository,
            AgentRuntime agentRuntime,
            LlmSettings llmSettings,
            TimePort time,
            int serverMaxConcurrentGames) {
        this.store = Objects.requireNonNull(store, "store");
        this.startGame = Objects.requireNonNull(startGame, "startGame");
        this.repository = Objects.requireNonNull(repository, "repository");
        this.agentRuntime = Objects.requireNonNull(agentRuntime, "agentRuntime");
        this.llmSettings = Objects.requireNonNull(llmSettings, "llmSettings");
        this.time = Objects.requireNonNull(time, "time");
        this.serverMaxConcurrentGames = Math.max(1, serverMaxConcurrentGames);
    }

    /** 一轮扫描：刷新进度、启动新对局、标记批次结束。由调度器周期调用。 */
    public void tick() {
        List<LabBatch> running;
        try {
            running = store.runningBatches();
        } catch (RuntimeException error) {
            LOG.warn("读取运行中的实验室批次失败，下一轮重试", error);
            return;
        }
        // 全局在跑局数：跨批次共享同一个上限，否则"服务端上限 8"会被批次数量放大。
        int globalInFlight = running.stream().mapToInt(LabBatch::inFlight).sum();
        for (LabBatch batch : running) {
            try {
                globalInFlight = tickBatch(batch, globalInFlight);
            } catch (RuntimeException error) {
                // 单个批次失败不能杀掉调度：异常之后定时任务会被静默取消，所有批次都会停住。
                LOG.warn("实验室批次一轮失败 batchId={}，下一轮重试", batch.batchId(), error);
            }
        }
    }

    private int tickBatch(LabBatch batch, int globalInFlight) {
        LabBatch current = refresh(batch);
        int launched = 0;
        while (!current.allLaunched()
                && current.inFlight() < current.maxConcurrentGames()
                && globalInFlight < serverMaxConcurrentGames
                && launched < MAX_LAUNCH_PER_TICK) {
            current = launchNext(current);
            globalInFlight++;
            launched++;
        }
        if (current.settled()) {
            current = current.withStatus(LabBatch.COMPLETED, null, time.nowMillis());
            LOG.info("实验室批次完成 batchId={} total={} statistics={}",
                    current.batchId(), current.total(), current.statistics());
        }
        store.saveBatch(current);
        return globalInFlight;
    }

    /**
     * 用对局仓储的权威状态刷新进度。
     *
     * <p>不累加计数：批次记录可能在重启后重放、也可能被两个 tick 同时读到，
     * 而"重读一遍"永远是幂等的。
     */
    private LabBatch refresh(LabBatch batch) {
        List<LabBatch.GameRef> updated = new ArrayList<>(batch.games().size());
        boolean changed = false;
        for (LabBatch.GameRef game : batch.games()) {
            if (game.gameId() == null || game.terminal()) {
                updated.add(game);
                continue;
            }
            LabBatch.GameRef refreshed = readOutcome(game);
            changed |= refreshed.status() != null && !refreshed.status().equals(game.status());
            updated.add(refreshed);
        }
        return changed ? batch.withGames(updated, time.nowMillis()) : batch;
    }

    private LabBatch.GameRef readOutcome(LabBatch.GameRef game) {
        try {
            var loaded = repository.load(game.gameId());
            GameSnapshot snapshot = loaded.game().snapshot();
            GameStatus status = loaded.game().status();
            if (status == GameStatus.FINISHED || status == GameStatus.ABORTED) {
                String winner = snapshot.winner() == null ? null : snapshot.winner().name();
                return game.withOutcome(
                        status == GameStatus.FINISHED ? LabBatch.GAME_FINISHED : LabBatch.GAME_ABORTED,
                        winner,
                        snapshot.round());
            }
            return game.withOutcome(LabBatch.GAME_RUNNING, null, snapshot.round());
        } catch (RuntimeException error) {
            // 加载不了（存储故障或对局丢失）就标为失败：留着 PENDING 会让批次永远等一个不存在的东西。
            LOG.warn("实验室对局状态不可读，标记为失败 gameId={}", game.gameId(), error);
            return new LabBatch.GameRef(game.index(), game.gameId(), LabBatch.GAME_FAILED,
                    null, null, game.roleAssignments());
        }
    }

    /** 创建下一局实验对局，并记录角色 → 配置的绑定。 */
    private LabBatch launchNext(LabBatch batch) {
        int index = batch.nextIndex();
        String gameId = GAME_ID_PREFIX + batch.batchId() + "_" + index;
        String roomId = "lab_" + batch.batchId() + "_" + index;
        List<SeatAssignment> seats = nineAiSeats();

        // ① 先占位注册：驱动在任何时刻派发都不会遇到未注册的座位。
        for (SeatAssignment seat : seats) {
            registerAgent(gameId, seat.seatNo().value(), AgentConfig.ruleBaseline("lab-placeholder"));
        }

        try {
            startGame.start(
                    new StartGameCommand(GameId.of(gameId), roomId, seats,
                            RoleComposition.STANDARD_NINE, GameRules.DEFAULT),
                    "SYSTEM",
                    "lab:" + batch.batchId() + ":" + index);
        } catch (RuntimeException error) {
            LOG.warn("实验室对局创建失败 batchId={} index={}", batch.batchId(), index, error);
            List<LabBatch.GameRef> games = new ArrayList<>(batch.games());
            games.add(new LabBatch.GameRef(index, gameId, LabBatch.GAME_FAILED, null, null, Map.of()));
            return batch.withGames(games, time.nowMillis());
        }

        GameSnapshot snapshot = repository.load(gameId).game().snapshot();
        List<LabAgentConfig> configs = store.configsByIds(batch.agentConfigIds());
        Map<String, LabBatch.AssignedConfig> assignments = new LinkedHashMap<>();
        Map<String, Integer> roleCursor = new LinkedHashMap<>();
        for (LabBatch.GameRef existing : batch.games()) {
            existing.roleAssignments().keySet()
                    .forEach(role -> roleCursor.merge(role, 1, Integer::sum));
        }

        for (PlayerSnapshot player : snapshot.players()) {
            String role = player.role() == null ? null : player.role().name();
            if (role == null) {
                continue;
            }
            LabAgentConfig chosen = pickConfig(configs, role, roleCursor.getOrDefault(role, 0));
            if (chosen == null) {
                continue;
            }
            // ② 换成目标配置：注册表按 (gameId, playerId) 幂等，必须先释放占位实例。
            // 句柄只需要 (gameId, playerId)——destroyAgent 按这两个字段定位实例，
            // 因此不必先取回占位句柄，也就不需要给 AgentRuntime 接口加一个"查句柄"的方法。
            agentRuntime.destroyAgent(new AgentRuntime.AgentHandle(
                    gameId, player.playerId().value(), player.seatNo().value(), "lab-placeholder"));
            registerAgent(gameId, player.seatNo().value(), toAgentConfig(chosen));
            assignments.put(role, new LabBatch.AssignedConfig(
                    chosen.agentConfigId(), player.seatNo().value(), player.camp().name()));
        }

        List<LabBatch.GameRef> games = new ArrayList<>(batch.games());
        games.add(new LabBatch.GameRef(
                index, gameId, LabBatch.GAME_RUNNING, null, snapshot.round(), assignments));
        LOG.info("实验室对局已创建 batchId={} index={} gameId={} 配置座位={}",
                batch.batchId(), index, gameId, assignments);
        return batch.withGames(games, time.nowMillis());
    }

    private static List<SeatAssignment> nineAiSeats() {
        List<SeatAssignment> seats = new ArrayList<>(RoleComposition.STANDARD_NINE.playerCount());
        for (int seatNo = 1; seatNo <= RoleComposition.STANDARD_NINE.playerCount(); seatNo++) {
            SeatNo seat = SeatNo.of(seatNo);
            seats.add(new SeatAssignment(
                    seat,
                    // AI 座位的 refId 必须是规范 playerId `p_{seat}`：
                    // SubmitActionApplicationService 用 actorId == controller.refId() 定位座位，
                    // 不一致时每个回合都会被拒为 SEAT_NOT_YOURS（Phase 4 第 32/34 轮的两个缺陷）。
                    new PlayerController(ControllerType.AI, PlayerId.forSeat(seat).value()),
                    true));
        }
        return seats;
    }

    /**
     * 该角色本轮该用哪份配置。
     *
     * <p>按"该角色已经开过几局"取模轮转，因此三份同角色配置会均匀分布而不是第一份吃掉全部对局。
     * 该角色没有任何配置时返回 {@code null}（走规则基线）。
     */
    private static LabAgentConfig pickConfig(List<LabAgentConfig> configs, String role, int cursor) {
        List<LabAgentConfig> candidates = new ArrayList<>();
        for (LabAgentConfig config : configs) {
            if (config.playsRole(role)) {
                candidates.add(config);
            }
        }
        return candidates.isEmpty() ? null : candidates.get(Math.floorMod(cursor, candidates.size()));
    }

    private void registerAgent(String gameId, int seatNo, AgentConfig config) {
        agentRuntime.createAgent(new AgentRuntime.CreateAgentCommand(
                gameId, PlayerId.forSeat(SeatNo.of(seatNo)).value(), seatNo, config));
    }

    /**
     * 实验室配置 → 运行时配置。
     *
     * <p>模型名取配置里的值；未配置供应商时（{@code LlmSettings.configured() == false}）
     * 仍然带上模型名——{@code LlmClient} 的实现会以"模型不可用"失败并走规则兜底，
     * 这条路径本身就是要被实验覆盖的场景（"模型挂了这局会怎样"）。
     */
    private AgentConfig toAgentConfig(LabAgentConfig config) {
        AgentConfig base = AgentConfig.ruleBaseline(config.agentConfigId());
        return new AgentConfig(
                config.agentConfigId(),
                config.name(),
                config.model(),
                config.temperature(),
                0.2,
                AgentConfig.StrategyProfile.valueOf(config.strategyProfile()),
                config.persona() == null || config.persona().isBlank() ? base.persona() : config.persona(),
                AgentConfig.WorkflowMode.A,
                base.summarizeThreshold(),
                base.exposureAversion(),
                config.memoryRecallLimit(),
                base.candidateLimit(),
                config.promptTemplateId(),
                TurnBudget.defaultFor(llmSettings.interactionMode()));
    }

    /** 供应商默认模型；未配置时为 {@code null}。用于把"没填模型"的配置补齐成可用模型。 */
    public String defaultModel() {
        return llmSettings.configured() ? llmSettings.defaultModel() : null;
    }
}
