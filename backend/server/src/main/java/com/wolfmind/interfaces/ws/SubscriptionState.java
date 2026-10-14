package com.wolfmind.interfaces.ws;

import com.wolfmind.domain.game.event.EventEnvelope;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 单条连接的订阅状态机（websocket-protocol.md §3、§8、§9）。
 *
 * <p><b>为什么它不依赖任何 WebSocket API</b>：协议语义（阶段转换、seq 规则、缺号检测、
 * 补发决策、积压上限、关闭码）是这条通道里最容易出错、也最需要测试的部分。把它做成纯 Java
 * 之后，全部规则都能用普通单元测试覆盖；传输层（真正的连接与发送）只剩"把帧写出去"这一件事。
 * 反过来把语义写进 WebSocket 端点里，就意味着每条断言都要先建一个连接。
 *
 * <p>它**不做可见性过滤**：那必须只有一个实现（event-design.md §4 硬规则 2、AP-6），
 * 已经在领域侧的 {@code VisibilityFilter} 里。调用方只把"该身份可见的事件"喂进来，
 * 因此这个类在结构上不可能泄漏隐藏信息。
 *
 * <p>seq 规则（§8.1，SEQ-1..SEQ-6 的实现）：
 * <ul>
 *   <li>初始 {@code lastSeq = 0}；只有 {@code GAME_EVENT} 与 {@code GAME_SNAPSHOT_SYNC} 推进它；</li>
 *   <li>{@code seq <= lastSeq} 丢弃（重复投递）；</li>
 *   <li>{@code seq == lastSeq + 1} 归约并推进；</li>
 *   <li>{@code seq > lastSeq + 1} 视为缺号——**缓存并立即要求 RESUME**，不跳过、
 *       不自己补空。跳过会让客户端永久丢失那个版本的状态，而它无法察觉。</li>
 * </ul>
 */
final class SubscriptionState {

    /** 阶段（§3.2）。它决定"当前允许哪些客户端帧"。 */
    enum Phase {
        /** 已连接未认证：只允许 AUTH。 */
        CONNECTED,
        /** 已认证未订阅：允许 AUTH 之外的全部管理帧。 */
        AUTHENTICATED,
        /** 已订阅：允许全部客户端帧。 */
        SUBSCRIBED,
        /** 已关闭：不再处理任何帧。 */
        CLOSED
    }

    /** 单连接积压上限（§9）。超出按丢弃优先级处理，仍超限则要求全量同步并关闭。 */
    static final int MAX_PENDING_FRAMES = 200;

    /** 未认证的超时（§3.2）：连接后 3 秒内必须发 AUTH。 */
    static final long AUTH_TIMEOUT_MILLIS = 3_000L;

    /** 空闲超时（§3.3）：45 秒内没有任何客户端帧则关闭。 */
    static final long IDLE_TIMEOUT_MILLIS = 45_000L;

    /** 心跳间隔（§3.3）。 */
    static final long HEARTBEAT_INTERVAL_MILLIS = 15_000L;

    private final String connectionId;
    private final AtomicLong frameIds = new AtomicLong();
    private final AtomicLong connectionSeq = new AtomicLong();

    /** 已认证账号；未认证时为 {@code null}。 */
    private String userId;
    private String sessionId;
    private Phase phase = Phase.CONNECTED;
    private long openedAt;
    private long lastClientFrameAt;

    /** gameId → 该订阅的 seq 游标。用 LinkedHashSet 保持订阅顺序，便于测试与诊断。 */
    private final Map<String, Long> lastSeqByGame = new LinkedHashMap<>();
    private final Map<String, String> scopeByGame = new LinkedHashMap<>();

    /** 单连接最多同时订阅的等待房数；等待房页面只需要一个，留出切换房间时的余量。 */
    static final int MAX_ROOM_SUBSCRIPTIONS = 4;

    /** 已订阅的等待房。房间快照没有游标：每帧都是完整状态，客户端按 {@code version} 丢弃旧帧。 */
    private final Set<String> rooms = new LinkedHashSet<>();

    void setScope(String gameId, boolean participant) {
        scopeByGame.put(gameId, participant ? "PLAYER" : "SPECTATOR");
    }
    /** 缺号时暂存的帧（§8.1：缓存并立即 RESUME，不跳过）。 */
    private final List<EventEnvelope> buffered = new ArrayList<>();
    /** 已确认处理到的 seq（§9 的 ACK 语义）：只影响积压缓冲，不影响领域状态。 */
    private long ackedSeq;

    /** 客户端帧频率限制（§9「发送速率限制」）。 */
    private final RateLimiter rateLimiter;

    /** 已因持续超限而判定关闭（由网关据此发 4429）。 */
    private boolean rateLimitExceeded;

    /**
     * 限速参数的**部署期配置点**。
     *
     * <p>为什么要有它：默认阈值（20 帧/秒、3 秒后关闭）在真实连接上很难在一个测试里
     * 走完——那需要真的发几百帧并等 3 秒。而"限速在真实连接上确实生效"只有真连才能证明。
     * 因此把窗口与阈值做成可配的，契约测试把它们调到很小。
     *
     * <p>用系统属性而不是 Spring 配置：{@code SubscriptionState} 由网关按连接构造，
     * 而网关是 {@code interfaces.ws} 的组件——为了读一个配置而给它注入
     * {@code Environment} 会把 Spring 依赖带进协议层，而这一层的可测性正来自
     * "不依赖任何框架"（见类注释）。
     */
    static final String RATE_WINDOW_PROPERTY = "wolfmind.ws.rate-window-millis";
    static final String RATE_MAX_PROPERTY = "wolfmind.ws.rate-max-frames";

    SubscriptionState(String connectionId) {
        this(connectionId,
                Long.getLong(RATE_WINDOW_PROPERTY, RateLimiter.DEFAULT_WINDOW_MILLIS),
                Integer.getInteger(RATE_MAX_PROPERTY, RateLimiter.DEFAULT_MAX_FRAMES));
    }

    SubscriptionState(String connectionId, long rateWindowMillis, int rateMaxFrames) {
        if (connectionId == null || connectionId.isBlank()) {
            throw new IllegalArgumentException("connectionId 不能为空");
        }
        this.connectionId = connectionId;
        this.rateLimiter = new RateLimiter(rateWindowMillis, rateMaxFrames);
    }

    String connectionId() {
        return connectionId;
    }

    Phase phase() {
        return phase;
    }

    String userId() {
        return userId;
    }

    boolean authenticated() {
        return userId != null;
    }

    Set<String> subscribedGames() {
        return new LinkedHashSet<>(lastSeqByGame.keySet());
    }

    boolean subscribedTo(String gameId) {
        return lastSeqByGame.containsKey(gameId);
    }


    long lastSeqOf(String gameId) {
        return lastSeqByGame.getOrDefault(gameId, 0L);
    }

    int pendingCount() {
        return buffered.size();
    }

    /** 连接已打开的时长；用于认证/空闲超时判定。 */
    long ageAt(long now) {
        return now - openedAt;
    }

    /** 距上次客户端帧的空闲时长。 */
    long idleAt(long now) {
        return now - lastClientFrameAt;
    }

    // ------------------------------------------------------------------
    // 状态转换
    // ------------------------------------------------------------------

    /** 记录连接建立。 */
    List<ServerFrame> onConnected(long now) {
        this.openedAt = now;
        this.lastClientFrameAt = now;
        return List.of();
    }

    /**
     * 处理 AUTH（§3.1）。
     *
     * <p>已认证后再次 AUTH 是协议错误（§5.1：只发一次），返回 ERROR 但**不关闭连接**——
     * 重复认证不影响已有订阅，断开它只会让客户端的重连逻辑徒增一次往返。
     */
    List<ServerFrame> onAuth(String token, SessionLookup sessions, long now) {
        if (phase == Phase.CLOSED) {
            return List.of();
        }
        this.lastClientFrameAt = now;
        if (authenticated()) {
            return List.of(error("AUTH_ALREADY_DONE", "本连接已认证，AUTH 只允许发送一次", now));
        }
        SessionLookup.Verified verified = sessions.verify(token).orElse(null);
        if (verified == null) {
            // §3.6：认证失败 → 4401，客户端不重连而是清会话跳登录。
            List<ServerFrame> frames = new ArrayList<>();
            frames.add(error("AUTH_REQUIRED", "会话无效或已过期", now));
            close();
            return frames;
        }
        this.userId = verified.userId();
        this.sessionId = verified.sessionId();
        this.phase = Phase.AUTHENTICATED;

        ServerFrame authOk = new ServerFrame(ServerFrame.VERSION, nextFrameId("AUTH_OK"),
                ServerFrame.Type.AUTH_OK.name(), now, nextConnectionSeq(),
                Map.of(
                        "userId", userId,
                        "username", verified.username(),
                        "sessionId", sessionId,
                        // 三个协商参数必须由服务端下发（§5.2）：客户端不能自己假设心跳与积压上限，
                        // 否则服务端调整策略后旧客户端会误判"连接卡住"。
                        "heartbeatIntervalMs", HEARTBEAT_INTERVAL_MILLIS,
                        "idleTimeoutMs", IDLE_TIMEOUT_MILLIS,
                        "maxPendingFrames", MAX_PENDING_FRAMES,
                        "serverTime", now));
        return List.of(authOk);
    }

    /**
     * 处理 SUBSCRIBE / RESUME（§3.1、§8.2）。
     *
     * <p>两者共用 SUBSCRIBED 确认帧（§0 的命名对齐说明）：语义一致，只是 RESUME 的意图
     * 更明确。{@code from} 恒为 {@code lastSeq + 1}，{@code truncated} 表示"缺口已超出
     * 可补发范围，应改发全量快照"。
     *
     * @param visibleEvents 该身份可见的事件（已由 VisibilityFilter 过滤），按 version 升序
     * @param availableTo   服务端持有的最大版本；用于判定 truncated
     */
    List<ServerFrame> onSubscribe(
            String gameId, long requestedLastSeq, boolean resume,
            List<EventEnvelope> visibleEvents, long availableTo, long now) {
        if (phase == Phase.CONNECTED) {
            return List.of(error("AUTH_REQUIRED", "订阅前必须完成 AUTH", now));
        }
        if (phase == Phase.CLOSED) {
            return List.of();
        }
        this.lastClientFrameAt = now;
        if (gameId == null || gameId.isBlank()) {
            return List.of(error("VALIDATION_ERROR", "SUBSCRIBE 缺少 gameId", now));
        }
        // 重复订阅只回一次 SUBSCRIBED（§6.1）：再回复会让客户端以为状态变了一次。
        if (subscribedTo(gameId) && requestedLastSeq == lastSeqOf(gameId)) {
            return List.of(subscribed(gameId, lastSeqOf(gameId), availableTo, false, resume, now));
        }

        long from = Math.max(1L, requestedLastSeq + 1);
        // truncated 的判定只有一条判据：请求区间超过了服务端持有的流尾，那些版本已经不在流里。
        // **不能**把"可见事件比请求条数少"当成截断——PRIVATE/WOLF/JUDGE 被过滤是正常的授权结果。
        boolean truncated = requestedLastSeq > availableTo;

        lastSeqByGame.put(gameId, requestedLastSeq);
        buffered.clear();
        phase = Phase.SUBSCRIBED;

        List<ServerFrame> frames = new ArrayList<>();
        if (truncated) {
            // 缺口无法补齐：不补发历史，改发全量快照（§8.3）。
            frames.add(syncRequired(gameId, "EVENT_TRUNCATED", requestedLastSeq, availableTo, now));
        }
        frames.add(subscribed(gameId, from, availableTo, truncated, resume, now));
        // 网关在 lastSeq=0 时交付快照；带游标时只补发其后的授权事件。
        long threshold = requestedLastSeq;
        for (EventEnvelope envelope : visibleEvents) {
            if (requestedLastSeq > 0 && !truncated && envelope.version() > threshold && envelope.version() <= availableTo) {
                frames.add(gameEvent(envelope, now));
                // 补发也必须推进游标：否则订阅后的第一条实时推送会被当成缺号，
                // 客户端就会立刻收到一个多余的 SYNC_REQUIRED。
                lastSeqByGame.put(gameId, (long) envelope.version());
            }
        }
        if (!truncated) lastSeqByGame.put(gameId, (long) availableTo);
        return frames;
    }

    /** 全量授权扫描已完成后重设内部游标，后续实时帧从同步点继续。 */
    ServerFrame snapshotSynced(String gameId, int version, Map<String, Object> payload, long now) {
        lastSeqByGame.put(gameId, (long) version);
        buffered.clear();
        return new ServerFrame(ServerFrame.VERSION, nextFrameId("GAME_SNAPSHOT_SYNC"),
                ServerFrame.Type.GAME_SNAPSHOT_SYNC.name(), now, version, payload);
    }

    void onInvisibleEvent(String gameId, int version) {
        if (subscribedTo(gameId) && version == lastSeqOf(gameId) + 1) {
            lastSeqByGame.put(gameId, (long) version);
        }
    }

    /** 处理 UNSUBSCRIBE。 */
    List<ServerFrame> onUnsubscribe(String gameId, long now) {
        if (phase == Phase.CLOSED) {
            return List.of();
        }
        this.lastClientFrameAt = now;
        long to = lastSeqOf(gameId);
        lastSeqByGame.remove(gameId);
        scopeByGame.remove(gameId);
        if (lastSeqByGame.isEmpty() && phase == Phase.SUBSCRIBED) {
            phase = Phase.AUTHENTICATED;
        }
        return List.of(new ServerFrame(ServerFrame.VERSION, nextFrameId("UNSUBSCRIBED"),
                ServerFrame.Type.UNSUBSCRIBED.name(), now, nextConnectionSeq(),
                Map.of("gameId", gameId, "to", to)));
    }

    boolean subscribedToRoom(String roomId) {
        return rooms.contains(roomId);
    }

    /** 等待房快照：房间事件流版本 + 与 {@code GET /rooms/{roomId}} 同形的房间视图。 */
    record RoomSnapshot(int version, Map<String, Object> room) {}

    /** 等待房快照的来源；房间不存在时返回空。 */
    interface RoomSnapshots {

        java.util.Optional<RoomSnapshot> load(String roomId);
    }

    /**
     * 处理 ROOM_SUBSCRIBE：先登记再读快照。
     *
     * <p>顺序不能反：读完快照、登记之前提交的房间变化，推送线程会因为"未订阅"而跳过，
     * 客户端就停在旧快照上。先登记的话，那次变化要么被推送、要么已包含在这里读到的快照里。
     */
    List<ServerFrame> onRoomSubscribe(String roomId, RoomSnapshots snapshots, long now) {
        if (phase == Phase.CONNECTED) {
            return List.of(error("AUTH_REQUIRED", "订阅前必须完成 AUTH", now));
        }
        if (phase == Phase.CLOSED) {
            return List.of();
        }
        this.lastClientFrameAt = now;
        boolean added = false;
        if (!rooms.contains(roomId)) {
            if (rooms.size() >= MAX_ROOM_SUBSCRIPTIONS) {
                return List.of(error("VALIDATION_ERROR",
                        "同一连接最多订阅 " + MAX_ROOM_SUBSCRIPTIONS + " 个房间", now));
            }
            rooms.add(roomId);
            added = true;
        }
        java.util.Optional<RoomSnapshot> snapshot = snapshots.load(roomId);
        if (snapshot.isEmpty()) {
            if (added) {
                rooms.remove(roomId);
            }
            return List.of(error("ROOM_NOT_FOUND", "房间不存在：" + roomId, now));
        }
        return List.of(roomSnapshot(roomId, snapshot.get(), now));
    }

    /** 处理 ROOM_UNSUBSCRIBE；不回应答帧，客户端发出后即停止处理该房间的帧。 */
    List<ServerFrame> onRoomUnsubscribe(String roomId, long now) {
        if (phase == Phase.CLOSED) {
            return List.of();
        }
        this.lastClientFrameAt = now;
        rooms.remove(roomId);
        return List.of();
    }

    /** 房间已提交变化后的推送；未订阅该房间时为空。 */
    List<ServerFrame> onRoomChanged(String roomId, RoomSnapshot snapshot, long now) {
        if (phase == Phase.CLOSED || !rooms.contains(roomId)) {
            return List.of();
        }
        return List.of(roomSnapshot(roomId, snapshot, now));
    }

    private ServerFrame roomSnapshot(String roomId, RoomSnapshot snapshot, long now) {
        return new ServerFrame(ServerFrame.VERSION, nextFrameId("ROOM_SNAPSHOT"),
                ServerFrame.Type.ROOM_SNAPSHOT.name(), now, nextConnectionSeq(),
                Map.of("roomId", roomId, "version", snapshot.version(), "room", snapshot.room()));
    }

    /**
     * 记一帧并给出限速处置（websocket-protocol.md §9）。
     *
     * <p>**在分派任何帧之前调用**——包括 PING 与 ACK：限速的目的正是防"用无效帧刷服务端"，
     * 因此不能只对需要鉴权的帧生效。
     *
     * @return 超限时返回应发回的 {@code ERROR} 帧；放行或需关闭时返回 {@code null}
     *         （关闭由 {@link #rateLimitExceeded()} 表达，由网关发出 4429）
     */
    ServerFrame checkRateLimit(long now) {
        if (phase == Phase.CLOSED) {
            return null;
        }
        RateLimiter.Verdict verdict = rateLimiter.record(now);
        if (verdict == RateLimiter.Verdict.ALLOW) {
            return null;
        }
        if (verdict == RateLimiter.Verdict.CLOSE) {
            // 持续超限：标记关闭，由网关发 4429。这里不构造 ERROR 帧——
            // 关闭时回一个错误帧没有意义，客户端会立刻失去连接，
            // 而 4429 的关闭码本身就携带了"频率超限"这个信息（§3.6）。
            rateLimitExceeded = true;
            phase = Phase.CLOSED;
            return null;
        }
        // 首次/偶发超限：**回错误但不关闭**。直接断开会让一个偶发突发
        // （连点两下、页面恢复时补发积压 ACK）变成掉线重连，而重连又会带来一批帧，
        // 形成"越忙越断"的循环。
        return error("RATE_LIMITED", "客户端帧频率超限，请降低发送频率", now);
    }

    /** 是否因持续超限而应关闭连接（关闭码 4429）。 */
    boolean rateLimitExceeded() {
        return rateLimitExceeded;
    }

    /** 当前窗口内的帧数；供诊断与测试。 */
    int rateWindowCount(long now) {
        return rateLimiter.windowCount(now);
    }

    /** 生效的限速配置（窗口毫秒、窗口内上限）；供契约测试确认调参真的传到了。 */
    String rateLimitConfig() {
        return rateLimiter.windowMillis() + "ms/" + rateLimiter.maxFrames() + "帧";
    }
    /** 处理 PING；服务端必须回 PONG（§3.3）。PING 是有效活动，会刷新空闲计时。 */
    List<ServerFrame> onPing(long now) {
        if (phase == Phase.CLOSED) {
            return List.of();
        }
        this.lastClientFrameAt = now;
        return List.of(new ServerFrame(ServerFrame.VERSION, nextFrameId("PONG"),
                ServerFrame.Type.PONG.name(), now, nextConnectionSeq(),
                Map.of("serverSeq", connectionSeq.get(), "serverTime", now)));
    }

    /** 处理 ACK：只推进"已确认"水位，不影响 lastSeq 与领域状态。 */
    List<ServerFrame> onAck(long seq, long now) {
        if (phase == Phase.CLOSED) {
            return List.of();
        }
        this.lastClientFrameAt = now;
        if (seq > ackedSeq) {
            ackedSeq = seq;
            // 已确认的帧可以从积压缓冲里丢掉：它们不再需要在重连时补发。
            buffered.removeIf(envelope -> envelope.version() <= ackedSeq);
        }
        return List.of();
    }

    // ------------------------------------------------------------------
    // 服务端推送
    // ------------------------------------------------------------------

    /**
     * 推送一个对局事件（§7.1）。
     *
     * <p>返回的帧列表可能是空的（该连接没订阅这个对局，或版本已在游标之前）。
     * 缺号时返回 {@code SYNC_REQUIRED} 并把事件缓存起来——**不跳过**，
     * 因为跳过会让客户端永久丢失那个版本的状态而它无法察觉。
     */
    List<ServerFrame> onEvent(EventEnvelope envelope, long now) {
        if (phase == Phase.CLOSED || !subscribedTo(envelope.gameId())) {
            return List.of();
        }
        long cursor = lastSeqOf(envelope.gameId());
        if (envelope.version() <= cursor) {
            // 重复投递（至少一次语义）：丢弃，不推进。
            return List.of();
        }
        if (envelope.version() > cursor + 1) {
            // 缺号：缓存 + 立即要求 RESUME。
            if (buffered.size() < MAX_PENDING_FRAMES) {
                buffered.add(envelope);
            }
            return List.of(syncRequired(
                    envelope.gameId(), "SEQ_GAP", cursor, envelope.version(), now));
        }
        lastSeqByGame.put(envelope.gameId(), (long) envelope.version());
        return List.of(gameEvent(envelope, now));
    }

    /**
     * 推送发言增量（§5.2 的 {@code AGENT_SPEECH_DELTA}）。
     *
     * <p>它是**展示更新**，不是领域事件（AGENTS.md §9）：不推进 seq，因此**可以在积压超限时
     * 优先丢弃**——丢一帧预览的代价远小于丢一帧状态。
     */
    List<ServerFrame> onSpeechDelta(
            String gameId, String playerId, int seatNo, String speechId,
            String delta, int index, boolean last, long now) {
        if (phase == Phase.CLOSED || !subscribedTo(gameId)) {
            return List.of();
        }
        return List.of(new ServerFrame(ServerFrame.VERSION, nextFrameId("AGENT_SPEECH_DELTA"),
                ServerFrame.Type.AGENT_SPEECH_DELTA.name(), now, nextConnectionSeq(),
                Map.of("gameId", gameId, "playerId", playerId, "seatNo", seatNo,
                        "speechId", speechId, "delta", delta, "index", index, "final", last)));
    }

    /** 主动关闭：标记阶段并返回关闭码。 */
    ServerFrame.CloseCode close() {
        phase = Phase.CLOSED;
        scopeByGame.clear();
        rooms.clear();
        return ServerFrame.CloseCode.TIMEOUT;
    }

    /** 是否需要因认证超时而关闭（§3.2）。 */
    boolean authTimedOut(long now) {
        return phase == Phase.CONNECTED && ageAt(now) >= AUTH_TIMEOUT_MILLIS;
    }

    /**
     * 是否因空闲超时而关闭（§3.3）。
     *
     * <p><b>只在已认证后生效。</b>未认证的连接由 {@link #authTimedOut} 管——它 3 秒就断开。
     * 不区分会出两个问题：一是未认证连接要白等 45 秒才被清理；二是同一条连接在 45 秒时
     * 会因为两个判据同时成立而无法确定该报 4401 还是 4408。
     */
    boolean idleTimedOut(long now) {
        return authenticated() && phase != Phase.CLOSED && idleAt(now) >= IDLE_TIMEOUT_MILLIS;
    }

    /**
     * 心跳看护的判定（§3.3、§3.6）：该连接是否应因超时被关闭。
     *
     * <p>返回 {@code null} 表示连接仍然健康。这是看护定时器唯一需要问的问题——
     * 把两个判据收在一处，避免调用方自己去比较"认证了没"来决定用哪个超时。
     *
     * <p>先判认证超时：未认证的连接必须在 3 秒内被清理，否则一个只连不说话的客户端
     * 会占着一个连接槽到 45 秒。
     */
    ServerFrame.CloseCode heartbeatVerdict(long now) {
        if (phase == Phase.CLOSED) {
            return null;
        }
        if (authTimedOut(now)) {
            // §3.6：认证失败/超时用 4401，客户端据此清会话而不是重连。
            return ServerFrame.CloseCode.UNAUTHENTICATED;
        }
        if (idleTimedOut(now)) {
            // §3.6：认证/订阅/心跳超时用 4408，客户端重连即可恢复。
            return ServerFrame.CloseCode.TIMEOUT;
        }
        return null;
    }

    // ------------------------------------------------------------------
    // 帧构造
    // ------------------------------------------------------------------

    private ServerFrame subscribed(
            String gameId, long from, long to, boolean truncated, boolean resumed, long now) {
        return new ServerFrame(ServerFrame.VERSION, nextFrameId("SUBSCRIBED"),
                ServerFrame.Type.SUBSCRIBED.name(), now, nextConnectionSeq(),
                Map.of(
                        "gameId", gameId,
                        // scope 由服务端判定（§6.1）：请求里不带角色/阵营/座位/观战参数，
                        // 否则客户端就能自称观战者拿到上帝视图。
                        "scope", scopeByGame.getOrDefault(gameId, "PLAYER"),
                        // from = lastSeq + 1（§8.1）：补发从这一版开始。
                        // 首次订阅 lastSeq=0，因此 from=1——客户端据此知道"第一条该收到的
                        // GAME_EVENT 的 seq 是 1"。写成 from+1 会让客户端以为有空洞而白跑一次 RESUME。
                        "from", from,
                        "to", to,
                        "truncated", truncated,
                        "resumed", resumed));
    }

    private ServerFrame syncRequired(
            String gameId, String reason, long lastSeq, long serverSeq, long now) {
        return new ServerFrame(ServerFrame.VERSION, nextFrameId("SYNC_REQUIRED"),
                ServerFrame.Type.SYNC_REQUIRED.name(), now, nextConnectionSeq(),
                Map.of("gameId", gameId, "reason", reason,
                        "lastSeq", lastSeq, "serverSeq", serverSeq));
    }

    private ServerFrame gameEvent(EventEnvelope envelope, long now) {
        // seq 等于事件 version（§4.3）：客户端据此排序与去重，因此必须是版本而不是自增号。
        return new ServerFrame(ServerFrame.VERSION, nextFrameId("GAME_EVENT"),
                ServerFrame.Type.GAME_EVENT.name(), now, envelope.version(),
                eventPayload(envelope));
    }

    /** 事件 payload：字段与 event-design.md §2 的信封逐字一致（P-1）。 */
    static Map<String, Object> eventPayload(EventEnvelope envelope) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("eventId", envelope.eventId());
        payload.put("gameId", envelope.gameId());
        payload.put("version", envelope.version());
        payload.put("type", envelope.type());
        payload.put("schemaVersion", envelope.schemaVersion());
        payload.put("occurredAt", envelope.occurredAt());
        payload.put("phase", envelope.phase());
        payload.put("round", envelope.round());
        payload.put("actorId", envelope.actorId());
        payload.put("visibility", envelope.visibility().name());
        payload.put("causationId", envelope.causationId());
        payload.put("correlationId", envelope.correlationId());
        payload.put("payload", envelope.payload());
        return payload;
    }

    private ServerFrame error(String code, String message, long now) {
        return new ServerFrame(ServerFrame.VERSION, nextFrameId("ERROR"),
                ServerFrame.Type.ERROR.name(), now, nextConnectionSeq(),
                Map.of("code", code, "message", message, "details", List.of(),
                        "traceId", "cor_" + connectionId));
    }

    private String nextFrameId(String prefix) {
        return prefix.toLowerCase(java.util.Locale.ROOT) + "_" + frameIds.incrementAndGet();
    }

    private long nextConnectionSeq() {
        return connectionSeq.incrementAndGet();
    }

    /** 会话校验的最小依赖；避免让协议语义依赖整个认证服务。 */
    interface SessionLookup {

        java.util.Optional<Verified> verify(String token);

        record Verified(String userId, String username, String sessionId) {}
    }
}
