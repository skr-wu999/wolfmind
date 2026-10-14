package com.wolfmind.interfaces.rest;

import com.wolfmind.application.room.StartRoomGameApplicationService;

import com.wolfmind.application.room.GetRoomQueryService;
import com.wolfmind.application.room.RoomApplicationService;
import com.wolfmind.application.room.RoomView;
import com.wolfmind.domain.game.model.ViewerContext;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 房间接口（api-spec.md 4）。
 *
 * <p>接口层只做四件事：取身份、取幂等键、做参数形态转换、调应用服务。
 * 能不能入座、能不能开局全部在 {@code Room} 聚合里（AGENTS.md 5 非协商规则 3/6）。
 *
 * <p><b>身份来源</b>：只取服务端会话解析出的 {@code userId}。请求体里的 {@code ownerId} /
 * {@code playerId} 一律忽略——那是"我是谁"的声明，不能当授权证据（AGENTS.md 8）。
 */
@RestController
@RequestMapping(RoomController.BASE_PATH)
class RoomController {

    static final String BASE_PATH = "/api/v1";

    private final ObjectProvider<RoomApplicationService> commands;
    private final ObjectProvider<GetRoomQueryService> queries;
    private final ObjectProvider<StartRoomGameApplicationService> starts;
    private final ViewerResolverPort viewerResolver;
    @org.springframework.beans.factory.annotation.Autowired
    private ObjectProvider<com.wolfmind.application.room.RoomLifecycleService> lifecycle;

    RoomController(
            ObjectProvider<RoomApplicationService> commands,
            ObjectProvider<GetRoomQueryService> queries,
            ObjectProvider<StartRoomGameApplicationService> starts,
            ViewerResolverPort viewerResolver) {
        this.commands = commands;
        this.queries = queries;
        this.starts = starts;
        this.viewerResolver = viewerResolver;
    }

    // ---------------------------------------------------------------- 只读

    @GetMapping("/rooms")
    Map<String, Object> listRooms(
            @RequestParam(name = "status", required = false) String status,
            @RequestParam(name = "page", defaultValue = "1") int page,
            @RequestParam(name = "pageSize", defaultValue = "20") int pageSize) {
        requirePositivePage(page, pageSize);
        GetRoomQueryService service = queryService();
        List<Map<String, Object>> items = new ArrayList<>();
        for (RoomView room : service.list(status, page, pageSize)) {
            items.add(room.toSummary());
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("items", items);
        body.put("page", page);
        body.put("pageSize", pageSize);
        // api-spec.md 1：total 是过滤后的全量条数，不是当前页条数。
        body.put("total", service.count(status));
        return body;
    }

    @GetMapping("/rooms/{roomId}")
    RoomView getRoom(@PathVariable String roomId) {
        return queryService().view(roomId);
    }

    // ---------------------------------------------------------------- 写

    /** 创建房间：房主自动占 1 号座。 */
    @PostMapping("/rooms")
    ResponseEntity<RoomView> createRoom(
            @RequestBody(required = false) Map<String, Object> body,
            @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey,
            HttpServletRequest request) {
        String actorId = requireActor(request);
        String key = requireIdempotencyKey(idempotencyKey);
        Map<String, Object> payload = body == null ? Map.of() : body;
        String name = string(payload, "name");
        Map<String, Object> config = object(payload, "config");
        RoomView room = commandService().create(actorId, name, config, key);
        return ResponseEntity.status(HttpStatus.CREATED).body(room);
    }

    @PostMapping("/rooms/{roomId}/join")
    RoomView join(
            @PathVariable String roomId,
            @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey,
            HttpServletRequest request) {
        String actorId = requireActor(request);
        String key = requireIdempotencyKey(idempotencyKey);
        return commandService().join(roomId, actorId, key, key);
    }

    @PostMapping("/rooms/{roomId}/leave")
    ResponseEntity<RoomView> leave(
            @PathVariable String roomId,
            @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey,
            HttpServletRequest request) {
        String actorId = requireActor(request);
        var exits = lifecycle == null ? null : lifecycle.getIfAvailable();
        String key = requireIdempotencyKey(idempotencyKey);
        RoomView room = exits == null ? commandService().leave(roomId, actorId, key)
                : exits.leaveRoom(roomId, actorId, key);
        return ResponseEntity.ok(room);
    }

    @PostMapping("/rooms/{roomId}/seats/{seatNo}/take")
    RoomView takeSeat(
            @PathVariable String roomId,
            @PathVariable int seatNo,
            @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey,
            HttpServletRequest request) {
        String actorId = requireActor(request);
        return commandService().takeSeat(
                roomId, actorId, seatNo, requireIdempotencyKey(idempotencyKey));
    }

    /** 把座位交给 AI；{@code agentConfigId} 缺省时用规则基线配置。 */
    @PostMapping("/rooms/{roomId}/seats/{seatNo}/ai")
    Map<String, Object> seatAi(
            @PathVariable String roomId,
            @PathVariable int seatNo,
            @RequestBody(required = false) Map<String, Object> body,
            @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey,
            HttpServletRequest request) {
        String actorId = requireActor(request);
        Map<String, Object> payload = body == null ? Map.of() : body;
        // agentConfigId 属于 Phase 5 的 Agent 配置接口；未提供时用规则基线，
        // 这是唯一一个"有合理默认值"的 AI 参数，其余参数都留在配置对象里。
        String agentConfigId = optionalString(payload, "agentConfigId");
        if (agentConfigId == null) {
            agentConfigId = "rule-baseline";
        }
        RoomView room = commandService().seatAi(
                roomId, actorId, seatNo, agentConfigId, requireIdempotencyKey(idempotencyKey));
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("seatNo", seatNo);
        response.put("controller", Map.of("type", "AI", "refId", agentConfigId));
        response.put("room", room);
        return response;
    }

    @DeleteMapping("/rooms/{roomId}/seats/{seatNo}/ai")
    RoomView clearAi(
            @PathVariable String roomId,
            @PathVariable int seatNo,
            @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey,
            HttpServletRequest request) {
        String actorId = requireActor(request);
        return commandService().clearAi(
                roomId, actorId, seatNo, requireIdempotencyKey(idempotencyKey));
    }

    @PostMapping("/rooms/{roomId}/ready")
    Map<String, Object> ready(
            @PathVariable String roomId,
            @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey,
            HttpServletRequest request) {
        String actorId = requireActor(request);
        RoomView room = commandService().ready(
                roomId, actorId, requireIdempotencyKey(idempotencyKey));
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("readyCount", room.readyCount());
        response.put("allReady", room.occupied() == room.capacity() && room.readyCount() == room.capacity());
        response.put("room", room);
        return response;
    }

    /**
     * 开局（api-spec.md 5.1）。
     *
     * <p>{@code autoFillAi} 为 true 时把剩余空座补成规则基线 AI，再冻结房间。
     * 补位顺序固定（按座位号升序），因此同一房间的补位结果可复现。
     */
    @PostMapping("/rooms/{roomId}/games")
    ResponseEntity<Map<String, Object>> startGame(
            @PathVariable String roomId,
            @RequestBody(required = false) Map<String, Object> body,
            @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey,
            HttpServletRequest request) {
        String actorId = requireActor(request);
        String key = requireIdempotencyKey(idempotencyKey);
        boolean autoFillAi = bool(body == null ? Map.of() : body, "autoFillAi");

        RoomApplicationService service = commandService();
        if (autoFillAi) {
            /*
             * 补位到房间满座。**判据是"该座位没有真人控制者"，不是"控制器为 null"。**
             *
             * 第 38 轮抓到的缺陷：房主 `leave` 之后，那个座位的控制器**仍在**——
             * 它是房主的 `HUMAN` 控制器（`refId` 是房主 userId）。
             * 原先的 `controller() != null` 于是把这一座 `continue` 掉，
             * **座位 1 从未变成 AI**。
             *
             * 症状极具欺骗性：其余 8 个座位完全正常，只有座位 1 卡住，
             * 而 `DAY_DISCUSS` 的回合游标恰好从座位 1 开始——
             * 于是 `turnIndex` 不前进、整局停住，看起来像"游戏逻辑坏了"。
             * 实测那一刻 `成功提交=5`，**没有一次来自座位 1**。
             *
             * 一个座位的失败会让整局停住：这就是"AI 补位"这个验收项的边界情况。
             */
            for (int seatNo = 1; seatNo <= 9; seatNo++) {
                RoomView current = queryService().view(roomId);
                RoomView.ControllerView seated = current.seats().get(seatNo - 1).controller();
                boolean hasHuman = seated != null
                        && com.wolfmind.domain.game.model.ControllerType.HUMAN.name()
                                .equals(seated.type());
                if (hasHuman) {
                    continue;
                }
                service.seatAi(roomId, actorId, seatNo, "rule-baseline", key + ":ai:" + seatNo);
            }
        }

        // 真正开局：编排服务负责"发牌 + 冻结房间 + 实例化 AI Agent"。
        // 就绪校验仍在聚合里（INV-R2）：未全部就绪会得到 NOT_READY。
        StartRoomGameApplicationService.StartResult result =
                startService().start(roomId, actorId, key + ":start");

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("gameId", result.gameId());
        response.put("status", result.room().status());
        response.put("version", result.version());
        response.put("phase", result.phase());
        response.put("agentCount", result.agentCount());
        response.put("roomId", result.room().roomId());
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    // ---------------------------------------------------------------- 辅助

    private RoomApplicationService commandService() {
        RoomApplicationService service = commands.getIfAvailable();
        if (service == null) {
            throw new NotWiredException("写接口尚未接入：需要 RoomApplicationService（api-spec.md 4）");
        }
        return service;
    }

    private GetRoomQueryService queryService() {
        GetRoomQueryService service = queries.getIfAvailable();
        if (service == null) {
            throw new NotWiredException("只读接口尚未接入：需要 GetRoomQueryService（api-spec.md 4）");
        }
        return service;
    }

    private StartRoomGameApplicationService startService() {
        StartRoomGameApplicationService service = starts.getIfAvailable();
        if (service == null) {
            throw new NotWiredException("开局尚未接入：需要 StartRoomGameApplicationService（api-spec.md 5.1）");
        }
        return service;
    }

    /** 身份只来自服务端会话解析；未登录一律 401。 */
    private String requireActor(HttpServletRequest request) {
        ViewerContext viewer = viewerResolver.resolve(request);
        String actorId = viewer.viewerUserId();
        if (actorId == null || actorId.isBlank()) {
            throw new UnauthorizedException("房间操作需要登录：Authorization: Bearer <sessionToken>");
        }
        return actorId;
    }

    private static String requireIdempotencyKey(String idempotencyKey) {
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            throw new RequestShapeException("缺少 Idempotency-Key 头（api-spec.md 1）");
        }
        return idempotencyKey;
    }

    private static void requirePositivePage(int page, int pageSize) {
        if (page < 1 || page > 10_000) {
            throw new RequestShapeException("参数 page 必须在 1..10000 之间，实际为 " + page);
        }
        if (pageSize < 1 || pageSize > 100) {
            throw new RequestShapeException("参数 pageSize 必须在 1..100 之间，实际为 " + pageSize);
        }
    }

    private static String string(Map<String, Object> body, String field) {
        Object value = body.get(field);
        if (value instanceof String text && !text.isBlank()) {
            return text;
        }
        throw new RequestShapeException("请求体缺少必填字段 " + field + "（字符串）");
    }

    private static String optionalString(Map<String, Object> body, String field) {
        Object value = body.get(field);
        return value instanceof String text && !text.isBlank() ? text : null;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> object(Map<String, Object> body, String field) {
        Object value = body.get(field);
        if (value == null) {
            return Map.of();
        }
        if (value instanceof Map<?, ?> map) {
            return (Map<String, Object>) map;
        }
        throw new RequestShapeException("字段 " + field + " 必须是 JSON 对象");
    }

    private static boolean bool(Map<String, Object> body, String field) {
        Object value = body.get(field);
        if (value instanceof Boolean flag) {
            return flag;
        }
        if (value instanceof String text && !text.isBlank()) {
            return Boolean.parseBoolean(text.trim());
        }
        return false;
    }
}
