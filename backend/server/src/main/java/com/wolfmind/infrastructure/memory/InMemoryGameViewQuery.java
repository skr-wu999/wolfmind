package com.wolfmind.infrastructure.memory;

import com.wolfmind.application.port.EventStorePort;
import com.wolfmind.application.port.GameViewQueryPort;
import com.wolfmind.domain.game.event.EventEnvelope;
import com.wolfmind.domain.game.model.GameException;
import com.wolfmind.domain.game.model.GameSnapshot;
import com.wolfmind.domain.game.model.GameStatus;
import com.wolfmind.domain.game.model.PlayerId;
import com.wolfmind.domain.game.model.PlayerSnapshot;
import com.wolfmind.domain.game.model.ViewerContext;
import com.wolfmind.domain.game.view.GameViewProjector;
import com.wolfmind.domain.game.view.GameViewSnapshot;
import com.wolfmind.domain.game.view.VisibilityFilter;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * 内存视图查询：只读用例的内存适配器。
 *
 * <p>它刻意"什么都不决定"：
 * <ul>
 *   <li>脱敏交给 {@link GameViewProjector#project}（领域唯一实现，engine 侧）；</li>
 *   <li>回放可见性交给 {@link VisibilityFilter}（同一份判定，WebSocket 与 AI 工具也用它）；</li>
 *   <li>本类只负责"从哪个存储读、读哪个区间、怎么分页"。</li>
 * </ul>
 *
 * <p>早期版本曾在这里自己写一份可见性判断，那是 AP-6；现在那类代码已删除，
 * 任何"再写一遍谁能看什么"的实现都应该被评审打回。
 */
public final class InMemoryGameViewQuery implements GameViewQueryPort {

    /** api-spec.md §6 规定单次回放上限 500 条。 */
    public static final int REPLAY_MAX_EVENTS = 500;

    private final EventStorePort eventStore;
    private final InMemoryGameRepository repository;
    private final com.wolfmind.application.port.ReplayArchivePort archive;

    public InMemoryGameViewQuery(EventStorePort eventStore, InMemoryGameRepository repository) {
        this(eventStore, repository, null);
    }

    /**
     * @param archive SQLite 归档（{@code wolfmind.storage=redis} 时注入）；{@code null} 表示
     *                没有归档可读——此时过期对局的回放会如实报 404，而不是返回一份空历史
     */
    public InMemoryGameViewQuery(
            EventStorePort eventStore,
            InMemoryGameRepository repository,
            com.wolfmind.application.port.ReplayArchivePort archive) {
        if (eventStore == null || repository == null) {
            throw new IllegalArgumentException("eventStore 与 repository 都不能为空");
        }
        this.eventStore = eventStore;
        this.repository = repository;
        this.archive = archive;
    }

    @Override
    public GameViewSnapshot view(String gameId, ViewerContext viewer) {
        GameSnapshot state = stateOf(gameId);
        // 赛后公开是"读取者身份"的一部分：先按状态补上 reveal，再投影，
        // 否则赛后参与者仍然看不到已公开的角色（event-design.md §4 矩阵最后一行）。
        ViewerContext effective = VisibilityFilter.withReveal(
                viewer == null ? ViewerContext.anonymous() : viewer, state.status());
        return GameViewProjector.project(state, effective);
    }

    /**
     * 指定版本的视图。
     *
     * <p>用"从版本 1 重放事件到 atVersion"而不是"取最近快照再回退"：快照是缓存（INV-15），
     * 只保留最近几个版本，而回放页要的可能是任意一步。一局只有两百来条事件，
     * 确定性重放比"猜哪个快照离目标最近"便宜也更可靠。
     *
     * <p>{@code atVersion} 大于等于流尾时等价于读取当前视图——这是回放页播放到末尾的正常路径，
     * 不该报错。
     */
    @Override
    public GameViewSnapshot viewAt(String gameId, ViewerContext viewer, int atVersion) {
        if (atVersion < 1) {
            throw GameException.Factory.validation("atVersion 必须大于等于 1，实际为 " + atVersion);
        }
        GameSnapshot state = repository.loadAt(gameId, atVersion).game().snapshot();
        ViewerContext effective = VisibilityFilter.withReveal(
                viewer == null ? ViewerContext.anonymous() : viewer, state.status());
        return GameViewProjector.project(state, effective);
    }

    @Override
    public List<EventEnvelope> replay(
            String gameId, ViewerContext viewer, int fromVersion, int toVersion, int maxEvents) {
        return replayPage(gameId, viewer, fromVersion, toVersion, maxEvents).events();
    }

    /**
     * 回放读取：事件流在就读流，流已过期则读归档。
     *
     * <p>"流不存在"与"对局不存在"必须分开：前者是 TTL 到期，后者是客户端拼错了 id。
     * 这里只把前者交给归档，归档里也没有才算 404。
     */
    @Override
    public ReplayPage replayPage(
            String gameId, ViewerContext viewer, int fromVersion, int toVersion, int maxEvents) {
        int limit = maxEvents <= 0 ? REPLAY_MAX_EVENTS : Math.min(maxEvents, REPLAY_MAX_EVENTS);
        if (toVersion < fromVersion) {
            return new ReplayPage(List.of(), currentVersion(gameId), false);
        }
        if (!eventStore.exists(gameId)) {
            return replayFromArchive(gameId, viewer, fromVersion, toVersion, limit);
        }
        ViewerContext effective = VisibilityFilter.withReveal(
                viewer == null ? ViewerContext.anonymous() : viewer, stateOf(gameId).status());
        List<EventEnvelope> visible = VisibilityFilter.filter(
                eventStore.read(gameId, Math.max(1, fromVersion), toVersion), effective);
        return new ReplayPage(
                visible.size() <= limit ? visible : List.copyOf(visible.subList(0, limit)),
                currentVersion(gameId),
                false);
    }

    private ReplayPage replayFromArchive(
            String gameId, ViewerContext viewer, int fromVersion, int toVersion, int limit) {
        if (archive == null) {
            throw GameException.Factory.notRunning("不存在");
        }
        var archived = archive.game(gameId).orElseThrow(() -> GameException.Factory.notRunning("不存在"));
        // 归档只收终局对局，因此这里按赛后公开处理；判定依据是归档里的状态，不是"归档"这件事本身。
        ViewerContext effective = VisibilityFilter.withReveal(
                viewer == null ? ViewerContext.anonymous() : viewer,
                archived.terminal() ? GameStatus.FINISHED : GameStatus.RUNNING);
        List<EventEnvelope> visible = VisibilityFilter.filter(
                archive.events(gameId, Math.max(1, fromVersion), toVersion, limit), effective);
        return new ReplayPage(
                visible.size() <= limit ? visible : List.copyOf(visible.subList(0, limit)),
                archived.version(),
                true);
    }

    /**
     * 流尾版本。
     *
     * <p>直接问仓储而不是读事件流：仓储已经维护了"当前版本"，再读一次流既慢又可能
     * 与仓储的视图不一致（两者之间有并发窗口）。
     */
    @Override
    public int currentVersion(String gameId) {
        return repository.currentVersion(gameId);
    }

    @Override
    public List<Map<String, Object>> listGames(String status, String roomId, int page, int pageSize) {
        boolean filtered = status != null && !status.isBlank();
        List<Map<String, Object>> items = new ArrayList<>();
        for (String gameId : repository.knownGameIds()) {
            // 对局驱动每 200ms 按 RUNNING 列一次：已知终态的对局不会再变，跳过它们省掉逐局访问存储。
            if (filtered && !GameStatus.FINISHED.name().equals(status)
                    && !GameStatus.ABORTED.name().equals(status) && repository.knownTerminal(gameId)) {
                continue;
            }
            Optional<InMemoryGameRepository.StatusAt> current = repository.statusAt(gameId);
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("gameId", gameId);
            item.put("version", current.map(InMemoryGameRepository.StatusAt::version).orElse(0));
            item.put("status", current.map(at -> at.status().name()).orElse(null));
            items.add(item);
        }
        if (filtered) {
            items.removeIf(item -> !status.equals(item.get("status")));
        }
        int normalizedPage = Math.max(1, page);
        int normalizedSize = pageSize <= 0 ? 20 : Math.min(pageSize, 100);
        int from = Math.min((normalizedPage - 1) * normalizedSize, items.size());
        int to = Math.min(from + normalizedSize, items.size());
        return List.copyOf(items.subList(from, to));
    }

    /**
     * 为只读查询构造读取者上下文。
     *
     * <p>{@code wolfPlayerIds} 只在读取者确实控制狼人座位时才填充：这是"AI 的可见性与它扮演的
     * 玩家完全一致"的落地方式（event-design.md §4 硬规则 1）。观战者不填；进行中观战
     * 仅读取公开事件，赛后由服务端状态触发完整披露。
     *
     * <p><b>Phase 4 接入认证时的硬约束</b>：{@code serverVerifiedSpectator} 与
     * {@code myPlayerIds} 都只能由服务端从会话与参与者关系反查得出，<b>绝不允许</b>直接来自
     * 请求参数或客户端声明。"我是观战者"如果由客户端说了算，等于把 WOLF/PRIVATE/JUDGE 事件
     * 的读取权公开（event-design.md §4 硬规则 1、AP-6）。
     *
     * <p>本方法在参数自相矛盾时直接拒绝，而不是"以某个参数为准"：
     * 参与者不能同时作为本局观战者（api-spec.md §10、websocket-protocol.md §6.4）。
     *
     * @param serverVerifiedSpectator 服务端确认"该用户不是本局参与者"；必须来自可信来源
     * @param myPlayerIds             服务端确认的该用户在本局控制的座位；观战者必须为空集
     */
    public ViewerContext contextFor(
            String gameId, String viewerUserId, Set<PlayerId> myPlayerIds, boolean serverVerifiedSpectator) {
        Set<PlayerId> mine = myPlayerIds == null ? Set.of() : Set.copyOf(myPlayerIds);
        if (serverVerifiedSpectator && !mine.isEmpty()) {
            // 两者同时成立意味着调用方把不可信的输入当成了身份依据。这里失败是为了让
            // "观战者身份可以提权"这类缺陷在第一次请求就暴露，而不是静默给出上帝视角。
            throw GameException.Factory.validation(
                    "参与者不能同时作为本局观战者：spectator=true 且 participantPlayerIds 非空（api-spec.md §10）");
        }
        GameSnapshot state = stateOf(gameId);
        if (serverVerifiedSpectator) {
            return ViewerContext.spectator(viewerUserId);
        }
        Set<PlayerId> wolves = new LinkedHashSet<>();
        if (!mine.isEmpty()) {
            state.players().stream()
                    .filter(PlayerSnapshot::isWolf)
                    .map(PlayerSnapshot::playerId)
                    .filter(mine::contains)
                    .forEach(wolves::add);
        }
        return VisibilityFilter.withReveal(
                ViewerContext.participant(viewerUserId, mine, wolves, false), state.status());
    }

    /**
     * 按账号反查座位并构造读取者上下文。
     *
     * <p>反查依据是 {@code PlayerSnapshot.controller()} 的 {@code refId} 等于账号 id：
     * 真人座位的控制者就是账号，AI 座位的 refId 是 Agent 配置 id（永远不等于账号 id），
     * 因此 AI 不会因为 id 撞上而被当成某个人的座位。
     *
     * <p>匿名（{@code viewerUserId} 为 null 或空）返回匿名上下文：只读接口允许匿名访问，
     * 而匿名只应看到 PUBLIC。
     *
     * <p>账号不是本局参与者时返回**只读参与者**上下文（空座位集合）而不是观战者：
     * 观战者是一个更强的主张（「此人已被服务端确认不是参与者」），而"不是参与者"与
     * "确认可以观战"是两件事——后者还需要房间/订阅关系的判定，尚未接入。
     * 返回空集合的结果是只看到 PUBLIC，这是安全的一侧。
     */
    @Override
    public ViewerContext viewerContextFor(String gameId, String viewerUserId) {
        if (viewerUserId == null || viewerUserId.isBlank()) {
            return ViewerContext.anonymous();
        }
        Set<PlayerId> mine = new LinkedHashSet<>();
        try {
            GameSnapshot state = stateOf(gameId);
            for (PlayerSnapshot player : state.players()) {
                com.wolfmind.domain.game.model.PlayerController controller = player.controller();
                if (controller != null
                        && controller.type() == com.wolfmind.domain.game.model.ControllerType.HUMAN
                        && viewerUserId.equals(controller.refId())) {
                    mine.add(player.playerId());
                }
            }
            return contextFor(gameId, viewerUserId, mine, mine.isEmpty());
        } catch (RuntimeException error) {
            // 事件流已过期时改从归档反查座位：归档里的 GAME_STARTED.seats 是权威的座位表。
            // 没有这一步，"一周之后参与者打开自己的回放"会退回匿名，看不到自己的私有事件。
            ViewerContext fromArchive = archivedContextFor(gameId, viewerUserId);
            if (fromArchive != null) {
                return fromArchive;
            }
            // 对局不存在或状态不可读：退回匿名（只看到 PUBLIC），而不是抛异常把
            // 一次"订阅了一个不存在的对局"变成连接失败。
            return ViewerContext.anonymous();
        }
    }

    /**
     * 由归档座位表构造读取者上下文。
     *
     * <p>归档只收终局对局，因此参赛账号按"赛后公开"处理——但仍被标为参与者而不是观战者，
     * 这样"参与者与观战者看到的东西不同"这条规则在归档数据上依然成立。
     *
     * @return {@code null} 表示归档里也没有这一局
     */
    private ViewerContext archivedContextFor(String gameId, String viewerUserId) {
        if (archive == null) {
            return null;
        }
        var archived = archive.game(gameId).orElse(null);
        if (archived == null) {
            return null;
        }
        Set<PlayerId> mine = new LinkedHashSet<>();
        for (var seat : archived.seats()) {
            if ("HUMAN".equals(seat.controllerType()) && viewerUserId.equals(seat.refId())) {
                mine.add(PlayerId.of(seat.playerId()));
            }
        }
        if (mine.isEmpty()) {
            return ViewerContext.spectator(viewerUserId);
        }
        return ViewerContext.participant(viewerUserId, mine, Set.of(), archived.terminal());
    }
    private GameSnapshot stateOf(String gameId) {
        return repository.load(gameId).game().snapshot();
    }
}
