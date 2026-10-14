package com.wolfmind.domain.game.model;

import com.wolfmind.domain.game.event.DeathAnnouncedEvent;
import com.wolfmind.domain.game.event.DomainEvent;
import com.wolfmind.domain.game.event.EventMeta;
import com.wolfmind.domain.game.event.EventType;
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
import com.wolfmind.domain.game.service.ActionValidator;
import com.wolfmind.domain.game.service.DeterministicShuffle;
import com.wolfmind.domain.game.service.NightResolutionPolicy;
import com.wolfmind.domain.game.service.RoleLocator;
import com.wolfmind.domain.game.service.TurnOrderPolicy;
import com.wolfmind.domain.game.service.VoteTallyPolicy;
import com.wolfmind.domain.game.service.WinConditionPolicy;
import com.wolfmind.domain.game.service.WolfKillPolicy;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * 狼人杀对局聚合根：规则的权威边界（AGENTS.md §5、DESIGN.md §5）。
 *
 * <p>三条不可退让的实现约束：
 * <ol>
 *   <li><b>状态只能由事件修改</b>（INV-13）：所有可变字段的写入都集中在 {@code apply(...)} 里，
 *       命令处理与 {@link #replay(DomainEvent)} 共用同一批 {@code apply}，因此"命令处理后的内存状态"
 *       与"重放同样事件后的内存状态"必然一致（INV-16）。</li>
 *   <li><b>不使用时钟、随机数、UUID</b>（domain-model.md §10）：时间与事件 id 由 {@link ActionContext}
 *       注入；随机种子只在开局使用且结果写入 {@code ROLE_ASSIGNED}。</li>
 *   <li><b>集合遍历顺序确定</b>：所有涉及多人的事件按座位号升序产生。</li>
 * </ol>
 *
 * <p>被拒绝的命令抛 {@link GameException} 且不产生任何事件；{@link #advance(ActionContext)} 在条件
 * 未达成时返回空列表（"什么都不该发生"不是错误）。
 */
public final class Game {

    /** 单次用例内允许的最大连续阶段推进次数，用于防御转换表配置错误导致的死循环。 */
    private static final int MAX_PHASE_STEPS = 64;

    private final GameId gameId;

    private int version;
    private GameStatus status;
    private GamePhase phase;
    private int round;
    private RoleComposition roleComposition;
    private GameRules rules;
    private String roomId;

    /** 恒为全部座位，键为座位号，保证按座位号升序遍历。 */
    private final Map<SeatNo, Player> players = new LinkedHashMap<>();

    private List<SeatNo> turnOrder = List.of();
    private int turnIndex;
    /** 上一轮白天发言的首位座位，用于下一轮环形顺延（TurnOrderPolicy）。 */
    private SeatNo previousDiscussionFirst;
    private Turn currentTurn;

    private Integer wolfKillTarget;
    private final List<WolfKillSelectedEvent.Nomination> wolfNominations = new ArrayList<>();
    private Integer witchSaved;
    private Integer witchPoisoned;
    private boolean witchPassed;
    /** 已查验目标；{@code -1} 表示"本夜已放弃查验"，与"尚未查验（null）"区分。 */
    private Integer inspectedTarget;
    private Camp inspectResult;

    private final Map<Integer, Vote> votes = new LinkedHashMap<>();

    /** 全部死亡记录，含是否已公布（公布由 {@code DEATH_ANNOUNCED} 驱动）。 */
    private final List<DeathRecord> deaths = new ArrayList<>();

    private GamePhase resumePhase;
    private GamePhase pendingAfterLastWords;
    /** 当前这轮死亡的来源；跨插入阶段（猎人开枪）保存，用于决定遗言之后回到哪里。 */
    private DeathOrigin pendingDeathOrigin;
    /** 本轮遗言发言者，按座位号升序；进入 LAST_WORDS 时计算。 */
    private final List<SeatNo> pendingLastWords = new ArrayList<>();
    private int lastWordsIndex;
    private Integer pendingHunter;
    /** 被猎人枪击的座位：需要在下一次阶段跳转前补一条 DEATH_ANNOUNCED。 */
    private Integer pendingShotVictim;

    private Camp winner;
    private String finishReason;

    /** 本夜每名狼人已发送的夜聊条数，键为座位号。 */
    private final Map<Integer, Integer> wolfChatCounts = new LinkedHashMap<>();
    /** 最后一条非夜聊事件的版本。 */
    private int stateVersion;

    /** 最近一次用例调用产生的事件，等待应用层持久化。 */
    private final List<DomainEvent> uncommittedEvents = new ArrayList<>();

    private Game(GameId gameId) {
        this.gameId = Objects.requireNonNull(gameId, "gameId");
        this.status = GameStatus.INIT;
        this.phase = GamePhase.GAME_INIT;
        this.round = 1;
        this.version = 0;
    }

    /** 新建对局（尚未开局）：状态 {@code INIT}，版本 0。 */
    public static Game create(GameId gameId) {
        return new Game(gameId);
    }

    /**
     * 从快照恢复（event-design.md §11 恢复流程的第 3 步入参）。
     *
     * <p>恢复路径与重放路径共享同一套字段，所以"快照 + 增量事件"与"从头全量重放"必然一致
     * （INV-15、INV-16）。
     */
    public static Game restore(GameSnapshot snapshot) {
        Objects.requireNonNull(snapshot, "snapshot");
        Game game = new Game(snapshot.gameId());
        game.version = snapshot.version();
        game.status = snapshot.status();
        game.phase = snapshot.phase();
        game.round = snapshot.round();
        game.roleComposition = snapshot.roleComposition();
        game.rules = snapshot.rules();
        game.roomId = snapshot.roomId();
        game.winner = snapshot.winner();
        game.finishReason = snapshot.finishReason();

        for (PlayerSnapshot player : snapshot.players()) {
            Player restored = new Player(
                    player.playerId(), player.seatNo(), player.controller(), player.ready());
            if (player.role() != null) {
                restored.applyRole(player.role());
            }
            restored.applySkillState(player.skillState());
            if (!player.alive()) {
                restored.kill();
            }
            game.players.put(player.seatNo(), restored);
        }

        game.turnOrder = snapshot.turnOrder().stream().map(SeatNo::of).toList();
        game.turnIndex = snapshot.turnIndex();
        game.previousDiscussionFirst = snapshot.previousDiscussionFirst() == null
                ? null
                : SeatNo.of(snapshot.previousDiscussionFirst());
        game.currentTurn = snapshot.currentTurn();
        game.wolfKillTarget = snapshot.wolfKillTarget();
        game.wolfNominations.addAll(snapshot.wolfNominations());
        game.witchSaved = snapshot.witchSaved();
        game.witchPoisoned = snapshot.witchPoisoned();
        game.witchPassed = snapshot.witchPassed();
        game.inspectedTarget = snapshot.inspectedTarget();
        game.inspectResult = snapshot.inspectResult();
        game.votes.putAll(snapshot.votes());
        game.deaths.addAll(snapshot.deaths());
        game.resumePhase = snapshot.resumePhase();
        game.pendingAfterLastWords = snapshot.pendingAfterLastWords();
        game.pendingLastWords.addAll(snapshot.pendingLastWords().stream().map(SeatNo::of).toList());
        game.lastWordsIndex = snapshot.lastWordsIndex();
        game.pendingHunter = snapshot.pendingHunter();
        game.pendingShotVictim = snapshot.pendingShotVictim();
        game.wolfChatCounts.putAll(snapshot.wolfChatCounts());
        game.stateVersion = snapshot.stateVersion();
        return game;
    }

    // ------------------------------------------------------------------
    // 只读查询
    // ------------------------------------------------------------------

    public GameId gameId() {
        return gameId;
    }

    /** 已产生事件数；下一条事件的 {@code version} 为 {@code version() + 1}（INV-11）。 */
    public int version() {
        return version;
    }

    public GameStatus status() {
        return status;
    }

    public GamePhase phase() {
        return phase;
    }

    public int round() {
        return round;
    }

    public GameRules rules() {
        return rules;
    }

    public RoleComposition roleComposition() {
        return roleComposition;
    }

    /** 最近一次 {@link #start}/{@link #handle}/{@link #advance} 产生的事件。 */
    public List<DomainEvent> pendingEvents() {
        return List.copyOf(uncommittedEvents);
    }

    /** 只读状态投影；不含任何规则判断（domain-model.md §2.2）。 */
    public GameSnapshot snapshot() {
        List<PlayerSnapshot> playerSnapshots = players.values().stream()
                .map(Player::snapshot)
                .sorted(Comparator.comparing(PlayerSnapshot::seatNo))
                .toList();
        return new GameSnapshot(
                gameId,
                version,
                status,
                phase,
                round,
                roleComposition,
                rules,
                roomId,
                playerSnapshots,
                turnOrder.stream().map(SeatNo::value).toList(),
                turnIndex,
                previousDiscussionFirst == null ? null : previousDiscussionFirst.value(),
                currentTurn,
                wolfKillTarget,
                List.copyOf(wolfNominations),
                witchSaved,
                witchPoisoned,
                witchPassed,
                inspectedTarget,
                inspectResult,
                new LinkedHashMap<>(votes),
                List.copyOf(deaths),
                resumePhase,
                pendingAfterLastWords,
                pendingLastWords.stream().map(SeatNo::value).toList(),
                lastWordsIndex,
                pendingHunter,
                pendingShotVictim,
                winner,
                finishReason,
                new LinkedHashMap<>(wolfChatCounts),
                stateVersion);
    }

    // ------------------------------------------------------------------
    // 用例入口
    // ------------------------------------------------------------------

    /**
     * 开局：校验 INV-1..INV-3，洗牌分配角色，产生 11 条事件
     * （{@code GAME_STARTED} + 9×{@code ROLE_ASSIGNED} + {@code PHASE_CHANGED}）。
     *
     * <p>版本 1..11 连续，任一环节失败都不允许留下版本空洞（system-design.md §3.1）。
     */
    public List<DomainEvent> start(StartGameCommand cmd, ActionContext ctx) {
        begin(ctx);
        if (status != GameStatus.INIT || phase != GamePhase.GAME_INIT) {
            throw GameException.Factory.alreadyStarted();
        }
        if (!cmd.gameId().equals(gameId)) {
            throw GameException.Factory.validation("命令的 gameId 与聚合不一致");
        }
        if (!cmd.allReady()) {
            // 用 NOT_READY（409）而不是 VALIDATION_ERROR（400）：请求格式没有问题，
            // 是**房间/对局的当前状态**还不允许开局。客户端按 400 处理会以为要改请求体，
            // 而它该做的是等其他人准备。
            throw GameException.Factory.notReady("存在未就绪座位，不能开局（INV-R2）");
        }

        roleComposition = cmd.roleComposition();
        rules = cmd.rules();
        roomId = cmd.roomId();

        List<SeatAssignment> seats = cmd.seats().stream()
                .sorted(Comparator.comparing(SeatAssignment::seatNo))
                .toList();
        for (SeatAssignment seat : seats) {
            players.put(
                    seat.seatNo(),
                    new Player(PlayerId.forSeat(seat.seatNo()), seat.seatNo(), seat.controller(), seat.ready()));
        }

        List<SeatNo> initialTurnOrder = TurnOrderPolicy.orderFor(
                players.values().stream().map(Player::snapshot).toList(), null);
        turnOrder = initialTurnOrder;

        emit(new GameStartedEvent(
                meta(ctx, EventType.GAME_STARTED),
                gameId,
                roomId,
                seats.stream().map(GameStartedEvent.SeatSpec::of).toList(),
                roleComposition,
                rules,
                initialTurnOrder.stream().map(SeatNo::value).toList(),
                1));

        // 角色分配结果写入 ROLE_ASSIGNED，重放不再重新随机（domain-model.md §2.2 决定性规则）
        List<Role> assigned = DeterministicShuffle.assignRoles(roleComposition, ctx.randomSeed());
        for (int index = 0; index < seats.size(); index++) {
            SeatNo seatNo = seats.get(index).seatNo();
            Player player = players.get(seatNo);
            Role role = assigned.get(index);
            emit(new RoleAssignedEvent(
                    meta(ctx, EventType.ROLE_ASSIGNED), player.playerId(), seatNo, role, role.camp()));
        }

        transition(ctx, GamePhase.NIGHT_WOLF, null);
        runUntilStable(ctx);
        return List.copyOf(uncommittedEvents);
    }

    /**
     * 唯一行动入口（DESIGN.md §6 的 {@code Game.handle()}）。
     *
     * <p>固定流程：校验（V1..V8）→ 阶段处理器"改状态并记录事件" → 检查阶段退出条件 →
     * 检查胜负。任一步失败抛 {@link GameException} 且不产生事件（domain-model.md §2.2）。
     */
    public List<DomainEvent> handle(Action action, ActionContext ctx) {
        begin(ctx);
        PlayerSnapshot actor = ActionValidator.validate(snapshot(), action);
        if (action instanceof SkipAction skip && skip.reason() == SkipReason.TIMEOUT && !ctx.isSystem()) {
            throw GameException.Factory.notAllowed("TIMEOUT 只能由服务端超时看护提交");
        }

        switch (action) {
            case SpeechAction speech -> handleSpeech(ctx, actor, speech);
            case SkipAction skip -> handleSkip(ctx, actor, skip);
            case VoteAction vote -> handleVote(ctx, actor, vote);
            case WolfKillAction wolfKill -> handleWolfKill(ctx, actor, wolfKill);
            case SeerInspectAction inspect -> handleSeerInspect(ctx, actor, inspect);
            case WitchAction witch -> handleWitch(ctx, actor, witch);
            case HunterShotAction shot -> handleHunterShot(ctx, actor, shot);
            case WolfChatAction chat -> handleWolfChat(ctx, actor, chat);
        }

        runUntilStable(ctx);
        return List.copyOf(uncommittedEvents);
    }

    /**
     * 阶段推进：仅由应用层在"条件达成"或"超时"后调用，不接收玩家输入（ddd-design.md §2.2）。
     *
     * <p>它负责的是**阶段级**收尾，且对同一状态幂等：
     * <ul>
     *   <li>自动结算阶段（{@code NIGHT_SETTLE} / {@code DAY_ANNOUNCE} / {@code VOTE_SETTLE}）：
     *       完成结算并推进到下一个需要输入的阶段；</li>
     *   <li>{@code DAY_VOTE} / {@code NIGHT_WOLF}：若全部存活玩家已投票（或全部存活狼人已提名），
     *       继续收尾；若有缺席者则**返回空列表**。</li>
     * </ul>
     *
     * <p><b>它不做超时兜底</b>：领域不读时钟（INV-16），所以"谁超时了、该补什么行动"必须由
     * 应用层用 {@link #timeoutSkips(long, boolean)} 追问领域，然后把返回的 {@link SkipAction}
     * 逐条交给 {@link #handle(Action, ActionContext)}。这样超时仍然表现为合法领域输入，
     * 而不是服务端的隐式修补（C-4）。
     *
     * <p>返回空列表表示"当前状态不需要推进"——调用方不得把它当成错误。
     */
    public List<DomainEvent> advance(ActionContext ctx) {
        begin(ctx);
        if (status != GameStatus.RUNNING) {
            return List.of();
        }
        runUntilStable(ctx);
        return List.copyOf(uncommittedEvents);
    }

    /**
     * 中止对局（人数不足、不可恢复）：终态，之后不接受任何行动（INV-12）。
     */
    public List<DomainEvent> abort(String reason, ActionContext ctx) {
        begin(ctx);
        if (status != GameStatus.RUNNING) {
            throw GameException.Factory.notRunning(status.name());
        }
        emit(new GameAbortedEvent(meta(ctx, EventType.GAME_ABORTED), reason, version + 1));
        return List.copyOf(uncommittedEvents);
    }

    /**
     * 替换座位控制者（真人掉线由 AI/Bot 接管，ddd-design.md §3.3）。
     *
     * <p>规则校验不需要分支：{@code Player} 只保存 {@code {type, refId}}。
     */
    public List<DomainEvent> changeController(
            SeatNo seatNo, PlayerController newController, ActionContext ctx) {
        begin(ctx);
        if (status != GameStatus.RUNNING && status != GameStatus.INIT) {
            throw GameException.Factory.notRunning(status.name());
        }
        Player player = players.get(seatNo);
        if (player == null) {
            throw GameException.Factory.invalidTarget("座位 " + seatNo + " 不存在");
        }
        PlayerController from = player.controller();
        if (from.equals(newController)) {
            return List.of();
        }
        emit(new PlayerControllerChangedEvent(
                meta(ctx, EventType.PLAYER_CONTROLLER_CHANGED),
                roomId,
                player.playerId(),
                seatNo,
                from.type(),
                from.refId(),
                newController.type(),
                newController.refId()));
        return List.copyOf(uncommittedEvents);
    }

    /**
     * 应用一条历史事件：仅由仓储在恢复与投影重建时调用。
     *
     * <p>版本必须严格连续（INV-11），否则抛 {@link DomainErrorCode#REPLAY_OUT_OF_ORDER}。
     * 重放不触碰 {@code uncommittedEvents}：重放产生的事件都是历史事实，不是待提交事件。
     */
    public void replay(DomainEvent event) {
        if (event.version() != version + 1) {
            throw GameException.Factory.replayOutOfOrder(version + 1, event.version());
        }
        apply(event);
    }

    // ------------------------------------------------------------------
    // 行动处理
    // ------------------------------------------------------------------

    private void handleSpeech(ActionContext ctx, PlayerSnapshot actor, SpeechAction action) {
        action.speech().validateLength(rules.speechMaxLength());
        // speechId 由 (gameId, version) 派生，保证确定性且唯一（不使用 UUID）
        String speechId = PlayerSpokeEvent.speechIdFor(gameId.value(), version + 1);
        // 游标推进同样由 applyPlayerSpoke 完成，命令路径不直接改状态（INV-13）
        emit(new PlayerSpokeEvent(
                meta(ctx, EventType.PLAYER_SPOKE),
                actor.playerId(),
                actor.seatNo(),
                speechId,
                action.speech().content(),
                phase == GamePhase.LAST_WORDS ? lastWordsIndex : turnIndex,
                phase));
    }

    private void handleSkip(ActionContext ctx, PlayerSnapshot actor, SkipAction action) {
        switch (phase) {
            case DAY_DISCUSS, LAST_WORDS -> {
                int index = phase == GamePhase.LAST_WORDS ? lastWordsIndex : turnIndex;
                emit(new SpeechSkippedEvent(
                        meta(ctx, EventType.SPEECH_SKIPPED),
                        actor.playerId(),
                        actor.seatNo(),
                        action.reason(),
                        index));
                // 游标推进由 applySpeechSkipped 完成，命令路径不直接改状态（INV-13）
            }
            case DAY_VOTE -> recordVote(ctx, actor, Vote.abstain(actor.seatNo().value()));
            case NIGHT_WOLF -> emit(WolfKillNominatedEvent.empty(
                    meta(ctx, EventType.WOLF_KILL_NOMINATED), actor.playerId(), actor.seatNo()));
            case NIGHT_SEER -> emit(new SeerSkippedEvent(
                    meta(ctx, EventType.SEER_SKIPPED), actor.playerId(), actor.seatNo(), action.reason()));
            case NIGHT_WITCH -> emit(new WitchPassedEvent(
                    meta(ctx, EventType.WITCH_PASSED), actor.playerId(), actor.seatNo(), action.reason()));
            case HUNTER_SHOOT -> emit(new HunterSkippedEvent(
                    meta(ctx, EventType.HUNTER_SKIPPED), actor.playerId(), actor.seatNo(), action.reason()));
            default -> throw GameException.Factory.notAllowed(
                    "阶段 " + phase + " 不接受 SKIP（domain-model.md §4）");
        }
    }

    private void handleVote(ActionContext ctx, PlayerSnapshot actor, VoteAction action) {
        recordVote(ctx, actor, ActionValidator.normalizeVote(action));
    }

    private void recordVote(ActionContext ctx, PlayerSnapshot actor, Vote vote) {
        boolean abstain = vote.abstain();
        SeatNo targetSeat = abstain ? null : SeatNo.of(vote.targetSeat());
        emit(new PlayerVotedEvent(
                meta(ctx, EventType.PLAYER_VOTED),
                actor.playerId(),
                actor.seatNo(),
                abstain ? null : PlayerId.forSeat(targetSeat),
                targetSeat,
                abstain));
    }

    private void handleWolfKill(ActionContext ctx, PlayerSnapshot actor, WolfKillAction action) {
        emit(action.isEmptyKill()
                ? WolfKillNominatedEvent.empty(
                        meta(ctx, EventType.WOLF_KILL_NOMINATED), actor.playerId(), actor.seatNo())
                : new WolfKillNominatedEvent(
                        meta(ctx, EventType.WOLF_KILL_NOMINATED),
                        actor.playerId(),
                        actor.seatNo(),
                        PlayerId.forSeat(action.target()),
                        action.target(),
                        false));
    }

    private void handleWolfChat(ActionContext ctx, PlayerSnapshot actor, WolfChatAction action) {
        emit(new WolfChatSentEvent(
                meta(ctx, EventType.WOLF_CHAT_SENT),
                actor.playerId(),
                actor.seatNo(),
                WolfChatSentEvent.chatIdFor(gameId.value(), version + 1),
                action.content()));
    }

    private void handleSeerInspect(ActionContext ctx, PlayerSnapshot actor, SeerInspectAction action) {
        PlayerSnapshot target = RoleLocator.at(snapshot().players(), action.target())
                .orElseThrow(() -> GameException.Factory.invalidTarget("座位 " + action.target() + " 不存在"));
        emit(new SeerInspectedEvent(
                meta(ctx, EventType.SEER_INSPECTED),
                actor.playerId(),
                actor.seatNo(),
                target.playerId(),
                target.seatNo(),
                target.camp()));
    }

    private void handleWitch(ActionContext ctx, PlayerSnapshot actor, WitchAction action) {
        switch (action.mode()) {
            case SAVE -> emit(new WitchSavedEvent(
                    meta(ctx, EventType.WITCH_SAVED),
                    actor.playerId(),
                    actor.seatNo(),
                    PlayerId.forSeat(action.target()),
                    action.target()));
            case POISON -> emit(new WitchPoisonedEvent(
                    meta(ctx, EventType.WITCH_POISONED),
                    actor.playerId(),
                    actor.seatNo(),
                    PlayerId.forSeat(action.target()),
                    action.target()));
            case PASS -> emit(new WitchPassedEvent(
                    meta(ctx, EventType.WITCH_PASSED),
                    actor.playerId(),
                    actor.seatNo(),
                    SkipReason.VOLUNTARY));
        }
    }

    private void handleHunterShot(ActionContext ctx, PlayerSnapshot actor, HunterShotAction action) {
        if (action.isGiveUp()) {
            emit(new HunterSkippedEvent(
                    meta(ctx, EventType.HUNTER_SKIPPED),
                    actor.playerId(),
                    actor.seatNo(),
                    SkipReason.VOLUNTARY));
            return;
        }
        PlayerSnapshot target = RoleLocator.at(snapshot().players(), action.target())
                .orElseThrow(() -> GameException.Factory.invalidTarget("座位 " + action.target() + " 不存在"));
        // 目标在开枪前仍然存活 → 这是一次新的死亡事实，必须产生 PLAYER_KILLED（JUDGE）；
        // 若目标已经死亡（今晚已被狼刀/毒），只合并死因，不重复产生死亡事实（见 applyDeaths）。
        if (target.alive()) {
            emit(new PlayerKilledEvent(
                    meta(ctx, EventType.PLAYER_KILLED),
                    target.playerId(),
                    target.seatNo(),
                    List.of(DeathCause.HUNTER_SHOT),
                    DeathCause.HUNTER_SHOT));
        }
        emit(new HunterShotEvent(
                meta(ctx, EventType.HUNTER_SHOT),
                actor.playerId(),
                actor.seatNo(),
                target.playerId(),
                target.seatNo()));
        emit(new DeathAnnouncedEvent(
                meta(ctx, EventType.DEATH_ANNOUNCED),
                List.of(new DeathAnnouncedEvent.DeathEntry(target.playerId(), target.seatNo()))));
    }

    // ------------------------------------------------------------------
    // 驱动循环：把"事件确定的阶段推进"一次性跑到稳定点
    // ------------------------------------------------------------------

    /**
     * 反复检查当前阶段的退出条件并推进，直到停在"等待外部输入"的阶段。
     *
     * <p>循环只由当前状态决定，不依赖任何外部计时或随机，因此命令处理与重放走的是同一条路径
     * （INV-16）。{@code MAX_PHASE_STEPS} 防御转换表配置错误导致的死循环。
     */
    private void runUntilStable(ActionContext ctx) {
        int steps = 0;
        while (status == GameStatus.RUNNING) {
            if (steps++ > MAX_PHASE_STEPS) {
                throw new IllegalStateException(
                        "阶段推进超过 " + MAX_PHASE_STEPS + " 步仍未稳定，请检查转换表：phase=" + phase);
            }
            switch (phase) {
                case NIGHT_WOLF -> {
                    if (!hasPendingActor()) {
                        selectWolfKill(ctx);
                    } else {
                        return;
                    }
                }
                case NIGHT_SEER, NIGHT_WITCH -> {
                    if (!hasPendingActor()) {
                        GamePhase next = nextNightPhase();
                        transition(ctx, next, null);
                    } else {
                        return;
                    }
                }
                case NIGHT_SETTLE -> settleNight(ctx);
                case DAY_ANNOUNCE -> {
                    // 死讯已在 applyDeaths 中公布；本阶段只是主流程上的标记（ddd-design.md §3.2）
                    transition(ctx, GamePhase.LAST_WORDS, GamePhase.DAY_DISCUSS);
                }
                case LAST_WORDS -> {
                    if (lastWordsIndex >= pendingLastWords.size()) {
                        finishLastWords(ctx);
                    } else if (currentTurn == null) {
                        grantNextTurn(ctx);
                    } else {
                        return;
                    }
                }
                case DAY_DISCUSS -> {
                    if (turnIndex >= turnOrder.size()) {
                        votes.clear();
                        transition(ctx, GamePhase.DAY_VOTE, null);
                    } else if (currentTurn == null) {
                        grantNextTurn(ctx);
                    } else {
                        return;
                    }
                }
                case DAY_VOTE -> {
                    if (snapshot().allAlivePlayersVoted()) {
                        transition(ctx, GamePhase.VOTE_SETTLE, null);
                    } else {
                        return;
                    }
                }
                case VOTE_SETTLE -> settleVotes(ctx);
                case HUNTER_SHOOT -> {
                    if (pendingHunter == null) {
                        finishHunterPhase(ctx);
                    } else if (currentTurn == null) {
                        grantTurnToHunter(ctx);
                    } else {
                        return;
                    }
                }
                case GAME_INIT, GAME_OVER -> {
                    return;
                }
            }
        }
    }

    /**
     * 当前阶段是否仍有"必须等待的外部行动者"。
     *
     * <p>这是"无技能持有者的阶段只产生 {@code PHASE_CHANGED}，不产生跳过事件"的实现
     * （domain-model.md §4）：角色已死亡时阶段直接通过。
     */
    private boolean hasPendingActor() {
        GameSnapshot state = snapshot();
        return switch (phase) {
            case NIGHT_WOLF -> {
                boolean anyAliveWolf = !RoleLocator.aliveWolves(state.players()).isEmpty();
                yield anyAliveWolf && !state.allAliveWolvesNominated();
            }
            case NIGHT_SEER -> {
                // 已放弃查验（inspectedTarget = -1）同样视为已行动
                boolean acted = inspectedTarget != null;
                yield !acted && RoleLocator.aliveHolderOf(state.players(), Role.SEER).isPresent();
            }
            case NIGHT_WITCH -> !state.witchActed()
                    && RoleLocator.aliveHolderOf(state.players(), Role.WITCH).isPresent();
            case DAY_VOTE -> !state.allAlivePlayersVoted();
            default -> false;
        };
    }

    private GamePhase nextNightPhase() {
        return switch (phase) {
            case NIGHT_SEER -> GamePhase.NIGHT_WITCH;
            case NIGHT_WITCH -> GamePhase.NIGHT_SETTLE;
            default -> throw GameException.Factory.notAllowed("阶段 " + phase + " 没有夜间后继");
        };
    }

    /** 狼刀定案：按提名票数定案并按平票规则裁决（WolfKillPolicy）。 */
    private void selectWolfKill(ActionContext ctx) {
        WolfKillPolicy.Decision decision = WolfKillPolicy.decide(wolfNominations, rules.wolfKillTieBreak());
        Integer targetSeat = decision.targetSeat();
        emit(new WolfKillSelectedEvent(
                meta(ctx, EventType.WOLF_KILL_SELECTED),
                targetSeat == null ? null : PlayerId.forSeat(SeatNo.of(targetSeat)),
                targetSeat == null ? null : SeatNo.of(targetSeat),
                decision.nominations()));
        transition(ctx, GamePhase.NIGHT_SEER, null);
    }

    /** 夜晚结算：狼刀 → 解药 → 毒药，输出死亡列表（domain-model.md §6.3）。 */
    private void settleNight(ActionContext ctx) {
        NightResolutionPolicy.Resolution resolution = NightResolutionPolicy.resolve(
                snapshot().players(), wolfKillTarget, witchSaved, witchPoisoned, round);
        applyDeaths(ctx, resolution.deaths(), DeathOrigin.NIGHT);
    }

    /**
     * 逐条产生 {@code PLAYER_KILLED}，再公布死讯并决定后继阶段。
     *
     * <p>死亡"发生"（{@code PLAYER_KILLED}，{@code JUDGE}）与死讯"公布"（{@code DEATH_ANNOUNCED}，
     * {@code PUBLIC}）是两件事，但都在本方法内完成：公布事件的死讯列表正是本次结算的死亡，
     * 因此两者必须成对产生，否则"遗言资格"会依赖一个不存在的事件。
     *
     * @param origin 死亡来源：夜间结算或白天放逐。它决定后继阶段与回合推进方式，
     *               也决定猎人开枪是否被允许（被枪击不再触发连锁开枪）
     */
    private void applyDeaths(ActionContext ctx, List<DeathRecord> records, DeathOrigin origin) {
        // 按座位号升序产生事件，避免集合遍历顺序影响事件顺序（INV-16）
        List<DeathRecord> ordered = records.stream()
                .sorted(Comparator.comparing(DeathRecord::seatNo))
                .toList();

        // 先记录"本次结算前已经死亡的座位"。判断必须在应用任何死亡之前完成，否则
        // 前面的死亡会把后面的记录误判成"已死过"。
        Set<Integer> deadBefore = ordered.stream()
                .map(death -> death.seatNo().value())
                .filter(seat -> !snapshot().playerAt(SeatNo.of(seat)).alive())
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));

        Optional<PlayerSnapshot> hunterToShoot = Optional.empty();
        for (DeathRecord death : ordered) {
            PlayerSnapshot player = snapshot().playerAt(death.seatNo());

            // 只有"本次结算才死亡"的座位产生 PLAYER_KILLED；结算前已死的座位只补记死因
            // （例如猎人枪击一个今晚已被刀死的座位），避免同一死亡事实出现两条事件。
            if (deadBefore.contains(death.seatNo().value())) {
                recordDeath(death);
            } else {
                emit(new PlayerKilledEvent(
                        meta(ctx, EventType.PLAYER_KILLED),
                        death.playerId(),
                        death.seatNo(),
                        List.copyOf(death.causes()),
                        death.primaryCause()));
            }

            // 开枪资格只看"是否被毒"（INV-8）与技能次数：狼刀死与被放逐的猎人都能开枪，
            // 遗言阶段结算出的猎人死亡同样可以（ddd-design.md §3.2）。
            if (hunterToShoot.isEmpty()
                    && player.role() == Role.HUNTER
                    && NightResolutionPolicy.canHunterShoot(
                            player, death, rules.hunterCanShootWhenPoisoned(), true)) {
                hunterToShoot = Optional.of(player);
            }
        }

        if (hunterToShoot.isPresent()) {
            // 夜里出局的猎人在天亮公布死讯之后开枪（2026-10-12 所有者批准）：否则同夜被毒的人在
            // 猎人视图里仍显示存活，选中后被拒绝就等于提前得知了死讯。
            if (origin == DeathOrigin.NIGHT) {
                emitDeathAnnounced(ctx);
            }
            // 猎人开枪是插入阶段：resumePhase 记录开枪结束后要回到的阶段。
            // 待开枪的猎人与"遗言之后回到哪里"都由 apply 侧从死亡台账派生（INV-13）。
            transition(ctx, GamePhase.HUNTER_SHOOT, GamePhase.LAST_WORDS);
            return;
        }

        // 无猎人可开枪（或猎人被毒，INV-8）：公布死讯后回到主流程
        emitDeathAnnounced(ctx);
        transition(
                ctx,
                origin == DeathOrigin.NIGHT ? GamePhase.DAY_ANNOUNCE : GamePhase.LAST_WORDS,
                origin == DeathOrigin.NIGHT ? GamePhase.DAY_DISCUSS : GamePhase.NIGHT_WOLF);
    }

    /**
     * 公布死讯并计算遗言资格。
     *
     * <p>{@code deaths} 只含"本轮的死亡事实"，空数组表示平安夜——事件仍然产生，前端据此显示
     * "昨夜无人死亡"，而不是靠"没有收到事件"推断（event-design.md §7.2）。
     */
    private void emitDeathAnnounced(ActionContext ctx) {
        List<DeathRecord> currentRound = deaths.stream()
                .filter(death -> death.round() == round)
                .sorted(Comparator.comparing(DeathRecord::seatNo))
                .toList();

        emit(new DeathAnnouncedEvent(
                meta(ctx, EventType.DEATH_ANNOUNCED),
                currentRound.stream()
                        .map(death -> new DeathAnnouncedEvent.DeathEntry(death.playerId(), death.seatNo()))
                        .toList()));
    }

    /**
     * 重算本轮遗言队列。
     *
     * <p>它是纯派生的：遗言资格只依赖"本轮死亡记录 + 规则参数"，不需要额外隐藏状态。
     * 命令路径与重放路径共用同一处实现（INV-13、INV-16）。
     *
     * @param resetCursor 是否把发言游标复位。只有"进入 LAST_WORDS"时才复位；
     *                    {@code DEATH_ANNOUNCED} 触发的重算必须保留游标——否则猎人开枪补发的那条
     *                    {@code DEATH_ANNOUNCED} 会把已经说过的遗言位置抹回 0（INV-16 曾因此失败）。
     *                    放逐后的遗言只取放逐记录及其后的死亡：清晨的夜间死者已在当天第一段遗言里发言。
     */
    private void recomputePendingLastWords(boolean resetCursor) {
        pendingLastWords.clear();
        int from = 0;
        for (int index = 0; index < deaths.size(); index++) {
            DeathRecord death = deaths.get(index);
            if (death.round() == round && death.causedBy(DeathCause.EXILE)) {
                from = index;
                break;
            }
        }
        deaths.subList(from, deaths.size()).stream()
                .filter(death -> death.round() == round)
                .filter(this::lastWordsAllowed)
                .map(DeathRecord::seatNo)
                .sorted()
                .forEach(pendingLastWords::add);
        turnOrder = List.copyOf(pendingLastWords);
        if (resetCursor) {
            lastWordsIndex = 0;
            turnIndex = 0;
        }
    }

    /** 重算白天发言顺序（首轮从最小存活座位开始，之后环形顺延）。 */
    private void recomputeDiscussionOrder() {
        turnOrder = TurnOrderPolicy.orderFor(snapshot().players(), previousDiscussionFirst);
        previousDiscussionFirst = turnOrder.isEmpty() ? null : turnOrder.get(0);
        turnIndex = 0;
    }

    /**
     * 遗言规则（domain-model.md §3）：被放逐者按 {@code exileLastWords}；本轮放逐之后被猎人带走的
     * 按 {@code dayShotLastWords}；其余死亡（含夜里出局的猎人天亮开枪带走的目标）按首夜/后续夜规则。
     */
    private boolean lastWordsAllowed(DeathRecord death) {
        if (death.causedBy(DeathCause.EXILE)) {
            return rules.exileLastWords();
        }
        if (death.causedBy(DeathCause.HUNTER_SHOT) && exiledEarlierThisRound(death)) {
            return rules.dayShotLastWords();
        }
        return rules.nightDeathHasLastWords(death.round());
    }

    private boolean exiledEarlierThisRound(DeathRecord death) {
        int position = deaths.indexOf(death);
        for (int index = 0; index < position; index++) {
            DeathRecord earlier = deaths.get(index);
            if (earlier.round() == death.round() && earlier.causedBy(DeathCause.EXILE)) {
                return true;
            }
        }
        return false;
    }

    /** 计票与放逐（domain-model.md §6.2 的 {@code VoteTallyPolicy}）。 */
    private void settleVotes(ActionContext ctx) {
        GameSnapshot state = snapshot();

        // 阶段级超时：未提交者记为弃权票（domain-model.md §4 的 DAY_VOTE 超时行为）
        for (PlayerSnapshot player : state.players()) {
            if (player.alive() && !votes.containsKey(player.seatNo().value())) {
                emit(new PlayerVotedEvent(
                        meta(ctx, EventType.PLAYER_VOTED),
                        player.playerId(),
                        player.seatNo(),
                        null,
                        null,
                        true));
            }
        }

        TallyResult tally = VoteTallyPolicy.tally(votes, state.players());
        emit(new VoteTallyResolvedEvent(
                meta(ctx, EventType.VOTE_TALLY_RESOLVED),
                tally.result(),
                tally.tally().stream()
                        .map(entry -> new VoteTallyResolvedEvent.TallyEntry(entry.seatNo(), entry.votes()))
                        .toList(),
                tally.maxVotes(),
                tally.exiled() ? PlayerId.forSeat(SeatNo.of(tally.exiledSeat())) : null,
                tally.tieSeats()));

        if (!tally.exiled()) {
            // 平票或全员弃权：无人出局，直接进入下一轮
            if (resolveWin(ctx)) {
                return;
            }
            startNextRound(ctx);
            return;
        }

        SeatNo exiledSeat = SeatNo.of(tally.exiledSeat());
        PlayerSnapshot exiled = RoleLocator.at(state.players(), exiledSeat)
                .orElseThrow(() -> GameException.Factory.invalidTarget("座位 " + exiledSeat + " 不存在"));
        DeathRecord death = DeathRecord.announced(
                exiled.playerId(), exiledSeat, EnumSet.of(DeathCause.EXILE), round);
        applyDeaths(ctx, List.of(death), DeathOrigin.EXILE);
    }

    /** 遗言结束后：判定胜负，否则回到 {@code pendingAfterLastWords}。 */
    private void finishLastWords(ActionContext ctx) {
        GamePhase target = pendingAfterLastWords;
        // 这里刻意不清空 pendingAfterLastWords / pendingDeathOrigin：
        // 它们只在"进入 LAST_WORDS"时被重算，命令路径的清空会让最终快照与重放分叉（INV-16）。
        if (resolveWin(ctx)) {
            return;
        }
        if (target == null || target == GamePhase.NIGHT_WOLF) {
            startNextRound(ctx);
        } else {
            transition(ctx, target, null);
        }
    }

    /** 猎人开枪或放弃后：判定胜负，否则回到 {@code resumePhase}。 */
    private void finishHunterPhase(ActionContext ctx) {
        GamePhase resume = resumePhase;
        resumePhase = null;
        if (deaths.stream().anyMatch(death -> death.round() == round && !death.announced())) {
            emitDeathAnnounced(ctx);
        }
        if (resolveWin(ctx)) {
            return;
        }
        if (resume == null || resume == GamePhase.LAST_WORDS) {
            // 开枪结束后仍要处理遗言：回到 LAST_WORDS，并记住遗言之后的去向
            GamePhase after = pendingDeathOrigin == DeathOrigin.NIGHT
                    ? GamePhase.DAY_DISCUSS
                    : GamePhase.NIGHT_WOLF;
            transition(ctx, GamePhase.LAST_WORDS, after);
            return;
        }
        transition(ctx, resume, null);
    }

    /** 停滞中止的原因码（{@code GAME_ABORTED.reason}）。 */
    public static final String STALLED_REASON = "STALLED";

    /**
     * 未分胜负：进入下一轮。轮次在跳转前推进，并由 {@code PHASE_CHANGED} 的 round 字段固化。
     *
     * <p>连续 {@code stalledRoundsLimit} 轮无人出局时改为中止（domain-model.md §3）：
     * 全员挂机的对局狼人不刀、全员弃票，否则会无限循环下去。
     */
    private void startNextRound(ActionContext ctx) {
        if (stalled()) {
            transition(ctx, GamePhase.GAME_OVER, null);
            emit(new GameAbortedEvent(meta(ctx, EventType.GAME_ABORTED), STALLED_REASON, version + 1));
            return;
        }
        votes.clear();
        round++;
        transition(ctx, GamePhase.NIGHT_WOLF, null);
    }

    /** 含本轮在内的最近 {@code stalledRoundsLimit} 轮都没有任何死亡记录。 */
    private boolean stalled() {
        int limit = rules.stalledRoundsLimit();
        if (limit <= 0 || round < limit) {
            return false;
        }
        int firstRound = round - limit + 1;
        return deaths.stream().noneMatch(death -> death.round() >= firstRound);
    }

    /**
     * 胜负判定；已分出胜负时产生 {@code PLAYER_KILLED}（若目标已死）与终结事件并返回 true。
     *
     * <p>终局必须由事件承载：先产生 {@code GAME_FINISHED}，再产生 {@code PHASE_CHANGED(→GAME_OVER)}，
     * 因为 domain-model.md §4 把 {@code GAME_OVER} 的"进入动作"定义为 {@code PHASE_CHANGED}，
     * 且 event-design.md §14.4 要求"每个阶段跳转都有 PHASE_CHANGED"。
     */
    private boolean resolveWin(ActionContext ctx) {
        // 幂等保护：终局判定可能在同一轮驱动中被多次到达（例如遗言结束与下一阶段收尾），
        // 已结束的对局不得再次产生 GAME_FINISHED 或 GAME_OVER 跳转。
        if (status != GameStatus.RUNNING) {
            return true;
        }
        Optional<WinConditionPolicy.Verdict> verdict =
                WinConditionPolicy.check(snapshot().players(), rules.winCondition());
        if (verdict.isEmpty()) {
            return false;
        }
        // 顺序固定：先产生最后一段 PHASE_CHANGED（此时 phase 仍是 LAST_WORDS / VOTE_SETTLE /
        // HUNTER_SHOOT，转换表校验才有意义），再产生 GAME_FINISHED。
        // 终态由 applyPhaseChanged（phase=GAME_OVER）与 applyGameFinished（status=FINISHED）写入。
        transition(ctx, GamePhase.GAME_OVER, null);
        emit(new GameFinishedEvent(
                meta(ctx, EventType.GAME_FINISHED),
                verdict.get().winner(),
                verdict.get().reason(),
                round,
                snapshot().alivePlayers().stream().map(PlayerSnapshot::seatNo).toList(),
                List.of()));
        return true;
    }

    /**
     * 阶段跳转：先校验转换表（ddd-design.md §3.2），再写状态，最后做进入初始化。
     *
     * @param resume 插入阶段的返回点；非插入阶段传 {@code null}
     * @param resume 插入阶段的返回点；非插入阶段传 {@code null}
     */
    private void transition(ActionContext ctx, GamePhase to, GamePhase resume) {

        GamePhase from = phase;
        if (!from.allowsNext(to)) {
            throw GameException.Factory.notAllowed(
                    "阶段转换 " + from + " → " + to + " 不在转换表内（ddd-design.md §3.2）");
        }

        Long deadline = null;
        if (to == GamePhase.DAY_VOTE) {
            deadline = ctx.occurredAt() + rules.voteDeadlineMs();
        } else if (to == GamePhase.NIGHT_WOLF || to == GamePhase.NIGHT_SEER
                || to == GamePhase.NIGHT_WITCH) {
            deadline = ctx.occurredAt() + rules.turnDeadlineMs();
        }

        emit(new PhaseChangedEvent(
                meta(ctx, EventType.PHASE_CHANGED),
                from,
                to,
                0,
                to == GamePhase.HUNTER_SHOOT ? resume : null,
                deadline));
        // 注意：resumePhase / pendingHunter / pendingAfterLastWords 一律由 applyPhaseChanged 从事件派生。
        // 曾在这里直接赋值，导致"纯聚合测试全绿、但经仓储 load() 全量重放时丢字段"——
        // 第 1 轮白天会被整段跳过（verifier 的 replayRestoresPhaseRoutingFields 抓到）。
        initPhase(ctx, to);
    }

    /** 进入某阶段时的初始化（命令路径与重放路径共用，保证 INV-16）。 */
    private void initPhase(ActionContext ctx, GamePhase entered) {
        switch (entered) {
            case DAY_DISCUSS -> {
                // 派生状态在 applyPhaseChanged 里重算；命令路径由随后的 grantNextTurn 发放回合
            }
            case LAST_WORDS, HUNTER_SHOOT -> currentTurn = null;
            default -> {
                // 其它阶段的进入动作已由 applyPhaseChanged 与各自的结算方法完成
            }
        }
    }

    /** 为 {@code turnOrder} 的当前下标发放回合窗口。 */
    private void grantNextTurn(ActionContext ctx) {
        int cursor = phase == GamePhase.LAST_WORDS ? lastWordsIndex : turnIndex;
        if (cursor >= turnOrder.size()) {
            currentTurn = null;
            return;
        }
        SeatNo seatNo = turnOrder.get(cursor);
        PlayerSnapshot player = RoleLocator.at(snapshot().players(), seatNo)
                .orElseThrow(() -> GameException.Factory.invalidTarget("座位 " + seatNo + " 不存在"));
        emit(new TurnGrantedEvent(
                meta(ctx, EventType.TURN_GRANTED),
                player.playerId(),
                seatNo,
                phase,
                cursor,
                ctx.occurredAt() + rules.turnDeadlineFor(player.controller().type()),
                player.controller().type()));
    }

    private void grantTurnToHunter(ActionContext ctx) {
        if (pendingHunter == null) {
            return;
        }
        SeatNo seatNo = SeatNo.of(pendingHunter);
        PlayerSnapshot hunter = RoleLocator.at(snapshot().players(), seatNo)
                .orElseThrow(() -> GameException.Factory.invalidTarget("猎人座位不存在"));
        emit(new TurnGrantedEvent(
                meta(ctx, EventType.TURN_GRANTED),
                hunter.playerId(),
                seatNo,
                GamePhase.HUNTER_SHOOT,
                0,
                ctx.occurredAt() + rules.turnDeadlineFor(hunter.controller().type()),
                hunter.controller().type()));
    }

    // ------------------------------------------------------------------
    // 事件应用：全类唯一修改字段的地方（INV-13）
    // ------------------------------------------------------------------

    private void begin(ActionContext ctx) {
        uncommittedEvents.clear();
        if (ctx.requiresVersionCheck() && ctx.expectedVersion() != version) {
            throw GameException.Factory.versionConflict(ctx.expectedVersion(), version);
        }
    }

    private EventMeta meta(ActionContext ctx, EventType type) {
        return new EventMeta(
                // eventId 由 (commandId, version) 派生：可排序、确定、不需要 UUID
                ctx.eventIdFor(version + 1),
                version + 1,
                ctx.occurredAt(),
                phase,
                round,
                ctx.actorId(),
                ctx.commandId(),
                ctx.correlationId());
    }

    /** 记录事件并立即应用：改状态与记录事件必须成对（domain-model.md §2.2 第 4 步）。 */
    private <T extends DomainEvent> T emit(T event) {
        uncommittedEvents.add(event);
        apply(event);
        return event;
    }

    private void apply(DomainEvent event) {
        switch (event) {
            case GameStartedEvent started -> applyGameStarted(started);
            case RoleAssignedEvent assigned -> applyRoleAssigned(assigned);
            case PhaseChangedEvent changed -> applyPhaseChanged(changed);
            case TurnGrantedEvent granted -> applyTurnGranted(granted);
            case PlayerSpokeEvent spoke -> applyPlayerSpoke(spoke);
            case SpeechSkippedEvent skipped -> applySpeechSkipped(skipped);
            case PlayerVotedEvent voted -> applyPlayerVoted(voted);
            case WolfKillNominatedEvent nominated -> applyWolfKillNominated(nominated);
            case WolfKillSelectedEvent selected -> applyWolfKillSelected(selected);
            case WolfChatSentEvent chat -> wolfChatCounts.merge(chat.wolfSeatNo().value(), 1, Integer::sum);
            case SeerInspectedEvent inspected -> applySeerInspected(inspected);
            case SeerSkippedEvent skipped -> applySeerSkipped(skipped);
            case WitchSavedEvent saved -> applyWitchSaved(saved);
            case WitchPoisonedEvent poisoned -> applyWitchPoisoned(poisoned);
            case WitchPassedEvent passed -> applyWitchPassed(passed);
            case HunterShotEvent shot -> applyHunterShot(shot);
            case HunterSkippedEvent skipped -> applyHunterSkipped(skipped);
            case VoteTallyResolvedEvent tally -> applyVoteTally(tally);
            case PlayerKilledEvent killed -> applyPlayerKilled(killed);
            case DeathAnnouncedEvent announced -> applyDeathAnnounced(announced);
            case GameFinishedEvent finished -> applyGameFinished(finished);
            case GameAbortedEvent aborted -> applyGameAborted(aborted);
            case PlayerControllerChangedEvent changed -> applyControllerChanged(changed);
            case GameRecoveredEvent ignored -> {
                // 恢复事件不改变对局状态：它是运维事实，落库与审计即可
            }
            default -> throw GameException.Factory.unknownEventType(event.type().code());
        }
        version = event.version();
        if (!(event instanceof WolfChatSentEvent)) {
            stateVersion = event.version();
        }
        // 终态字段由 applyGameFinished / applyGameAborted 写入，保证"状态只能由 apply 修改"（INV-13）
    }

    private void applyGameStarted(GameStartedEvent event) {
        status = GameStatus.RUNNING;
        phase = GamePhase.GAME_INIT;
        round = event.startedRound();
        roleComposition = event.roleComposition();
        rules = event.rules();
        roomId = event.roomId();
        turnOrder = event.turnOrder().stream().map(SeatNo::of).toList();
        turnIndex = 0;

        // 座位名单是开局事实，必须能从 GAME_STARTED 重建：否则"全量重放"无法还原，
        // 而重放是恢复与投影重建的唯一真相来源（INV-15、INV-16）。
        // 能开局就说明当时全部座位就绪（INV-R2），因此 ready 统一为 true。
        players.clear();
        for (GameStartedEvent.SeatSpec seat : event.seats()) {
            SeatNo seatNo = SeatNo.of(seat.seatNo());
            players.put(seatNo, new Player(
                    PlayerId.of(seat.playerId()), seatNo, seat.controller(), true));
        }
    }

    private void applyRoleAssigned(RoleAssignedEvent event) {
        Player player = players.get(event.seatNo());
        if (player == null) {
            throw GameException.Factory.invalidTarget("角色分配引用了不存在的座位 " + event.seatNo());
        }
        player.applyRole(event.role());
    }

    private void applyPhaseChanged(PhaseChangedEvent event) {
        phase = event.to();
        resumePhase = event.resumePhase();
        currentTurn = null;
        // 待开枪的猎人只在 HUNTER_SHOOT 期间有效：进入时从死亡台账派生，离开时清空。
        // 不用"命令路径传进来的座位"，否则重放无法还原（INV-13）；
        // 也不能只靠 TURN_GRANTED 派生——那条回合要等 pendingHunter 非空才发放，会形成循环依赖。
        pendingHunter = event.to() == GamePhase.HUNTER_SHOOT ? derivePendingHunter() : null;
        // 轮次完全由事件决定（INV-13）：命令路径在跳转前已推进 round，重放路径也从这里取，
        // 因此"实时处理"与"重放"不可能出现轮次差异（曾因 round++ 写在 apply 之外而被 INV-16 测试抓到）。
        if (event.round() < round) {
            throw GameException.Factory.replayOutOfOrder(round, event.round());
        }
        round = event.round();
        switch (event.to()) {
            case NIGHT_WOLF -> {
                wolfKillTarget = null;
                wolfNominations.clear();
                wolfChatCounts.clear();
                witchSaved = null;
                witchPoisoned = null;
                witchPassed = false;
                inspectedTarget = null;
                inspectResult = null;
                turnOrder = List.of();
                turnIndex = 0;
                lastWordsIndex = 0;
                pendingLastWords.clear();
                pendingAfterLastWords = null;
                // votes 必须在进入夜晚时清空：否则重放路径会残留上一轮票（INV-16 曾因此失败）
                votes.clear();
            }
            case DAY_VOTE -> votes.clear();
            case LAST_WORDS -> {
                // 遗言结束后回到哪里：由"本轮是否发生过放逐"派生——被放逐者的遗言走 NIGHT_WOLF
                // （下一轮夜晚），夜间死亡的遗言走 DAY_DISCUSS（白天讨论）。
                // 用"死亡记录 + 死因"而不是"是否已公布"：后者在 DEATH_ANNOUNCED 之后会翻面，
                // 让同一条重放得出不同结果（INV-16 曾因此失败）。
                boolean exiledThisRound = exiledInRound();
                pendingAfterLastWords = exiledThisRound ? GamePhase.NIGHT_WOLF : GamePhase.DAY_DISCUSS;
                pendingDeathOrigin = exiledThisRound ? DeathOrigin.EXILE : DeathOrigin.NIGHT;
                recomputePendingLastWords(true);
            }
            case DAY_DISCUSS -> recomputeDiscussionOrder();
            default -> {
                // 其它阶段无需清理：暂存状态已由前置结算消费
            }
        }
    }

    private void applyTurnGranted(TurnGrantedEvent event) {
        currentTurn = new Turn(
                event.playerId(),
                event.seatNo(),
                event.turnPhase(),
                event.turnIndex(),
                event.deadline());
    }

    /**
     * 从死亡台账派生"待开枪的猎人"座位。
     *
     * <p>判据与 {@code applyDeaths} 选择猎人时完全一致：本轮死亡的猎人、开枪次数仍在、
     * 且未被毒（除非规则允许被毒开枪）。因此实时路径与重放路径得到同一结果，不依赖任何
     * 只存在于命令路径的临时变量。
     */
    private Integer derivePendingHunter() {
        return deaths.stream()
                .filter(death -> death.round() == round)
                .filter(death -> death.causedBy(DeathCause.WOLF_KILL) || death.causedBy(DeathCause.EXILE))
                .filter(death -> !death.causedBy(DeathCause.HUNTER_SHOT))
                .map(death -> snapshot().playerAt(death.seatNo()))
                .filter(player -> player.role() == Role.HUNTER)
                .filter(player -> player.skillState().shotAvailable())
                .filter(player -> rules.hunterCanShootWhenPoisoned()
                        || !deaths.stream()
                                .anyMatch(d -> d.seatNo().equals(player.seatNo())
                                        && d.causedBy(DeathCause.WITCH_POISON)))
                .map(player -> player.seatNo().value())
                .findFirst()
                .orElse(null);
    }

    /** 本轮是否发生过放逐。放逐是"遗言之后进入下一轮夜晚"的唯一判据。 */
    private boolean exiledInRound() {
        return deaths.stream()
                .anyMatch(death -> death.round() == round && death.causedBy(DeathCause.EXILE));
    }

    private void applyPlayerSpoke(PlayerSpokeEvent event) {
        // 游标推进只由事件驱动：命令路径与重放路径共用这一处赋值（INV-13、INV-16）
        advanceSpeakerCursor();
    }

    private void applySpeechSkipped(SpeechSkippedEvent event) {
        advanceSpeakerCursor();
    }

    /** 发言或跳过后推进"轮到谁"的游标；遗言用独立的游标，两者不能混用。 */
    private void advanceSpeakerCursor() {
        if (phase == GamePhase.LAST_WORDS) {
            lastWordsIndex++;
        } else {
            turnIndex++;
        }
        currentTurn = null;
    }

    private void applyPlayerVoted(PlayerVotedEvent event) {
        votes.put(
                event.seatNo().value(),
                Vote.resolve(
                        event.seatNo().value(),
                        event.targetSeatNo() == null ? null : event.targetSeatNo().value()));
    }

    private void applyWolfKillNominated(WolfKillNominatedEvent event) {
        wolfNominations.add(new WolfKillSelectedEvent.Nomination(
                event.wolfSeatNo().value(),
                event.targetSeatNo() == null ? null : event.targetSeatNo().value()));
    }

    private void applyWolfKillSelected(WolfKillSelectedEvent event) {
        wolfKillTarget = event.targetSeatNo() == null ? null : event.targetSeatNo().value();
    }

    private void applySeerInspected(SeerInspectedEvent event) {
        inspectedTarget = event.targetSeatNo() == null ? null : event.targetSeatNo().value();
        inspectResult = event.result();
    }

    private void applySeerSkipped(SeerSkippedEvent event) {
        // -1 表示"本夜已放弃查验"：与 null（尚未行动）区分，否则阶段退出条件无法判定
        inspectedTarget = -1;
        inspectResult = null;
    }

    private void applyWitchSaved(WitchSavedEvent event) {
        Player witch = players.get(event.witchSeatNo());
        witch.applySkillState(witch.skillState().consumeAntidote());
        witchSaved = event.targetSeatNo() == null ? null : event.targetSeatNo().value();
    }

    private void applyWitchPoisoned(WitchPoisonedEvent event) {
        Player witch = players.get(event.witchSeatNo());
        witch.applySkillState(witch.skillState().consumePoison());
        witchPoisoned = event.targetSeatNo() == null ? null : event.targetSeatNo().value();
    }

    private void applyWitchPassed(WitchPassedEvent event) {
        witchPassed = true;
    }

    private void applyHunterShot(HunterShotEvent event) {
        Player hunter = players.get(event.hunterSeatNo());
        hunter.applySkillState(hunter.skillState().consumeShot());
        pendingHunter = null;
        currentTurn = null;
    }

    private void applyHunterSkipped(HunterSkippedEvent event) {
        Player hunter = players.get(event.hunterSeatNo());
        hunter.applySkillState(hunter.skillState().consumeShot());
        pendingHunter = null;
        currentTurn = null;
    }

    private void applyVoteTally(VoteTallyResolvedEvent event) {
        // 计票结果本身不改状态：出局者通过随后的 PLAYER_KILLED 落地
    }

    private void applyPlayerKilled(PlayerKilledEvent event) {
        var causes = EnumSet.copyOf(event.causes());
        // 放逐和枪击已有公开事件；夜间刀毒死亡等待 DEATH_ANNOUNCED。
        boolean announced = causes.contains(DeathCause.EXILE) || causes.contains(DeathCause.HUNTER_SHOT);
        recordDeath(announced
                ? DeathRecord.announced(event.playerId(), event.seatNo(), causes, event.round())
                : DeathRecord.of(event.playerId(), event.seatNo(), causes, event.round()));
    }

    /**
     * 登记一条死亡：置死亡标记、按死因调整技能可用性、写入死亡台账。
     *
     * <p>它是"死亡事实"的唯一落地点，因此猎人枪击受害者也走这里，但**不产生第二条**
     * {@code PLAYER_KILLED}（死亡事实只记录一次，避免同一事实出现两次）。
     */
    private void recordDeath(DeathRecord record) {
        Player player = players.get(record.seatNo());
        if (player == null) {
            throw GameException.Factory.invalidTarget("死亡引用了不存在的座位 " + record.seatNo());
        }
        player.kill();
        // 被毒则失去开枪能力：技能可用性读 causes 集合而不是 primaryCause（event-design.md §7.2）
        if (record.causedBy(DeathCause.WITCH_POISON)) {
            player.applySkillState(player.skillState().disableShot());
        }
        deaths.add(record);
    }

    private void applyDeathAnnounced(DeathAnnouncedEvent event) {
        Set<Integer> announcedSeats = new LinkedHashSet<>();
        event.deaths().forEach(death -> announcedSeats.add(death.seatNo().value()));
        for (int index = 0; index < deaths.size(); index++) {
            DeathRecord record = deaths.get(index);
            if (record.round() == event.round() && announcedSeats.contains(record.seatNo().value())) {
                deaths.set(index, record.markAnnounced());
            }
        }
        // 遗言队列是纯派生状态，从"本轮已公布的死亡 + 规则"重算；游标保持不变
        recomputePendingLastWords(false);
    }

    private void applyGameFinished(GameFinishedEvent event) {
        status = GameStatus.FINISHED;
        winner = event.winner();
        finishReason = event.reason();
    }

    private void applyGameAborted(GameAbortedEvent event) {
        status = GameStatus.ABORTED;
        finishReason = event.reason();
    }

    private void applyControllerChanged(PlayerControllerChangedEvent event) {
        Player player = players.get(event.seatNo());
        if (player == null) {
            throw GameException.Factory.invalidTarget("座位 " + event.seatNo() + " 不存在");
        }
        player.changeController(new PlayerController(event.toType(), event.toRefId()));
    }

    // ------------------------------------------------------------------
    // 供应用层构造超时兜底 Action 的查询
    // ------------------------------------------------------------------

    /** 全部存活玩家座位号，按升序。 */
    public List<SeatNo> aliveSeats() {
        return players.values().stream()
                .filter(Player::alive)
                .map(Player::seatNo)
                .sorted()
                .toList();
    }

    /** 未投票的存活座位：应用层据此构造弃权兜底。 */
    public List<SeatNo> seatsAwaitingVote() {
        return players.values().stream()
                .filter(Player::alive)
                .map(Player::seatNo)
                .filter(seat -> !votes.containsKey(seat.value()))
                .sorted()
                .toList();
    }

    /** 尚未提名的存活狼人座位：应用层据此构造弃刀兜底。 */
    public List<SeatNo> wolvesAwaitingNomination() {
        Set<Integer> nominated = new LinkedHashSet<>();
        wolfNominations.forEach(nomination -> nominated.add(nomination.wolfSeatNo()));
        return RoleLocator.aliveWolves(snapshot().players()).stream()
                .map(PlayerSnapshot::seatNo)
                .filter(seat -> !nominated.contains(seat.value()))
                .sorted()
                .toList();
    }

    /**
     * 超时看护：把"到期"翻译成一组合法 {@link SkipAction}，外加是否需要一次阶段推进。
     *
     * <p>它是"超时是合法输入而非异常"（ddd-design.md §3.2）的落点。**领域不读时钟**，
     * 因此"是否到期"由应用层判定后作为 {@code phaseDeadlinePassed} 传入；本方法只回答
     * "到期该如何表达成领域输入"，不自己比较时间。
     *
     * <p>调用方（应用层超时看护）的标准用法：
     * <pre>
     *   Game.HandlingExpiredDeadline handling = game.timeoutSkips(now, deadlinePassed);
     *   for (SkipAction skip : handling.skips()) { game.handle(skip, ctxForCurrentVersion()); }
     *   if (handling.advanceNeeded()) { game.advance(ctxForCurrentVersion()); }
     * </pre>
     * 每个 {@code SkipAction} 必须单独调用一次 {@code handle}（一次 handle 只接受一个行动），
     * 且每次都按当时的 {@code version()} 重新构造 {@link ActionContext}。
     *
     * @param nowMillis           当前毫秒时间戳；用于有回合窗口的阶段比较 {@code Turn.deadline}
     * @param phaseDeadlinePassed 阶段级 deadline 是否已过；{@code PHASE_CHANGED.deadline} 给出，
     *                            用于无回合窗口的阶段（{@code DAY_VOTE}、{@code NIGHT_WOLF} 等）
     */
    public HandlingExpiredDeadline timeoutSkips(long nowMillis, boolean phaseDeadlinePassed) {
        if (status != GameStatus.RUNNING) {
            return new HandlingExpiredDeadline(List.of(), false);
        }
        List<SkipAction> skips = new ArrayList<>();
        boolean advanceNeeded = false;
        switch (phase) {
            case DAY_DISCUSS, LAST_WORDS -> {
                Turn turn = currentTurn;
                if (turn != null && turn.expiredAt(nowMillis)) {
                    skips.add(SkipAction.timeout(turn.seatNo().value()));
                }
            }
            case HUNTER_SHOOT -> {
                if (pendingHunter != null && (currentTurn == null
                        ? phaseDeadlinePassed : currentTurn.expiredAt(nowMillis))) {
                    skips.add(SkipAction.timeout(pendingHunter));
                }
            }
            case DAY_VOTE -> {
                if (phaseDeadlinePassed) {
                    // 未投票者记为弃权票：每个座位走同一条 handle 路径，因此仍可重放
                    seatsAwaitingVote().forEach(seat -> skips.add(SkipAction.timeout(seat.value())));
                }
            }
            case NIGHT_WOLF -> {
                if (phaseDeadlinePassed) {
                    wolvesAwaitingNomination().forEach(seat -> skips.add(SkipAction.timeout(seat.value())));
                }
            }
            case NIGHT_SEER -> {
                if (phaseDeadlinePassed && !snapshot().seerActed()) {
                    RoleLocator.aliveHolderOf(snapshot().players(), Role.SEER)
                            .ifPresent(seer -> skips.add(SkipAction.timeout(seer.seatNo().value())));
                }
            }
            case NIGHT_WITCH -> {
                if (phaseDeadlinePassed && !snapshot().witchActed()) {
                    RoleLocator.aliveHolderOf(snapshot().players(), Role.WITCH)
                            .ifPresent(witch -> skips.add(SkipAction.timeout(witch.seatNo().value())));
                }
            }
            case DAY_ANNOUNCE, NIGHT_SETTLE, VOTE_SETTLE -> {
                // 自动结算阶段等待应用层的推进调用，不需要任何 SkipAction
                advanceNeeded = true;
            }
            default -> {
                // GAME_INIT / GAME_OVER 没有需要兜底的行动者
            }
        }
        return new HandlingExpiredDeadline(List.copyOf(skips), advanceNeeded);
    }

    /** 只要"需要提交的跳过行动"时的便捷形式；语义与 {@link #timeoutSkips(long, boolean)} 完全一致。 */
    public List<SkipAction> skipActionsOnTimeout(long nowMillis, boolean phaseDeadlinePassed) {
        return timeoutSkips(nowMillis, phaseDeadlinePassed).skips();
    }

    /** 超时处理的答案：需要提交的跳过行动，以及是否需要一次阶段推进。 */
    public record HandlingExpiredDeadline(List<SkipAction> skips, boolean advanceNeeded) {

        public HandlingExpiredDeadline {
            skips = List.copyOf(skips);
        }

        public boolean isEmpty() {
            return skips.isEmpty() && !advanceNeeded;
        }
    }

    /** 死亡来源：决定"无猎人可开枪时"的后继阶段与回合推进方式。 */
    private enum DeathOrigin {
        /** 夜间结算产生的死亡。 */
        NIGHT,
        /** 白天计票放逐产生的死亡。 */
        EXILE
    }
}
