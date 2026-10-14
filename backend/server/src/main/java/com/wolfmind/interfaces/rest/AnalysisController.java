package com.wolfmind.interfaces.rest;

import com.wolfmind.application.analysis.AgentAnalysis;
import com.wolfmind.application.analysis.AnalysisQueryService;
import com.wolfmind.application.analysis.AnalysisSummary;
import com.wolfmind.domain.game.model.ViewerContext;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * AI 分析接口（api-spec.md §7）。
 *
 * <p><b>必须登录</b>（api-spec.md §10：{@code /analysis} 匿名不可用）：分析页是"赛后复盘"，
 * 匿名开放等于把整局的角色与决策轨迹公开。这条判定在接口层，因为它关心的是**调用者身份**，
 * 而不是对局规则。
 *
 * <p><b>披露分层由服务端决定，不由这个类决定。</b>本类只把 {@code viewerUserId} 交给用例，
 * 由用例按对局状态给出 {@code FULL} / {@code PUBLIC}。控制器里再写一份"结束了就全给"的判断，
 * 就会在第二次改动时与用例漂移（AP-6 的同类问题）。
 */
@RestController
@RequestMapping(AnalysisController.BASE_PATH)
class AnalysisController {

    static final String BASE_PATH = "/api/v1";

    private final ObjectProvider<AnalysisQueryService> analysis;
    private final ViewerResolverPort viewerResolver;

    AnalysisController(ObjectProvider<AnalysisQueryService> analysis, ViewerResolverPort viewerResolver) {
        this.analysis = analysis;
        this.viewerResolver = viewerResolver;
    }

    @GetMapping("/games/{gameId}/analysis/summary")
    AnalysisSummary summary(@PathVariable String gameId, HttpServletRequest request) {
        return service().summary(gameId, viewerUserId(request));
    }

    @GetMapping("/games/{gameId}/analysis/agents/{playerId}")
    AgentAnalysis agentAnalysis(
            @PathVariable String gameId,
            @PathVariable String playerId,
            HttpServletRequest request) {
        try {
            return service().agentAnalysis(gameId, viewerUserId(request), playerId);
        } catch (AnalysisQueryService.UnknownSeatException error) {
            // 404 而不是 403：座位不存在与"你没有权限"是两件事，客户端对它们的处理也不同
            // （前者应回到座位列表，后者应提示权限）。
            throw new NotFoundException(error.getMessage());
        }
    }

    private AnalysisQueryService service() {
        AnalysisQueryService service = analysis.getIfAvailable();
        if (service == null) {
            throw new NotWiredException("分析接口尚未接入：需要 AnalysisQueryService（api-spec.md 7）");
        }
        return service;
    }

    /** 取已认证账号；缺失即 401，不进应用层。 */
    private String viewerUserId(HttpServletRequest request) {
        ViewerContext viewer = viewerResolver.resolve(request);
        String userId = viewer.viewerUserId();
        if (userId == null || userId.isBlank()) {
            throw new UnauthorizedException("AI 分析需要登录（api-spec.md 10：/analysis 匿名不可用）");
        }
        return userId;
    }
}
