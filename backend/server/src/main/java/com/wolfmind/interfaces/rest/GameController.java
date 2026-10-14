package com.wolfmind.interfaces.rest;

import com.wolfmind.application.game.ActionResult;
import com.wolfmind.application.game.SubmitActionApplicationService;
import com.wolfmind.application.game.SubmitActionCommand;
import com.wolfmind.application.port.GameViewQueryPort;
import com.wolfmind.domain.game.event.EventEnvelope;
import com.wolfmind.domain.game.model.ViewerContext;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
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
 * 对局接口（api-spec.md 5）。
 *
 * <p>职责边界（ddd-design.md 5.1）：只做鉴权与参数转换、调用应用服务、把结果映射为 HTTP 响应。
 * 本类没有任何规则判断，也不直接访问 Redis / SQLite / 领域聚合——脱敏已经在领域侧的
 * GameViewProjector 完成，接口层不做"谁能看什么"的判断（AP-6）。
 */
@RestController
@RequestMapping(GameController.BASE_PATH)
class GameController {

    static final String BASE_PATH = "/api/v1";
    private static final int REPLAY_MAX_EVENTS = 500;

    /** 事件信封形态；字段与 event-design.md 2 逐字一致。 */
    private static final List<String> EVENT_FIELDS = List.of(
            "eventId", "gameId", "version", "type", "schemaVersion", "occurredAt",
            "phase", "round", "actorId", "visibility", "causationId", "correlationId", "payload");

    private final GameViewQueryPort viewQuery;
    private final ViewerResolverPort viewerResolver;
    private final ObjectProvider<SubmitActionApplicationService> submitAction;

    /**
     * @param submitAction 用 ObjectProvider 注入而不是直接依赖：只读部署（回放/观战节点）不应为了
     *                     一个写接口被迫构造整条写路径（锁、幂等、事件发布）。缺依赖时该路由返回
     *                     501 并指明缺什么，而不是启动失败或假装成功。
     */
    GameController(
            GameViewQueryPort viewQuery,
            ViewerResolverPort viewerResolver,
            ObjectProvider<SubmitActionApplicationService> submitAction) {
        this.viewQuery = viewQuery;
        this.viewerResolver = viewerResolver;
        this.submitAction = submitAction;
    }

    @GetMapping("/games")
    Map<String, Object> listGames(
            @RequestParam(name = "status", required = false) String status,
            @RequestParam(name = "roomId", required = false) String roomId,
            @RequestParam(name = "page", defaultValue = "1") int page,
            @RequestParam(name = "pageSize", defaultValue = "20") int pageSize) {
        if (page < 1 || page > 10_000) {
            throw new RequestShapeException("参数 page 必须在 1..10000 之间，实际为 " + page);
        }
        if (pageSize < 1 || pageSize > 100) {
            throw new RequestShapeException("参数 pageSize 必须在 1..100 之间，实际为 " + pageSize);
        }
        List<Map<String, Object>> items = viewQuery.listGames(status, roomId, page, pageSize);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("items", items);
        body.put("page", page);
        body.put("pageSize", pageSize);
        // api-spec.md 1 要求分页响应含 total。当前仓储只返回当前页，total 取已取回条数，
        // 与"全量计数"语义有偏差，已在 TASKS.md 记为待修项——不在这里编一个假的全量数字。
        body.put("total", items.size());
        return body;
    }

    @GetMapping("/games/{gameId}")
    Object gameView(@PathVariable String gameId, HttpServletRequest request) {
        return viewQuery.view(gameId, gameViewer(gameId, request));
    }

    /**
     * 回放（api-spec.md §6）。
     *
     * <p>数据来源由视图端口决定：事件流在就读流，流已过期（TTL 到期）则读 SQLite 归档，
     * 两者用同一份 {@code VisibilityFilter}，因此"谁能看到哪条"只有一处实现。
     * {@code truncated} 的正确含义是**本次数据来自归档**，而不是"可见事件少于请求条数"——
     * PRIVATE/WOLF/JUDGE 被过滤是正常的授权结果。
     *
     * <p>{@code ?atVersion=} 让回放页取到"第 N 步的棋盘"。它必须由服务端重建：
     * 让客户端按事件增量演进等于让前端再实现一遍脱敏投影（AP-6）。
     */
    @GetMapping("/games/{gameId}/replay")
    Map<String, Object> replay(
            @PathVariable String gameId,
            @RequestParam(name = "from", defaultValue = "1") int from,
            @RequestParam(name = "to", defaultValue = "" + Integer.MAX_VALUE) int to,
            @RequestParam(name = "atVersion", required = false) String atVersion,
            HttpServletRequest request) {
        if (from < 1) {
            throw new RequestShapeException("参数 from 必须大于等于 1，实际为 " + from);
        }
        if (to < from) {
            throw new RequestShapeException("参数 to 不能小于 from");
        }
        ViewerContext viewer = gameViewer(gameId, request);
        GameViewQueryPort.ReplayPage page =
                viewQuery.replayPage(gameId, viewer, from, to, REPLAY_MAX_EVENTS);
        int available = page.availableVersion();
        int effectiveTo = Math.min(to, Math.max(available, from - 1));

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("gameId", gameId);
        body.put("from", from);
        body.put("to", effectiveTo);
        body.put("total", page.events().size());
        body.put("truncated", page.fromArchive());
        List<Map<String, Object>> mapped = new ArrayList<>(page.events().size());
        for (EventEnvelope envelope : page.events()) {
            mapped.add(toEventMap(envelope));
        }
        body.put("events", mapped);
        if (atVersion != null && !atVersion.isBlank()) {
            body.put("atVersion", parseAtVersion(atVersion));
            body.put("snapshot", viewQuery.viewAt(gameId, viewer, parseAtVersion(atVersion)));
        }
        return body;
    }

    private static int parseAtVersion(String raw) {
        try {
            int value = Integer.parseInt(raw.trim());
            if (value < 1) {
                throw new RequestShapeException("参数 atVersion 必须大于等于 1，实际为 " + raw);
            }
            return value;
        } catch (NumberFormatException error) {
            throw new RequestShapeException("参数 atVersion 必须是整数，实际为 " + raw);
        }
    }

    /**
     * 提交行动（api-spec.md 5.3）。
     *
     * <p><b>默认不返回 view</b>：accepted=true 只表示命令已被领域接受并写入事件，客户端必须等
     * PLAYER_VOTED 等事件到达后再更新界面。若默认返回视图，前端会倾向"用响应刷新界面"，
     * 从而绕过事件顺序与去重。?include=view 是 WebSocket 不可用时的兜底。
     */
    @PostMapping("/games/{gameId}/actions")
    ResponseEntity<Map<String, Object>> submitAction(
            @PathVariable String gameId,
            @RequestBody Map<String, Object> body,
            @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey,
            @RequestParam(name = "include", required = false) String include,
            HttpServletRequest request) {

        SubmitActionApplicationService service = submitAction.getIfAvailable();
        if (service == null) {
            // 只读部署：明确回 501 并指明缺哪个服务，不假装成功——否则客户端无法区分
            // "成功但没有事件"与"根本没实现"。
            throw new NotWiredException(
                    "写接口尚未接入：需要 SubmitActionApplicationService（api-spec.md 5.3）");
        }

        // 请求形态先于身份校验：缺 Idempotency-Key 是"请求本身不合法"，用 401 表达会把
        // "客户端少发了头"误导成"你没登录"。先拒形态，再拒身份。
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            throw new RequestShapeException("缺少 Idempotency-Key 头（api-spec.md 1）");
        }

        ViewerContext viewer = viewerResolver.resolve(request);
        String actorId = viewer.viewerUserId();
        if (actorId == null || actorId.isBlank()) {
            throw new UnauthorizedException("提交行动需要登录：Authorization: Bearer <sessionToken>");
        }

        String actionType = requiredString(body, "actionType");
        int expectedVersion = requiredInt(body, "expectedVersion");
        @SuppressWarnings("unchecked")
        Map<String, Object> params = body.get("params") instanceof Map<?, ?> map
                ? (Map<String, Object>) map
                : Map.of();

        ActionResult result = service.submit(new SubmitActionCommand(
                gameId, actorId, actionType, params, expectedVersion, idempotencyKey, null));

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("accepted", result.accepted());
        response.put("gameId", result.gameId());
        response.put("version", result.version());
        response.put("eventIds", result.eventIds());
        response.put("eventTypes", result.eventTypes());
        response.put("replayed", result.replayed());
        if ("view".equals(include)) {
            response.put("view", viewQuery.view(gameId, gameViewer(gameId, request)));
        }
        return ResponseEntity.status(HttpStatus.OK).body(response);
    }

    private ViewerContext gameViewer(String gameId, HttpServletRequest request) {
        ViewerContext identity = viewerResolver.resolve(request);
        return viewQuery.viewerContextFor(gameId, identity.viewerUserId());
    }

    private static Map<String, Object> toEventMap(EventEnvelope envelope) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("eventId", envelope.eventId());
        map.put("gameId", envelope.gameId());
        map.put("version", envelope.version());
        map.put("type", envelope.type());
        map.put("schemaVersion", envelope.schemaVersion());
        map.put("occurredAt", envelope.occurredAt());
        map.put("phase", envelope.phase());
        map.put("round", envelope.round());
        map.put("actorId", envelope.actorId());
        map.put("visibility", envelope.visibility());
        map.put("causationId", envelope.causationId());
        map.put("correlationId", envelope.correlationId());
        map.put("payload", envelope.payload());
        return map;
    }

    private static String requiredString(Map<String, Object> body, String field) {
        Object value = body.get(field);
        if (value instanceof String text && !text.isBlank()) {
            return text;
        }
        throw new RequestShapeException("请求体缺少必填字段 " + field + "（字符串）");
    }

    private static int requiredInt(Map<String, Object> body, String field) {
        Object value = body.get(field);
        if (value instanceof Number number) {
            return number.intValue();
        }
        if (value instanceof String text && !text.isBlank()) {
            try {
                return Integer.parseInt(text.trim());
            } catch (NumberFormatException error) {
                throw new RequestShapeException(field + " 必须是整数，实际为 " + text);
            }
        }
        throw new RequestShapeException("请求体缺少必填字段 " + field + "（整数）");
    }
}
