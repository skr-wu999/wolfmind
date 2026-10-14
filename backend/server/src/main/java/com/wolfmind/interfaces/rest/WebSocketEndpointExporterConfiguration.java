package com.wolfmind.interfaces.rest;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.server.standard.ServerEndpointExporter;

/**
 * 注册带 {@code @ServerEndpoint} 的 WebSocket 端点。
 *
 * <p><b>为什么这个只有 5 行的类在 {@code interfaces.rest}</b>：{@code ServerEndpointExporter}
 * 属于 {@code org.springframework.web.*}，而 DEP-4（ddd-design.md §5.3）规定 HTTP/Web 类型
 * **只能**出现在 {@code interfaces.rest}。架构测试确实拦住了把它放进
 * {@code infrastructure.ws} 的写法。
 *
 * <p><b>为什么必须有它</b>：内嵌 Tomcat 不做 {@code ServletContainerInitializer} 发现，
 * 因此 Tomcat 自带的 {@code WsSci} 不会自动运行，而它负责注册 HTTP 升级用的
 * {@code WsFilter}。缺了它，{@code /ws} 的升级请求会一路走到 {@code DispatcherServlet}
 * 并返回 404——**即使端点已经注册成功**。实测踩过这个坑：早期为省一个依赖而自己反射调
 * {@code WsSci.init}，拿到了容器、端点注册成功，但连接照样连不上。
 *
 * <p>{@code ServerEndpointExporter} 在正确的时机做这件事。端点类本身
 * （{@code WebSocketEndpoint}，{@code @ServerEndpoint} + {@code @Component}）在
 * {@code infrastructure.ws}——那是传输管道的归属。
 */
@Configuration
@ConditionalOnProperty(name = "wolfmind.ws.enabled", havingValue = "true", matchIfMissing = true)
class WebSocketEndpointExporterConfiguration {

    @Bean
    ServerEndpointExporter serverEndpointExporter() {
        return new ServerEndpointExporter();
    }
}