package com.wolfmind.interfaces.rest;

import com.wolfmind.domain.game.model.ViewerContext;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * 匿名身份适配器：仅解析令牌形态，不校验会话，因此始终返回匿名视角。
 * 生产认证使用 {@link SessionViewerResolver}；本适配器保留给显式配置的匿名部署或测试。
 *
 * <p><b>为什么用 {@code @ConditionalOnProperty} 而不是 {@code @ConditionalOnMissingBean}
 * 或 {@code @Primary}</b>：
 * <ul>
 *   <li>{@code @ConditionalOnMissingBean} 的求值在自动配置阶段，而 {@code @TestConfiguration}
 *       的 Bean 注册得更晚，两者会同时留在容器里，注入点直接失败；</li>
 *   <li>两个实现都标 {@code @Primary} 会得到 "more than one 'primary' bean found"
 *       （实测踩到过）。</li>
 * </ul>
 * 属性开关是确定性的、与注册顺序无关：{@code wolfmind.auth.viewer-resolver=none} 时
 * 这个兜底实现不注册，容器里只剩调用方提供的那个。生产默认配置已使用会话解析器。
 *
 * <p>未接入时**刻意不"猜"用户**：猜错的代价是把私有信息发给错误的人，
 * 而不可用的代价只是"功能还没上线"。
 */
@Component
@ConditionalOnProperty(
        name = "wolfmind.auth.viewer-resolver",
        havingValue = "anonymous")
class BearerViewerResolver implements ViewerResolverPort {

    @Override
    public ViewerContext resolve(HttpServletRequest request) {
        String token = bearerToken(request == null ? null : request.getHeader("Authorization"));
        if (token == null || token.isBlank()) {
            return ViewerContext.anonymous();
        }
        // 会话存储属于 Phase 4 的 /auth/* 工作；未接入前一律按匿名处理。
        return ViewerContext.anonymous();
    }

    /** 解析 "Bearer xxx"；形态不合法时返回 null 而不是抛异常，交由调用方决定拒绝还是降级。 */
    static String bearerToken(String authorizationHeader) {
        String prefix = "Bearer ";
        if (authorizationHeader == null
                || authorizationHeader.length() <= prefix.length()
                || !authorizationHeader.regionMatches(true, 0, prefix, 0, prefix.length())) {
            return null;
        }
        return authorizationHeader.substring(prefix.length()).trim();
    }
}
