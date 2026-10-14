package com.wolfmind.application.room;

import com.wolfmind.application.game.StartGameApplicationService;
import com.wolfmind.application.port.AgentProvisioningPort;
import com.wolfmind.domain.game.model.ControllerType;
import com.wolfmind.domain.game.model.GameId;
import com.wolfmind.domain.game.model.GameRules;
import com.wolfmind.domain.game.model.RoleComposition;
import com.wolfmind.domain.game.model.SeatAssignment;
import com.wolfmind.domain.game.model.StartGameCommand;
import com.wolfmind.domain.room.model.Room;
import com.wolfmind.domain.room.model.RoomSeat;
import com.wolfmind.domain.room.model.RoomStatus;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 开局编排：把房间变成一局可玩的对局（api-spec.md §5.1）。
 *
 * <p><b>为什么需要单独一个用例服务</b>：开局跨越两个聚合——房间要冻结座位并记住 gameId，
 * 对局要发牌。两个聚合各有自己的锁与事件流，任何一步失败都必须让整体停在可解释的位置：
 * <ul>
 *   <li>先创建对局（发牌）：失败则房间仍为 WAITING，玩家可以重试，座位没被冻结；</li>
 *   <li>再冻结房间：失败则对局已存在但房间还显示可入座 —— 用 gameId 派生（同一房间只能开一局）
 *       保证重试时会撞上 {@code GAME_ALREADY_STARTED} 而不是开出第二局。</li>
 * </ul>
 * 这个顺序是刻意的：宁可留下"房间还没冻结、但对局已存在"这种可检测的不一致，
 * 也不要留下"房间已冻结、但没有对局"——后者让 9 个玩家卡在一个打不开的房间里。
 *
 * <p>本类不含规则判断：座位够不够、是否全部就绪由 {@link Room} 决定，
 * 角色分配由 {@code Game.start} 决定。
 */
public final class StartRoomGameApplicationService {

    private final RoomRepositoryPort roomRepository;
    private final RoomApplicationService roomCommands;
    private final StartGameApplicationService startGame;
    private final AgentProvisioningPort agents;

    public StartRoomGameApplicationService(
            RoomRepositoryPort roomRepository,
            RoomApplicationService roomCommands,
            StartGameApplicationService startGame,
            AgentProvisioningPort agents) {
        this.roomRepository = Objects.requireNonNull(roomRepository, "roomRepository");
        this.roomCommands = Objects.requireNonNull(roomCommands, "roomCommands");
        this.startGame = Objects.requireNonNull(startGame, "startGame");
        this.agents = Objects.requireNonNull(agents, "agents");
    }

    /**
     * 开局结果。
     *
     * @param gameId          对局标识，客户端据此跳到对局页并订阅
     * @param version         对局版本（开局后为 11：GAME_STARTED + 9×ROLE_ASSIGNED + PHASE_CHANGED）
     * @param phase           开局后的阶段（正常为 {@code NIGHT_WOLF}）
     * @param agentCount      已实例化的 AI 座位数
     * @param room            冻结后的房间视图
     */
    public record StartResult(
            String gameId, int version, String phase, int agentCount, RoomView room) {}

    /**
     * 开局。
     *
     * @param roomId        房间号
     * @param actorId       发起者（必须是房主，由领域校验）
     * @param idempotencyKey 客户端幂等键
     */
    public StartResult start(String roomId, String actorId, String idempotencyKey) {
        Room room = roomRepository.load(roomId).room();
        if (room.status() == null) {
            throw com.wolfmind.domain.game.model.GameException.Factory.roomNotFound(roomId);
        }
        // 已开局：返回既有对局而不是再开一局（幂等）。重复点击"开始游戏"是常见操作，
        // 让它失败会让房主以为按钮坏了。
        if (room.status() == RoomStatus.RUNNING && room.gameId() != null) {
            return existing(room);
        }

        // 先检查房间是否坐满：不检查的话，座位数不足会一路走到 Game.start，
        // 由角色配置的"9 人 vs 实际座位数"校验拒绝，报出 ROLE_COMPOSITION_INVALID(400)。
        // 那个码的语义是"角色配置与人数不匹配"（配置错误），而这里的真实原因是
        // "还没坐满"（房间状态）。用 409 NOT_READY 更准确——客户端据此提示"等待玩家"，
        // 而不是去改一个它根本没传过的配置。
        if (room.occupiedCount() < Room.SEAT_COUNT) {
            throw com.wolfmind.domain.game.model.GameException.Factory.notReady(
                    "房间还差 " + (Room.SEAT_COUNT - room.occupiedCount()) + " 位玩家，不能开局");
        }

        // gameId 由 roomId 派生：一个房间只能开一局，因此它是确定性且不冲突的。
        // 这也让"创建对局成功但冻结房间失败"后的重试会撞上 GAME_ALREADY_STARTED，
        // 而不是悄悄开出第二局。
        String gameId = "g_" + roomId;

        List<SeatAssignment> seats = seatAssignments(room);
        RoleComposition composition = roleComposition(room);
        GameRules rules = com.wolfmind.application.game.GameRulesCodec.fromRoomConfig(room.config());

        // Event consumption may begin as soon as the game commit succeeds.
        int agentCount = agents.prepare(gameId, agentSeats(seats));

        var actionResult = startGame.start(
                new StartGameCommand(GameId.of(gameId), roomId, seats, composition, rules),
                actorId,
                idempotencyKey);

        // 对局已发牌，现在冻结房间并绑定 gameId。
        RoomView frozen = roomCommands.startGame(roomId, actorId, gameId, idempotencyKey + ":room");

        return new StartResult(
                actionResult.gameId(),
                actionResult.version(),
                // 开局后的阶段恒为 NIGHT_WOLF（GAME_STARTED 的契约，api-spec.md §5.1 的响应示例）。
                // 不去读聚合推断阶段：那会用一次额外加载换一个不会变的常量。
                "NIGHT_WOLF",
                agentCount,
                frozen);
    }

    /**
     * 进程重启后为运行中对局补建 AI Agent。
     *
     * <p>Agent 实例与短期记忆只在进程内，事件流与状态在 Redis 里存活。不补建的话，重启后每个
     * AI 回合都以 {@code AGENT_NOT_REGISTERED} 失败，对局只能靠超时跳过推进。已有实例的对局不重复创建。
     *
     * @return 补建的座位数
     */
    public int restoreAgents(String gameId, List<SeatAssignment> seats) {
        return agents.restore(gameId, agentSeats(seats));
    }

    private static List<AgentProvisioningPort.Seat> agentSeats(List<SeatAssignment> seats) {
        return seats.stream().map(seat -> new AgentProvisioningPort.Seat(
                com.wolfmind.domain.game.model.PlayerId.forSeat(seat.seatNo()).value(),
                seat.seatNo().value(), seat.controller().type() == ControllerType.AI)).toList();
    }
    /**
     * 房间座位 → 开局座位；顺序按座位号升序（角色分配依赖这个顺序的确定性）。
     *
     * <p><b>AI 座位的控制者标识在此归一化为 `p_{seat}`。</b>
     *
     * <p>原因：`Room.seatAi` 把调用方给的 `agentConfigId` **同时当作 playerId 与
     * `PlayerController.refId`**（域里那句注释写的是"都用 agentConfigId，
     * 两者一致才能让身份反查成立"）。而调用方传的是**配置 id**（形如 `room-p_3`），
     * 于是领域里存的 refId 是 `room-p_3`，而提交行动时
     * `SubmitActionApplicationService.seatOf` 拿 `actorId`（`p_3`）去比对，
     * 永远匹配不上——每个 AI 回合都被拒为 `SEAT_NOT_YOURS`。
     *
     * <p>正确的约束是反过来的：**agentId 必须等于规范 playerId `p_{seat}`**，
     * 而不是"playerId 用配置 id"。`AgentRuntime.createAgent` 与
     * `PlayerController`（AI 时 refId = agentId）两处都要求这一点。
     *
     * <p>在**开局边界**归一化而不是去改 `Room` 的语义：房间阶段那个标识只用于
     * 座位展示与"同一 AI 是否已就座"的比较，改成 `p_{seat}` 会让"同一个配置的 AI
     * 占多个座位"变得无法区分；而对局阶段必须有唯一的规范标识。
     * 两者是不同的关注点，各自归一化是恰当的。
     */
    private static List<SeatAssignment> seatAssignments(Room room) {
        List<SeatAssignment> seats = new ArrayList<>(Room.SEAT_COUNT);
        for (RoomSeat seat : room.seats()) {
            if (!seat.occupied()) {
                continue;
            }
            seats.add(new SeatAssignment(
                    seat.seatNo(), canonicalController(seat, seat.controller()), seat.ready()));
        }
        return seats;
    }

    /**
     * 该座位在**对局阶段**的规范控制器。
     *
     * <p>真人：`refId` 是 userId，保持不动（它就是提交时的 actorId）。
     *
     * <p>**AI 座位一律归一化为 `p_{seat}`，包括"房主离开后被 AI 接管"的座位。**
     * 这是第 38 轮抓到的缺陷：房主离开房间后，那个座位由 `autoFillAi` 接管，
     * 但控制器里**残留的是房主的 userId**（房间阶段"谁坐在这里"的标识），
     * 而对局阶段驱动用 `p_1` 派发——于是座位 1 的提交永远被拒为 `SEAT_NOT_YOURS`。
     *
     * <p>症状很有欺骗性：其他 8 个座位都正常提交（实测 5 次成功提交），
     * 只有座位 1 一直不动，于是 `DAY_DISCUSS` 的回合游标停在 1 号座、
     * `turnIndex` 不前进、界面表现为"游戏卡住"。**一个座位的失败会让整局停住。**
     *
     * <p>BOT 与其它类型原样保留：它们的 `refId` 语义（strategyKey）尚未固化。
     */
    private static com.wolfmind.domain.game.model.PlayerController canonicalController(
            RoomSeat seat, com.wolfmind.domain.game.model.PlayerController controller) {
        if (controller == null
                || controller.type() != com.wolfmind.domain.game.model.ControllerType.AI) {
            return controller;
        }
        return new com.wolfmind.domain.game.model.PlayerController(
                com.wolfmind.domain.game.model.ControllerType.AI,
                com.wolfmind.domain.game.model.PlayerId.forSeat(seat.seatNo()).value());
    }

    /**
     * 房间配置 → 角色配置。
     *
     * <p>当前只支持经典九人（3 狼 3 民 1 预 1 女 1 猎）。房间的 {@code config} 里若带了
     * 自定义配置，此处应解析；在 api-spec §4 明确配置的字段结构之前，用标准配置而不是
     * 猜一个解析规则——猜错会让"房间配置"这个字段永远无法可信。
     */
    private static RoleComposition roleComposition(Room room) {
        Map<String, Object> config = room.config();
        if (config != null && !config.isEmpty() && config.containsKey("roleComposition")) {
            // 显式声明了配置但当前尚不支持解析：明确拒绝，而不是静默用默认值开局。
            throw com.wolfmind.domain.game.model.GameException.Factory.compositionInvalid(
                    "房间配置里的 roleComposition 暂不支持自定义解析（当前仅经典九人）");
        }
        return RoleComposition.STANDARD_NINE;
    }

    /** 已开局时直接返回现状，不再发牌。 */
    private StartResult existing(Room room) {
        String gameId = room.gameId();
        List<SeatAssignment> seats = seatAssignments(room);
        int agents = (int) seats.stream()
                .filter(seat -> seat.controller().type() == ControllerType.AI)
                .count();
        return new StartResult(
                gameId,
                room.version(),
                "NIGHT_WOLF",
                agents,
                RoomView.of(room));
    }
}
