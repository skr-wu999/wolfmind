package com.wolfmind.interfaces.rest;

import com.wolfmind.application.user.AuthApplicationService;
import com.wolfmind.domain.game.model.DomainErrorCode;
import com.wolfmind.domain.game.model.GameException;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 认证接口（api-spec.md §3）。
 *
 * <p><b>响应里永不出现口令或口令散列</b>；登录响应是唯一一次下发明文令牌的地方。
 * 令牌同时用于 REST 的 {@code Authorization: Bearer} 与 WebSocket 的 {@code AUTH} 帧
 * （websocket-protocol.md §3.1）——两者共用同一份会话，因此登出会同时让长连接失效。
 */
@RestController
@RequestMapping(AuthController.BASE_PATH)
class AuthController {

    static final String BASE_PATH = "/api/v1";

    private final ObjectProvider<AuthApplicationService> auth;
    private final ViewerResolverPort viewerResolver;

    AuthController(ObjectProvider<AuthApplicationService> auth, ViewerResolverPort viewerResolver) {
        this.auth = auth;
        this.viewerResolver = viewerResolver;
    }

    @PostMapping("/auth/register")
    ResponseEntity<Map<String, Object>> register(@RequestBody Map<String, Object> body) {
        AuthApplicationService service = service();
        AuthApplicationService.RegisteredUser user = service.register(
                text(body, "username"), text(body, "password"), optionalText(body, "displayName"));

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("userId", user.userId());
        response.put("username", user.username());
        response.put("displayName", user.displayName());
        // api-spec.md §3.1：注册响应**不含**令牌——注册与登录是两步，
        // 让注册顺带下发会话会让"注册即可用"掩盖掉"口令是否真的能登录"这个问题。
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @PostMapping("/auth/login")
    Map<String, Object> login(@RequestBody Map<String, Object> body) {
        AuthApplicationService service = service();
        AuthApplicationService.LoggedIn loggedIn =
                service.login(text(body, "username"), text(body, "password"));

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("userId", loggedIn.userId());
        response.put("sessionToken", loggedIn.sessionToken());
        response.put("expiresAt", loggedIn.expiresAt());
        return response;
    }

    @PostMapping("/auth/logout")
    ResponseEntity<Void> logout(HttpServletRequest request) {
        AuthApplicationService service = service();
        service.logout(bearerToken(request));
        // api-spec.md §3：登出成功返回 204。重复登出也返回 204（幂等）。
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/auth/me")
    Map<String, Object> me(HttpServletRequest request) {
        AuthApplicationService service = service();
        AuthApplicationService.CurrentUser current = service.currentUser(bearerToken(request));

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("userId", current.userId());
        response.put("username", current.username());
        response.put("displayName", current.displayName());
        response.put("activeGameId", current.activeGameId());
        response.put("spectatingGameIds", current.spectatingGameIds());
        return response;
    }

    // ---------------------------------------------------------------- 辅助

    private AuthApplicationService service() {
        AuthApplicationService service = auth.getIfAvailable();
        if (service == null) {
            throw new NotWiredException("认证尚未接入：需要 AuthApplicationService（api-spec.md 3）");
        }
        return service;
    }

    /** 取 Bearer 令牌；缺失直接 401，不进应用层。 */
    private static String bearerToken(HttpServletRequest request) {
        String raw = request == null ? null : request.getHeader("Authorization");
        String prefix = "Bearer ";
        if (raw == null || raw.length() <= prefix.length()
                || !raw.regionMatches(true, 0, prefix, 0, prefix.length())) {
            throw new UnauthorizedException("需要登录：Authorization: Bearer <sessionToken>");
        }
        String token = raw.substring(prefix.length()).trim();
        if (token.isBlank()) {
            throw new UnauthorizedException("需要登录：Authorization: Bearer <sessionToken>");
        }
        return token;
    }

    private static String text(Map<String, Object> body, String field) {
        Object value = body == null ? null : body.get(field);
        if (value instanceof String string && !string.isBlank()) {
            return string;
        }
        throw new RequestShapeException("请求体缺少必填字段 " + field + "（字符串）");
    }

    private static String optionalText(Map<String, Object> body, String field) {
        Object value = body == null ? null : body.get(field);
        return value instanceof String string && !string.isBlank() ? string : null;
    }
}