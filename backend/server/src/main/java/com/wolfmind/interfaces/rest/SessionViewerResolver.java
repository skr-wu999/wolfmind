package com.wolfmind.interfaces.rest;

import com.wolfmind.application.user.AuthApplicationService;
import com.wolfmind.application.user.SessionPort;
import com.wolfmind.domain.game.model.ViewerContext;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.Set;

/**
 * 以会话令牌解析身份的 {@link ViewerResolverPort} 实现（Phase 4）。
 *
 * <p>它替换认证未落地时的匿名兜底：请求带有效令牌 → 身份为该账号；
 * 令牌缺失或无效 → 仍回落为**匿名**（只读接口因此只返回 PUBLIC 内容）。
 *
 * <p><b>为什么不在这里 401</b>：只读接口允许匿名访问（观战、回放），把"没有令牌"当成错误
 * 会让它们全部失效。需要登录的接口（房间写操作、提交行动）自己在控制器里检查
 * {@code viewerUserId} 是否为空并返回 401——这样"哪些接口需要登录"这个决定只写在
 * 需要它的地方，而不是散在一个全局解析器里。
 *
 * <p><b>令牌无效时不失败、只降级为匿名</b>是刻意的：一个过期令牌的请求应该看到公开内容，
 * 而不是收到 401 后让前端立刻把用户登出。前端会在真正需要登录的接口上得到 401，那时才清会话。
 *
 * <p>这里只解析账号。GameController 与 WebSocket 订阅再调用
 * {@code GameViewQueryPort.viewerContextFor}，由服务端反查该账号控制的座位。
 */
@Component
@ConditionalOnProperty(
        name = "wolfmind.auth.viewer-resolver",
        havingValue = "session",
        matchIfMissing = true)
class SessionViewerResolver implements ViewerResolverPort {

    private final AuthApplicationService auth;

    SessionViewerResolver(AuthApplicationService auth) {
        this.auth = auth;
    }

    @Override
    public ViewerContext resolve(HttpServletRequest request) {
        return identified(request).orElseGet(ViewerContext::anonymous);
    }

    /**
     * 解析已认证身份。
     *
     * <p>返回 {@code Optional} 而不是抛异常：调用方需要区分"没有身份"与"有身份"，
     * 而"没有身份"是常态（匿名浏览）。
     */
    Optional<ViewerContext> identified(HttpServletRequest request) {
        String token = bearerToken(request == null ? null : request.getHeader("Authorization"));
        if (token == null) {
            return Optional.empty();
        }
        Optional<SessionPort.Verified> verified = auth.verifySession(token);
        if (verified.isEmpty()) {
            return Optional.empty();
        }
        // 只填 viewerUserId：可见性判定的其余输入（参与者座位、狼队、观战标记）
        // 必须由服务端按参与者关系反查，不能在这里凭空构造。
        return Optional.of(ViewerContext.participant(
                verified.get().userId(), Set.of(), Set.of(), false));
    }

    /** 解析 "Bearer xxx"；形态不合法返回 null。 */
    static String bearerToken(String authorizationHeader) {
        String prefix = "Bearer ";
        if (authorizationHeader == null
                || authorizationHeader.length() <= prefix.length()
                || !authorizationHeader.regionMatches(true, 0, prefix, 0, prefix.length())) {
            return null;
        }
        String token = authorizationHeader.substring(prefix.length()).trim();
        return token.isBlank() ? null : token;
    }
}
