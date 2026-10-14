package com.wolfmind.infrastructure.ws;

import com.wolfmind.application.port.GameViewQueryPort;
import com.wolfmind.application.user.AuthApplicationService;
import com.wolfmind.interfaces.ws.WebSocketGateway;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * WebSocket 装配（Phase 4）。
 *
 * <p><b>为什么必须用 {@link ServerEndpointExporter} 而不是自己注册端点</b>：
 * 内嵌 Tomcat **不做 {@code ServletContainerInitializer} 发现**，因此 Tomcat 自带的
 * {@code WsSci} 不会自动运行，而它负责两件事——设置 {@code ServerContainer} 属性，
 * 以及注册负责 HTTP 升级的 {@code WsFilter}。缺了后者，{@code /ws} 的升级请求会一路走到
 * {@code DispatcherServlet} 并返回 404，**即使 {@code addEndpoint} 已经成功**。
 * 这个失败模式很有误导性：注册成功与能连上是两件事。
 *
 * <p>{@code ServerEndpointExporter} 在正确的时机做这两件事，因此它是这条通道能工作的前提，
 * 而不是"为了少写代码"而引入的便利。
 *
 * <p>整个装配受 {@code wolfmind.ws.enabled} 控制（默认开启）：关闭时端点不注册，
 * 只关心 REST 的上下文不必启动 WebSocket 栈。
 */
@Configuration
@ConditionalOnProperty(name = "wolfmind.ws.enabled", havingValue = "true", matchIfMissing = true)
public class WebSocketConfiguration {

    /**
     * 协议网关。
     *
     * <p>它是 {@code interfaces.ws} 的组件（协议与语义），但作为 Bean 在这里注册：
     * 装配是基础设施侧的事。网关本身不依赖任何 WebSocket API，因此放在哪一层注册
     * 都不影响它的可测性。
     */
    @Bean
    WebSocketGateway webSocketGateway(AuthApplicationService auth, GameViewQueryPort gameViews,
            com.wolfmind.application.room.GetRoomQueryService rooms,
            com.fasterxml.jackson.databind.ObjectMapper mapper) {
        WebSocketGateway gateway = new WebSocketGateway(auth, gameViews, rooms, mapper);
        // 端点实例由容器反射创建，不是 Spring Bean，因此经静态持有注入。
        WebSocketGateway.holder(gateway);
        return gateway;
    }

    /**
     * 把网关注册为实时事件的监听者。
     *
     * <p>不新建第二个 {@code EventPublisherPort} Bean：那会让"已提交事件的唯一发布点"
     * 变成两个（AGENTS.md §7 要求扇出只发生在提交成功之后），并且与
     * {@code MemoryStorageConfiguration#eventPublisherPort} 撞名。监听者模式保住了单一发布点。
     */
    @Bean
    org.springframework.beans.factory.InitializingBean webSocketFanout(
            WebSocketGateway gateway,
            com.wolfmind.infrastructure.memory.InMemoryEventPublisher eventPublisher) {
        return () -> eventPublisher.addRealtimeListener(gateway::onCommittedEvents);
    }

    /** AI 发言流式预览的出口；WebSocket 关闭时 Agent 侧退回非流式调用。 */
    @Bean
    com.wolfmind.application.agent.speech.SpeechPreviewPort speechPreviewPort(WebSocketGateway gateway) {
        return new com.wolfmind.interfaces.ws.WebSocketSpeechPreview(gateway);
    }

    /**
     * 心跳看护：周期性扫描并关闭超时连接（websocket-protocol.md §3.3）。
     *
     * <p><b>为什么不用 {@code @Scheduled}</b>：那需要 {@code @EnableScheduling}，
     * 而它会为整个应用开一个调度线程池——为了一个每 5 秒的任务引入全局调度基础设施
     * 不划算，也让"这个任务活着吗"更难回答。一个具名的单线程池 +
     * {@code destroyMethod = "shutdown"} 生命周期明确。
     *
     * <p><b>为什么受 {@code wolfmind.ws.heartbeat.enabled} 控制（默认开启）</b>：
     * 契约测试会自己调 {@code sweepTimeouts()} 并断言结果。若看护线程同时也在跑，
     * 它可能先把连接关掉，测试就会变成时序竞态——"测试通过与否取决于线程调度"是最难查的一类。
     *
     * <p>扫描间隔取 5 秒：空闲阈值是 45 秒，因此最坏情况下连接会多活 5 秒被清理。
     * 更短的间隔只是让清理更及时，代价是更频繁的空扫——而空扫是遍历一个很小的 Map。
     */
    @Bean(destroyMethod = "shutdown")
    java.util.concurrent.ScheduledExecutorService webSocketHeartbeat(
            WebSocketGateway gateway,
            org.springframework.core.env.Environment environment) {
        boolean enabled = environment.getProperty(
                "wolfmind.ws.heartbeat.enabled", Boolean.class, true);
        java.util.concurrent.ScheduledExecutorService executor =
                java.util.concurrent.Executors.newSingleThreadScheduledExecutor(runnable -> {
                    Thread thread = new Thread(runnable, "wolfmind-ws-heartbeat");
                    // 守护线程：它不该阻止 JVM 退出。
                    thread.setDaemon(true);
                    return thread;
                });
        if (!enabled) {
            // 仍然返回一个可用的执行器（不为 null）：调用方不必处理 null，
            // 而"注册了这个 Bean 但什么都不扫"比"没有这个 Bean"更容易在诊断时看清。
            return executor;
        }
        executor.scheduleWithFixedDelay(() -> {
            try {
                gateway.sweepTimeouts();
            } catch (RuntimeException error) {
                // 看护失败不能杀掉调度：一次异常之后定时任务会被静默取消，
                // 而那时超时连接将再也无人清理——症状是连接数缓慢泄漏。
                org.slf4j.LoggerFactory.getLogger(WebSocketConfiguration.class)
                        .warn("心跳看护失败，将在下一轮重试", error);
            }
        }, 5, 5, java.util.concurrent.TimeUnit.SECONDS);
        return executor;
    }
}
