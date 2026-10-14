package com.wolfmind.interfaces.ws;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import com.wolfmind.application.port.GameViewQueryPort;
import com.wolfmind.application.room.GetRoomQueryService;
import com.wolfmind.application.room.RoomRepositoryPort;
import com.wolfmind.application.user.AuthApplicationService;
import com.wolfmind.domain.game.event.EventEnvelope;
import com.wolfmind.domain.game.model.ViewerContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * WebSocket 网关：连接注册表 + 帧分派 + 事件扇出。
 *
 * <p>它把三个来源接在一起：**传输**（{@link Sender}）、**协议语义**（{@link SubscriptionState}）、
 * **授权数据**（{@link GameViewQueryPort}）。三者分离是有意的：
 * <ul>
 *   <li>传输只有一个方法要测"帧写出去了吗"；</li>
 *   <li>协议语义已有 23 条纯单元测试；</li>
 *   <li>可见性过滤只有领域侧一个实现（event-design.md §4 硬规则 2），这里只是调用它。</li>
 * </ul>
 *
 * <p><b>网关自己不做可见性判断</b>。它按订阅者的身份调
 * {@link GameViewQueryPort#replay} 拿到**已过滤**的事件再喂给状态机。
 * 在这里写任何 {@code if (visibility == WOLF)} 都会造出第二个过滤实现——
 * 那正是 AP-6 明令禁止的，而两个实现漂移的后果是把狼人身份发给村民。
 *
 * <p>本类不依赖任何 {@code jakarta.websocket} 类型（{@link Sender} 是自定义接口），
 * 因此可以用假 Sender 做完整测试，不必建真实连接。
 */
public final class WebSocketGateway implements com.wolfmind.application.room.RoomPresencePort {

    private static final Logger log = LoggerFactory.getLogger(WebSocketGateway.class);

    /** 传输出口：把一帧写到某个连接。实现方负责序列化与并发写保护。 */
    public interface Sender {

        void send(String connectionId, Map<String, Object> wire);

        /** 服务端主动关闭（协议异常或超时）；{@code code} 见 §3.6。 */
        void close(String connectionId, int code, String reason);
    }

    /**
     * 网关的静态持有。
     *
     * <p><b>为什么需要它</b>：{@code @ServerEndpoint} 的实例由容器反射创建，不是 Spring Bean，
     * 拿不到构造器注入。传输端点（在 {@code infrastructure.ws}）因此经这个持有拿到网关。
     *
     * <p><b>为什么持有者放在网关这一侧</b>：DEP-4 要求 WebSocket 类型只出现在
     * {@code interfaces.ws}（协议）与 {@code infrastructure.ws}（传输管道）。
     * 若把持有者放在端点上，协议层就要反向引用基础设施层，方向是错的。
     * 放在网关上，依赖方向是 infrastructure → interfaces，符合 DEP-1。
     *
     * <p>网关的状态只有连接表、各连接的 {@code SubscriptionState} 与流式预览标识集合，
     * 都是并发容器，因此这个静态引用不引入额外的共享可变状态。
     */
    private static volatile WebSocketGateway instance;

    /** 装配期注入（由 {@code infrastructure.ws} 的配置类调用）。 */
    public static void holder(WebSocketGateway gateway) {
        instance = gateway;
    }

    /**
     * 取已装配的网关；未装配时返回 {@code null}。
     *
     * <p>返回 null 而不是抛异常：未装配时端点应表现为"收不到帧"的静默降级，
     * 而不是让每个连接都崩在 NPE 上。
     */
    public static WebSocketGateway holder() {
        return instance;
    }

    private final Map<String, SubscriptionState> connections = new ConcurrentHashMap<>();
    private final Map<String, Sender> senders = new ConcurrentHashMap<>();
    private final AuthApplicationService auth;
    private final GameViewQueryPort gameViews;
    private final GetRoomQueryService rooms;
    private final ObjectMapper mapper;

    public WebSocketGateway(AuthApplicationService auth, GameViewQueryPort gameViews, ObjectMapper mapper) {
        this(auth, gameViews, null, mapper);
    }

    /** @param rooms 等待房快照来源；为 {@code null} 时所有房间按不存在处理 */
    public WebSocketGateway(
            AuthApplicationService auth, GameViewQueryPort gameViews, GetRoomQueryService rooms, ObjectMapper mapper) {
        this.auth = auth;
        this.gameViews = gameViews;
        this.rooms = rooms;
        this.mapper = mapper;
    }

    /** 连接建立：登记并等 AUTH。 */
    public void onOpen(String connectionId, Sender sender) {
        senders.put(connectionId, sender);
        SubscriptionState state = new SubscriptionState(connectionId);
        connections.put(connectionId, state);
        dispatch(connectionId, state.onConnected(System.currentTimeMillis()));
    }

    /** 连接关闭：摘掉登记，避免向死连接扇出。 */
    public void onClose(String connectionId) {
        connections.remove(connectionId);
        senders.remove(connectionId);
    }

    @Override
    public boolean present(String roomId, String gameId, String userId) {
        for (SubscriptionState state : connections.values()) {
            synchronized (state) {
                if (state.phase() != SubscriptionState.Phase.CLOSED && userId.equals(state.userId())
                        && ((gameId != null && state.subscribedTo(gameId)) || state.subscribedToRoom(roomId))) return true;
            }
        }
        return false;
    }

    int connectionCount() {
        return connections.size();
    }

    /**
     * 处理一条客户端文本帧。格式错误回 ERROR 而不断开连接。
     *
     * <p>限速检查在**最前面**，早于解析与分派：目的正是防"用无效帧刷服务端"，
     * 因此不能只对合法帧生效——否则一个只会发乱码的客户端可以无限刷。
     */
    public void onText(String connectionId, String text) {
        SubscriptionState state = connections.get(connectionId);
        if (state == null) {
            // 连接已摘除（可能已关闭）：丢弃，不重建状态。
            return;
        }

        long now = System.currentTimeMillis();
        ServerFrame limited = state.checkRateLimit(now);
        if (state.rateLimitExceeded()) {
            // 持续超限：关 4429。文档要求"降低帧发送频率后重连"，
            // 而客户端据此把退避起点提到 15 秒（§3.6）。
            closeWith(connectionId, ServerFrame.CloseCode.RATE_LIMITED, "客户端帧频率持续超限");
            return;
        }
        if (limited != null) {
            send(connectionId, limited);
            return;
        }

        ClientFrameParser.Result parsed;
        try {
            parsed = ClientFrameParser.parse(text);
        } catch (RuntimeException error) {
            // 解析器自身出错（不应发生）：回错误帧而不是让异常逃逸到传输层。
            log.warn("帧解析异常 connectionId={}", connectionId, error);
            send(connectionId, "ERROR", Map.of(
                    "code", "INTERNAL_ERROR", "message", "帧处理失败", "details", List.of()), 0L);
            return;
        }
        if (parsed instanceof ClientFrameParser.Result.Malformed malformed) {
            send(connectionId, "ERROR", Map.of(
                    "code", malformed.code(), "message", malformed.message(), "details", List.of()), 0L);
            return;
        }
        try {
            synchronized (state) {
                dispatch(connectionId, handle(state, ((ClientFrameParser.Result.Ok) parsed).frame()));
            }
        } catch (RuntimeException error) {
            log.warn("同步帧处理失败 connectionId={}", connectionId, error);
            send(connectionId, "ERROR", Map.of("code", "INTERNAL_ERROR", "message", "同步失败，请重连", "details", List.of()), 0L);
        }
    }

    /** 按帧类型分派。每个分支只负责"取参数 → 调状态机"。 */
    private List<ServerFrame> handle(SubscriptionState state, ClientFrame frame) {
        long now = System.currentTimeMillis();
        return switch (frame) {
            case ClientFrame.Auth authFrame ->
                    state.onAuth(authFrame.token(), sessionLookup(), now);
            case ClientFrame.Subscribe subscribe ->
                    subscribe(state, subscribe.gameId(), subscribe.lastSeq(), false, now);
            case ClientFrame.Resume resume ->
                    subscribe(state, resume.gameId(), resume.lastSeq(), true, now);
            case ClientFrame.Unsubscribe unsubscribe ->
                    state.onUnsubscribe(unsubscribe.gameId(), now);
            case ClientFrame.RoomSubscribe roomSubscribe ->
                    state.onRoomSubscribe(roomSubscribe.roomId(), this::roomSnapshot, now);
            case ClientFrame.RoomUnsubscribe roomUnsubscribe ->
                    state.onRoomUnsubscribe(roomUnsubscribe.roomId(), now);
            case ClientFrame.Ping ping -> state.onPing(now);
            case ClientFrame.Ack ack -> state.onAck(ack.seq(), now);
        };
    }

    /**
     * SUBSCRIBE / RESUME 的公共处理。
     *
     * <p>{@code availableTo} 必须是**真实的流尾**（{@link GameViewQueryPort#currentVersion}），
     * 不能用补发事件列表的长度代替：{@code replay} 有 500 条上限，用长度会让一个已有 600 版
     * 的对局把 {@code SUBSCRIBED.to} 报成 500，客户端据此以为自己同步完了。
     */
    private List<ServerFrame> subscribe(
            SubscriptionState state, String gameId, long lastSeq, boolean resume, long now) {
        if (!state.authenticated()) {
            return state.onSubscribe(gameId, lastSeq, resume, List.of(), 0, now);
        }
        state.setScope(gameId, viewerOf(state, gameId).isParticipant());
        int availableTo;
        try {
            availableTo = gameViews.currentVersion(gameId);
        } catch (RuntimeException error) {
            // 对局不存在：按"没有事件"处理，让客户端收到 SUBSCRIBED 后由后续帧暴露问题，
            // 而不是在这里断开连接——它可能只是订阅了一个刚被归档的对局。
            log.debug("取流尾版本失败 gameId={}", gameId, error);
            availableTo = 0;
        }
        boolean snapshotRequired = availableTo > 0 && (lastSeq == 0 || lastSeq > availableTo);
        if (snapshotRequired) {
            var view = gameViews.view(gameId, viewerOf(state, gameId));
            Map<String, Object> payload = mapper.convertValue(view, new TypeReference<Map<String, Object>>() {});
            payload.put("snapshotVersion", 0);
            payload.put("replayedTo", view.version());
            payload.put("source", "FULL_REPLAY");
            List<ServerFrame> frames = new ArrayList<>(state.onSubscribe(
                    gameId, lastSeq, resume, List.of(), view.version(), now));
            frames.add(state.snapshotSynced(gameId, view.version(), payload, now));
            return frames;
        }
        List<EventEnvelope> visible = visibleEvents(state, gameId, lastSeq, availableTo);
        return state.onSubscribe(gameId, lastSeq, resume, visible, availableTo, now);
    }

    /**
     * 取该订阅者可见的事件。
     *
     * <p>用 {@code replay} 而不是自己读事件流：它内部调领域的 {@code VisibilityFilter}，
     * 因此权限判定只有一份实现。返回空列表表示"看不到任何事件"——这是正确的降级
     * （未登录或不是参与者时只应看到 PUBLIC，而这里连 PUBLIC 都可能被过滤掉，
     * 由 filter 决定）。
     */
    private List<EventEnvelope> visibleEvents(SubscriptionState state, String gameId, long lastSeq, int toVersion) {
        ViewerContext viewer = viewerOf(state, gameId);
        try {
            List<EventEnvelope> visible = new ArrayList<>();
            for (long from = Math.max(1L, lastSeq + 1); from <= toVersion; from += 500) {
                visible.addAll(gameViews.replay(gameId, viewer, (int) from,
                        (int) Math.min(from + 499, toVersion), 500));
            }
            return visible;
        } catch (RuntimeException error) {
            log.debug("订阅取事件失败 gameId={} viewer={}", gameId, viewer.viewerUserId(), error);
            throw error;
        }
    }

    /**
     * 订阅者在这次连接中的身份；未认证时是匿名（只能看到 PUBLIC）。
     *
     * <p><b>座位集合由服务端反查</b>（{@link GameViewQueryPort#viewerContextFor}），
     * 绝不由客户端声明：座位集合决定了能不能看到自己的角色与狼队，让客户端传等于把
     * "我是谁"的声明权交出去——一个村民只要自称某个狼人座位就能拿到狼队信息。
     *
     * <p>早期版本这里返回空座位集合，后果是**每个订阅者都只看得到 PUBLIC 事件**，
     * 连自己的角色都看不到。当时的注释把它写成"安全的一侧"，但那是把"接错了"说成了
     * "保守"——安全的一侧应该是"反查失败时降级为匿名"，而不是"永远降级"。
     */
    private ViewerContext viewerOf(SubscriptionState state, String gameId) {
        if (!state.authenticated()) {
            return ViewerContext.anonymous();
        }
        try {
            return gameViews.viewerContextFor(gameId, state.userId());
        } catch (RuntimeException error) {
            // 反查失败：降级为匿名（只看 PUBLIC）。失败方向必须偏安全一侧。
            log.debug("反查订阅者座位失败 gameId={} userId={}", gameId, state.userId(), error);
            return ViewerContext.anonymous();
        }
    }

    /** 状态机需要的会话校验；把认证服务的返回类型适配过来。 */
    private SubscriptionState.SessionLookup sessionLookup() {
        return token -> auth.verifySession(token).flatMap(verified -> {
            AuthApplicationService.CurrentUser current;
            try {
                current = auth.currentUser(token);
            } catch (RuntimeException error) {
                return Optional.<SubscriptionState.SessionLookup.Verified>empty();
            }
            return Optional.of(new SubscriptionState.SessionLookup.Verified(
                    verified.userId(), current.username(), verified.sessionId()));
        });
    }

    // ------------------------------------------------------------------
    // 扇出
    // ------------------------------------------------------------------

    /**
     * 一批已提交事件的扇出入口。
     *
     * <p>房间事件（流名以 {@code room:} 开头）不逐条推送：每个房间按批次只读一次当前状态，
     * 以 {@code ROOM_SNAPSHOT} 发给订阅了该房间的连接；其余按对局事件处理。
     */
    public void onCommittedEvents(List<EventEnvelope> events) {
        java.util.Set<String> changedRooms = new java.util.LinkedHashSet<>();
        for (EventEnvelope event : events) {
            String stream = event.gameId();
            if (stream != null && stream.startsWith(RoomRepositoryPort.STREAM_PREFIX)) {
                changedRooms.add(stream.substring(RoomRepositoryPort.STREAM_PREFIX.length()));
            } else {
                onCommittedEvent(event);
            }
        }
        for (String roomId : changedRooms) {
            onRoomChanged(roomId);
        }
    }

    /** 向订阅了该房间的连接推送当前房间快照。 */
    public void onRoomChanged(String roomId) {
        if (connections.isEmpty()) {
            return;
        }
        Optional<SubscriptionState.RoomSnapshot> snapshot = roomSnapshot(roomId);
        if (snapshot.isEmpty()) {
            return;
        }
        long now = System.currentTimeMillis();
        for (SubscriptionState state : new ArrayList<>(connections.values())) {
            synchronized (state) {
                dispatch(state.connectionId(), state.onRoomChanged(roomId, snapshot.get(), now));
            }
        }
    }

    /**
     * 房间快照；房间不存在或读取失败时为空。
     *
     * <p>房间视图与 {@code GET /rooms/{roomId}} 同源同形，那条 REST 路由不要求登录，
     * 因此这里不另设读权限，只要求连接已认证（由状态机检查）。
     */
    private Optional<SubscriptionState.RoomSnapshot> roomSnapshot(String roomId) {
        if (rooms == null) {
            return Optional.empty();
        }
        try {
            GetRoomQueryService.VersionedRoomView loaded = rooms.versioned(roomId);
            Map<String, Object> room = mapper.convertValue(loaded.view(), new TypeReference<Map<String, Object>>() {});
            return Optional.of(new SubscriptionState.RoomSnapshot(loaded.version(), room));
        } catch (RuntimeException error) {
            log.debug("读取房间快照失败 roomId={}", roomId, error);
            return Optional.empty();
        }
    }

    /**
     * 向所有订阅了该对局的连接推送一个已提交事件。
     *
     * <p><b>每个连接独立过滤</b>：同一个事件对村民不可见、对狼人可见，因此不能算一次
     * "该发给谁"然后广播。这里是逐连接调 {@code replay} 判断该事件版本是否出现在
     * 该身份可见的集合里——代价是每个事件每连接一次查询，收益是**权限判定不可能走捷径**。
     * 性能优化（按身份类型分组、缓存可见集合）留到有实测压力时再做，
     * 因为优化的第一步必然是"放宽每个连接独立过滤"，而那正是最容易出错的一步。
     */
    public void onCommittedEvent(EventEnvelope event) {
        if (connections.isEmpty()) {
            return;
        }
        for (SubscriptionState state : new ArrayList<>(connections.values())) {
            synchronized (state) {
                if (!state.subscribedTo(event.gameId())) continue;
                Boolean visible = visibleTo(state, event);
                if (visible == null) continue;
                if (!visible) {
                    // 只推进服务端已确认的内部扫描位置，不给客户端发送私有事件。
                    state.onInvisibleEvent(event.gameId(), event.version());
                    continue;
                }
                dispatch(state.connectionId(), state.onEvent(event, System.currentTimeMillis()));
            }
        }
        if (PLAYER_SPOKE.equals(event.type()) && committedSpeechIds.putIfAbsent(
                event.gameId()+":"+event.eventId(), Boolean.TRUE) == null) emitSpeechDeltaFor(event);
    }

    /** 发言事件名；与后端 {@code EventType.PLAYER_SPOKE} 逐字一致。 */
    private static final String PLAYER_SPOKE = "PLAYER_SPOKE";
    private final Map<String,Boolean> committedSpeechIds = java.util.Collections.synchronizedMap(
            new java.util.LinkedHashMap<>() {
                @Override protected boolean removeEldestEntry(Map.Entry<String,Boolean> entry) { return size()>10_000; }
            });

    /** 已经走过模型流式预览的发言；提交后不再补发整段增量，避免同一 speechId 的 index 回到 0。 */
    private final java.util.Set<String> streamedSpeechIds = ConcurrentHashMap.newKeySet();

    /** 未被提交取代的预览标识上限（回合超时或改为跳过时，标识不会被 {@code PLAYER_SPOKE} 移除）。 */
    private static final int STREAMED_SPEECH_ID_LIMIT = 4096;

    /**
     * 模型流式发言的一段预览（来自 Agent 的 {@code decide} 调用，先于 {@code PLAYER_SPOKE}）。
     *
     * <p>发言只在 {@code DAY_DISCUSS} / {@code LAST_WORDS} 合法，两者都是公开阶段，因此按公开投递。
     */
    public void onSpeechPreview(
            String gameId, String playerId, int seatNo, String speechId,
            String delta, int index, boolean last) {
        if (streamedSpeechIds.size() >= STREAMED_SPEECH_ID_LIMIT) {
            streamedSpeechIds.clear();
        }
        streamedSpeechIds.add(speechId);
        onSpeechDelta(gameId, playerId, seatNo, speechId, delta, index, last, false);
    }

    /**
     * 已提交的发言事件顺带投递一条 {@code AGENT_SPEECH_DELTA}。
     *
     * <p>只用于没有流式预览的发言（规则基线 Agent、模型不支持流式时）：投递一条
     * {@code index=0, final=true} 的完整发言，不把已完成的文本拆段假装成流式（AGENTS.md §14）。
     * 走过 {@link #onSpeechPreview} 的发言跳过这一步。
     *
     * <p>可见性由事件的 {@code visibility} 决定，**不在这里自行判断**：
     * {@code WOLF} 是狼队密谈，其余按公开处理。真正的逐连接授权仍由
     * {@link #isWolf} 用领域反查出的狼队集合完成。
     */
    private void emitSpeechDeltaFor(EventEnvelope event) {
        if (!PLAYER_SPOKE.equals(event.type())) {
            return;
        }
        Map<String, Object> payload = event.payload();
        Object content = payload.get("content");
        if (!(content instanceof String speech) || speech.isBlank()) {
            return;
        }
        String playerId = String.valueOf(payload.getOrDefault("playerId", ""));
        int seatNo = payload.get("seatNo") instanceof Number number ? number.intValue() : 0;
        String speechId = String.valueOf(payload.getOrDefault("speechId", event.eventId()));
        if (streamedSpeechIds.remove(speechId)) {
            return;
        }
        boolean wolfOnly = event.visibility() == com.wolfmind.domain.game.model.Visibility.WOLF;

        onSpeechDelta(event.gameId(), playerId, seatNo, speechId,
                speech, 0, true, wolfOnly);
    }

    /**
     * 推送发言增量（{@code AGENT_SPEECH_DELTA}，websocket-protocol.md §7.2）。
     *
     * <p><b>它是展示更新，不是领域事件</b>（AGENTS.md §9）：不推进任何连接的 seq，
     * 也不进事件流。因此它可以在积压超限时被丢弃——丢一帧预览的代价远小于丢一帧状态。
     *
     * <p><b>可见性按订阅关系而非事件版本判定</b>：发言有 {@code scope}（公开 / 狼队密谈）。
     * 狼队密谈只应发给狼；这里用 {@code wolfOnly} 表达，由调用方按领域规则算好——
     * 网关不重复实现可见性判断（event-design.md §4 硬规则 2 要求只有一份实现）。
     * {@code wolfOnly} 的连接判定用 {@link GameViewQueryPort#viewerContextFor} 反查出的
     * 狼队集合，因此它仍然只有一份来源。
     *
     * @param gameId   对局
     * @param playerId 发言座位
     * @param seatNo   座位号（展示用）
     * @param speechId 发言标识；同一条发言的多段共享它
     * @param delta    本段的文本
     * @param index    段序号，从 0 开始
     * @param last     是否最后一段
     * @param wolfOnly 是否仅狼队可见（密谈）
     */
    public void onSpeechDelta(
            String gameId, String playerId, int seatNo, String speechId,
            String delta, int index, boolean last, boolean wolfOnly) {
        if (connections.isEmpty()) {
            return;
        }
        long now = System.currentTimeMillis();
        for (SubscriptionState state : new ArrayList<>(connections.values())) {
            if (!state.subscribedTo(gameId)) {
                continue;
            }
            if (wolfOnly && !isWolf(state, gameId)) {
                // 密谈不给非狼：既不发帧，也不留痕。
                continue;
            }
            dispatch(state.connectionId(), state.onSpeechDelta(
                    gameId, playerId, seatNo, speechId, delta, index, last, now));
        }
    }

    /**
     * 该连接的身份是否属于本局狼队。
     *
     * <p>用领域侧反查出的狼队集合，不自己判断角色——{@code viewerContextFor} 已经把
     * "哪些狼人座位对我可见"算好了，而那是可见性判定的唯一实现。
     */
    private boolean isWolf(SubscriptionState state, String gameId) {
        if (!state.authenticated()) {
            return false;
        }
        try {
            ViewerContext viewer = viewerOf(state, gameId);
            return !viewer.wolfPlayerIds().isEmpty();
        } catch (RuntimeException error) {
            // 反查失败按"不是狼"处理：密谈的失败方向必须偏保守。
            log.debug("狼队判定失败，按非狼处理 gameId={} userId={}", gameId, state.userId(), error);
            return false;
        }
    }

    /**
     * 心跳看护一轮（§3.3 的服务端侧）：关闭超时连接。
     *
     * <p>由定时任务周期性调用。把它做成**幂等的一次扫描**而不是定时器本身：
     * 扫描是纯逻辑、可离线测（用假时钟驱动状态机），而"多久扫一次"是调度策略。
     * 混在一起会让超时判定必须靠等待真实时间来验证。
     *
     * <p>服务端**不主动发 PING**，只做超时清理。理由：客户端每 15 秒发一次 PING
     * （`gameSocket` 已实现），因此"连接活着"已经有信号；服务端再加一路主动 PING
     * 只会让两个方向的计时互相干扰，而清理半开连接不需要主动发包——
     * 没收到客户端的 PING 就是没收到。
     *
     * @return 本轮关闭的连接数
     */
    public int sweepTimeouts() {
        long now = System.currentTimeMillis();
        int closed = 0;
        for (SubscriptionState state : new ArrayList<>(connections.values())) {
            ServerFrame.CloseCode verdict = state.heartbeatVerdict(now);
            if (verdict == null) {
                continue;
            }
            // 先让状态机进入 CLOSED，再交给 closeWith 摘登记并关闭。
            // 顺序反了会让 closeWith 之后的 state 仍处于可用阶段，
            // 而一个已摘除登记的连接若还被后续帧引用，行为就依赖时序。
            state.close();
            closeWith(state.connectionId(), verdict,
                    verdict == ServerFrame.CloseCode.UNAUTHENTICATED
                            ? "认证超时：连接后未在规定时间内完成 AUTH"
                            : "空闲超时：超过 " + SubscriptionState.IDLE_TIMEOUT_MILLIS / 1000
                                    + " 秒无客户端帧");
            closed++;
        }
        return closed;
    }

    /** 当前在册连接数；供诊断与看护的可见性。 */
    public int openConnections() {
        return connections.size();
    }

    /** 该事件是否对该连接的身份可见。判据是"它出现在该身份的可见集合里"。 */
    private Boolean visibleTo(SubscriptionState state, EventEnvelope event) {
        try {
            List<EventEnvelope> visible = gameViews.replay(
                    event.gameId(), viewerOf(state, event.gameId()), event.version(), event.version(), 1);
            for (EventEnvelope candidate : visible) {
                if (candidate.version() == event.version()) {
                    return true;
                }
            }
            return false;
        } catch (RuntimeException error) {
            // 查询失败时**不发**：宁可让客户端少收一帧并随后走 SYNC_REQUIRED，
            // 也不要把一个可能含隐藏信息的事件发出去。失败的方向必须偏安全一侧。
            log.debug("可见性判定失败，按不可见处理 gameId={} version={}",
                    event.gameId(), event.version(), error);
            return null;
        }
    }

    /**
     * 把协议状态机产出的帧交给传输层；若状态机已置 CLOSED，则真正关闭连接。
     *
     * <p><b>为什么这里只处理 4401 一种关闭</b>：状态机的 CLOSED 有两条来路——
     * AUTH 失败（4401）与持续超限（4429）。后者由 {@code onText} 在处理时立即
     * {@link #closeWith} 返回，不会走到这里（它连帧都不分派）。把 4429 也塞进这个方法
     * 需要在状态机里记"因为什么关闭"，而那是给一个不需要状态的东西建模。
     */
    private void dispatch(String connectionId, List<ServerFrame> frames) {
        if (frames == null || frames.isEmpty()) {
            return;
        }
        for (ServerFrame frame : frames) {
            // 原样转发：状态机已经算好了 seq（GAME_EVENT 用事件版本、管理帧用连接内自增号）。
            // 在这里重建帧会把 seq 丢掉——曾经就是这么错的。
            send(connectionId, frame);
        }
        SubscriptionState state = connections.get(connectionId);
        if (state == null || state.phase() != SubscriptionState.Phase.CLOSED) {
            return;
        }
        // 走到这里说明是 AUTH 失败：§3.6 要求客户端清会话而不是重连。
        closeWith(connectionId, ServerFrame.CloseCode.UNAUTHENTICATED, "认证失败或会话失效");
    }

    /**
     * 摘掉登记并关闭连接。
     *
     * <p>先取 sender 再摘登记：摘掉之后 {@code send()} 就找不到它了，而关闭必须用它。
     * 抽成一个方法是因为它有两条调用路径（认证失败、频率超限），
     * 而两处各写一遍必然漏掉其中一处的"摘登记"——那会留下一个永远不会被关闭的登记项。
     */
    private void closeWith(String connectionId, ServerFrame.CloseCode code, String reason) {
        Sender sender = senders.remove(connectionId);
        connections.remove(connectionId);
        if (sender == null) {
            return;
        }
        try {
            sender.close(connectionId, code.code(), reason);
        } catch (RuntimeException error) {
            log.debug("关闭连接失败 {}", connectionId, error);
        }
    }

    /**
     * 把状态机产出的帧原样写出。
     *
     * <p><b>不要在这里重建帧。</b>早期版本是这样写的：
     * {@code new ServerFrame(VERSION, "srv_"+nanoTime(), type, now, 0L, payload)} ——
     * 它丢掉了状态机算好的 {@code seq} 与 {@code id}，于是**所有 {@code GAME_EVENT} 的
     * seq 都变成 0**。后果是客户端按 seq 去重时会把第二个事件之后的所有事件全部丢弃，
     * 界面永远停在第 1 版。
     *
     * <p>这个缺陷能活到现在，是因为协议状态机的单元测试只验证"返回了什么帧"，
     * 而网关这一层没有测试；端到端跑一次真实连接才暴露出来。**"两端各自测过"不等于
     * "接起来是对的"**，这正是本轮端到端验证的价值。
     */
    private void send(String connectionId, ServerFrame frame) {
        Sender sender = senders.get(connectionId);
        if (sender == null) {
            return;
        }
        try {
            sender.send(connectionId, frame.toWire());
        } catch (RuntimeException error) {
            // 单个连接的发送失败不能影响其它连接：摘掉它即可。
            log.debug("发送失败，摘除连接 {}", connectionId, error);
            onClose(connectionId);
        }
    }

    /** 由类型与 payload 构造帧；连接内管理帧用它，`seq` 用连接内自增号。 */
    private void send(
            String connectionId, String type, Map<String, Object> payload, long connectionSeq) {
        send(connectionId, new ServerFrame(
                ServerFrame.VERSION, "srv_" + System.nanoTime(), type,
                System.currentTimeMillis(), connectionSeq, payload));
    }
}
