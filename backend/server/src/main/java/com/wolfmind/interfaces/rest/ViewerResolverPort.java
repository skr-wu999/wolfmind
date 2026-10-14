package com.wolfmind.interfaces.rest;

import com.wolfmind.domain.game.model.ViewerContext;
import jakarta.servlet.http.HttpServletRequest;

/**
 * 读取者身份解析端口：把一次 HTTP 请求映射成 {@link ViewerContext}。
 *
 * <p><b>为什么是端口而不是一个具体类</b>：可见性判定的输入必须只有一个来源
 * （event-design.md 4 硬规则 2），所以"从请求到身份"必须收在一处；但**认证实现**会变
 * （Phase 4 是会话令牌，将来可能是 OAuth），而控制器不应随之改动。
 * 做成 Bean 接口后，测试可以注入一个返回参与者的实现来验证鉴权之后的分支，
 * 不需要为了测试去伪造一个会话存储。
 *
 * <p>默认实现 {@link BearerViewerResolver} 在认证未落地时返回**匿名**——
 * 这是安全的一侧：所有只读接口只返回 PUBLIC 内容。
 *
 * <p>明确禁止：用查询参数（如 {@code ?spectator=true}）提升可见性。那等于把"我是观战者"
 * 的声明权交给客户端，隐藏信息会被直接读到。观战者身份必须来自服务端可信的会话与订阅记录。
 */
public interface ViewerResolverPort {

    ViewerContext resolve(HttpServletRequest request);
}