package com.wolfmind.application.fake;

import com.wolfmind.domain.game.event.DomainEvent;
import com.wolfmind.domain.game.event.EventEnvelope;
import com.wolfmind.domain.game.model.ActionContext;
import com.wolfmind.domain.game.model.Game;
import com.wolfmind.domain.game.model.GameId;
import com.wolfmind.domain.game.model.GamePhase;
import com.wolfmind.domain.game.model.GameRules;
import com.wolfmind.domain.game.model.GameSnapshot;
import com.wolfmind.domain.game.model.PlayerSnapshot;
import com.wolfmind.domain.game.model.Role;
import com.wolfmind.domain.game.model.RoleComposition;
import com.wolfmind.domain.game.model.SeatAssignment;
import com.wolfmind.domain.game.model.SeatNo;
import com.wolfmind.domain.game.model.StartGameCommand;

import java.lang.reflect.RecordComponent;
import java.util.ArrayList;
import java.util.List;

/**
 * 测试夹具：用领域自己的公开入口把对局推进到一个已知状态。
 *
 * <p>固定种子让角色分配在任何 JVM 上可复现（{@code DeterministicShuffle} 不依赖平台随机源），
 * 因此测试可以从聚合状态反查"哪个座位是狼"这类事实，而不必假设座位顺序。
 */
public final class GameFixtures {

    /** 固定种子：让同一局的角色分配在测试间稳定。 */
    public static final long SEED = 42L;

    private GameFixtures() {}

    /** 九人标准局命令：座位 1..9 全部真人、全部就绪（INV-R2）。 */
    public static StartGameCommand standardCommand(String gameId, String roomId) {
        List<SeatAssignment> seats = new ArrayList<>();
        for (int seatNo = 1; seatNo <= 9; seatNo++) {
            seats.add(SeatAssignment.human(seatNo, userIdOf(seatNo)));
        }
        return new StartGameCommand(
                GameId.of(gameId), roomId, seats, RoleComposition.STANDARD_NINE, GameRules.DEFAULT);
    }

    /** 玩家 {@code p_{seat}} 的控制者身份，与 {@link #standardCommand} 的分配一致。 */
    public static String userIdOf(int seatNo) {
        return "u_" + seatNo;
    }

    /** 已开局的对局：版本 11（{@code GAME_STARTED} + 9×{@code ROLE_ASSIGNED} + {@code PHASE_CHANGED}）。 */
    public static Game startedGame(String gameId, String roomId, long seed) {
        Game game = Game.create(GameId.of(gameId));
        ActionContext ctx = ActionContext.of("evt-start", 1_000L, "u_owner", "cmd-start", "cor-start", 0)
                .withRandomSeed(seed);
        game.start(standardCommand(gameId, roomId), ctx);
        return game;
    }

    /**
     * 把开局事件按版本 1..n 写入事件存储，使"事件流"与"聚合版本"一致。
     *
     * <p>测试夹具绕过了应用服务，因此必须自己补上这一步；否则后续提交会因存储侧版本不符而被拒绝。
     */
    public static void seedStartEvents(FakeEventStore store, Game startedGame) {
        String gameId = startedGame.gameId().value();
        List<DomainEvent> events = startedGame.pendingEvents();
        List<EventEnvelope> envelopes = new ArrayList<>(events.size());
        int version = 1;
        for (DomainEvent event : events) {
            envelopes.add(EventEnvelope.of(event, gameId, version));
            version++;
        }
        store.append(gameId, 0, envelopes);
    }

    /** 常用组合：注册已开局聚合并把开局事件写入存储。 */
    public static Game registeredStartedGame(FakeEventStore store, FakeGameRepository repository, String gameId) {
        Game game = startedGame(gameId, "room-1", SEED);
        seedStartEvents(store, game);
        repository.register(game);
        return game;
    }

    /** 某个角色的座位；找不到时直接让测试失败，而不是返回 null 让后续断言变得含混。 */
    public static PlayerSnapshot playerWithRole(Game game, Role role) {
        return game.snapshot().players().stream()
                .filter(player -> player.role() == role)
                .findFirst()
                .orElseThrow(() -> new AssertionError("对局中没有角色 " + role));
    }

    /**
     * 把已开局的对局"恢复"到另一个阶段，用于构造正常写路径不可达的状态。
     *
     * <p>结算阶段（{@code NIGHT_SETTLE} / {@code DAY_ANNOUNCE} / {@code VOTE_SETTLE}）在一次命令内
     * 就会被领域推进完，因此提交路径上不会停在它们上面；但恢复路径（{@code Game.restore}）可能拿到
     * 这种状态，看护推进（{@code Game.advance}）正是为它准备的入口。这里用快照的公开构造入口
     * （反射按记录组件复制，字段增删不影响本方法）造出该状态，不修改任何生产代码。
     */
    public static Game restoredAtPhase(Game game, GamePhase phase) {
        GameSnapshot base = game.snapshot();
        return Game.restore(replaceComponent(base, "phase", phase));
    }

    private static GameSnapshot replaceComponent(GameSnapshot base, String componentName, Object value) {
        try {
            RecordComponent[] components = GameSnapshot.class.getRecordComponents();
            Class<?>[] types = new Class<?>[components.length];
            Object[] arguments = new Object[components.length];
            for (int index = 0; index < components.length; index++) {
                types[index] = components[index].getType();
                arguments[index] = components[index].getName().equals(componentName)
                        ? value
                        : components[index].getAccessor().invoke(base);
            }
            return (GameSnapshot) GameSnapshot.class.getDeclaredConstructor(types).newInstance(arguments);
        } catch (ReflectiveOperationException ex) {
            throw new AssertionError("构造成 " + componentName + "=" + value + " 的快照失败", ex);
        }
    }
}
