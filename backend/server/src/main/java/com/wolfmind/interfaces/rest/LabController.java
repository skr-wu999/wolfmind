package com.wolfmind.interfaces.rest;

import com.wolfmind.application.lab.LabAgentConfig;
import com.wolfmind.application.lab.LabApplicationService;
import com.wolfmind.application.lab.LabBatch;
import com.wolfmind.application.lab.LabQueryService;
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
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * AI 实验室接口（api-spec.md §8）。
 *
 * <p><b>必须登录</b>（api-spec.md §10：{@code /lab} 匿名不可用）：实验配置是账号资产，
 * 批次会真的开对局并消耗模型额度。匿名开放等于把别人的 token 预算公开。
 *
 * <p><b>接口层不做批次的准入判断。</b>并发上限、对局数上限、角色配置是否支持，
 * 全部由 {@link LabApplicationService} 判定；控制器只把它的拒绝翻译成 HTTP 语义
 * （{@code LabAdmissionException} → 429，参数错误 → 400）。
 */
@RestController
@RequestMapping(LabController.BASE_PATH)
class LabController {

    static final String BASE_PATH = "/api/v1";

    private final ObjectProvider<LabApplicationService> commands;
    private final ObjectProvider<LabQueryService> queries;
    private final ViewerResolverPort viewerResolver;

    LabController(
            ObjectProvider<LabApplicationService> commands,
            ObjectProvider<LabQueryService> queries,
            ViewerResolverPort viewerResolver) {
        this.commands = commands;
        this.queries = queries;
        this.viewerResolver = viewerResolver;
    }

    @GetMapping("/lab/agent-configs")
    Map<String, Object> listConfigs(
            @RequestParam(name = "page", defaultValue = "1") int page,
            @RequestParam(name = "pageSize", defaultValue = "20") int pageSize,
            HttpServletRequest request) {
        String ownerId = requireLogin(request);
        return query().catalog(ownerId, page, pageSize);
    }

    @PostMapping("/lab/agent-configs")
    ResponseEntity<Map<String, Object>> saveConfig(
            @RequestBody Map<String, Object> body,
            HttpServletRequest request) {
        String ownerId = requireLogin(request);
        LabAgentConfig config = command().saveConfig(body, ownerId);
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("agentConfigId", config.agentConfigId());
        response.put("name", config.name());
        response.put("role", config.role());
        response.put("model", config.model());
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @PostMapping("/lab/batches")
    ResponseEntity<Map<String, Object>> startBatch(
            @RequestBody Map<String, Object> body,
            @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey,
            HttpServletRequest request) {
        String ownerId = requireLogin(request);
        // 与写接口的通用约定一致（api-spec.md §1）：缺幂等键是请求形态错误，不是身份问题。
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            throw new RequestShapeException("缺少 Idempotency-Key 头（api-spec.md §1）");
        }
        LabBatch batch;
        try {
            batch = command().startBatch(body, ownerId);
        } catch (LabApplicationService.LabAdmissionException error) {
            throw new RateLimitedException(error.getMessage());
        }
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("batchId", batch.batchId());
        response.put("status", batch.status());
        response.put("total", batch.total());
        response.put("maxConcurrentGames", batch.maxConcurrentGames());
        response.put("serverMaxConcurrentGames", command().serverMaxConcurrentGames());
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(response);
    }

    /**
     * 批次进度。
     *
     * <p>返回体里同时有 {@code completed} 与 {@code total}，以及逐个对局的结果——
     * 只给两个数字的话，"这一批跑得怎么样"就只能靠一个百分比回答，而实验室真正要看的是分布。
     */
    @GetMapping("/lab/batches/{batchId}")
    Map<String, Object> batch(@PathVariable String batchId, HttpServletRequest request) {
        String ownerId = requireLogin(request);
        LabBatch batch;
        try {
            batch = query().batch(ownerId, batchId);
        } catch (LabQueryService.LabNotFoundException error) {
            throw new NotFoundException(error.getMessage());
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("batchId", batch.batchId());
        body.put("name", batch.name());
        body.put("status", batch.status());
        body.put("completed", batch.completed());
        body.put("total", batch.total());
        body.put("maxConcurrentGames", batch.maxConcurrentGames());
        body.put("agentConfigIds", batch.agentConfigIds());
        body.put("games", batch.games());
        body.put("statistics", batch.statistics());
        body.put("error", batch.error());
        body.put("createdAt", batch.createdAt());
        body.put("updatedAt", batch.updatedAt());
        return body;
    }

    @GetMapping("/lab/comparisons")
    Map<String, Object> comparisons(
            @RequestParam(name = "agentConfigIds", required = false) String agentConfigIds,
            @RequestParam(name = "role", required = false) String role,
            HttpServletRequest request) {
        String ownerId = requireLogin(request);
        return query().comparisons(ownerId, splitIds(agentConfigIds), role);
    }

    private static List<String> splitIds(String raw) {
        if (raw == null || raw.isBlank()) {
            return List.of();
        }
        List<String> ids = new ArrayList<>();
        Arrays.stream(raw.split(",")).map(String::trim).filter(id -> !id.isEmpty()).forEach(ids::add);
        return ids;
    }

    private LabApplicationService command() {
        LabApplicationService service = commands.getIfAvailable();
        if (service == null) {
            throw new NotWiredException("实验室写接口尚未接入：需要 LabApplicationService（api-spec.md §8）");
        }
        return service;
    }

    private LabQueryService query() {
        LabQueryService service = queries.getIfAvailable();
        if (service == null) {
            throw new NotWiredException("实验室读接口尚未接入：需要 LabQueryService（api-spec.md §8）");
        }
        return service;
    }

    private String requireLogin(HttpServletRequest request) {
        ViewerContext viewer = viewerResolver.resolve(request);
        String userId = viewer.viewerUserId();
        if (userId == null || userId.isBlank()) {
            throw new UnauthorizedException("AI 实验室需要登录（api-spec.md §10：/lab 匿名不可用）");
        }
        return userId;
    }
}
