package com.wolfmind.infrastructure.ws;

import com.wolfmind.interfaces.ws.WebSocketGateway;

import jakarta.websocket.CloseReason;
import jakarta.websocket.OnClose;
import jakarta.websocket.OnError;
import jakarta.websocket.OnMessage;
import jakarta.websocket.OnOpen;
import jakarta.websocket.Session;
import jakarta.websocket.server.ServerEndpoint;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.Map;

/**
 * WebSocket 端点 {@code /ws}（websocket-protocol.md §3）。
 *
 * <p><b>这个类刻意只做传输</b>：把 JSON 文本转给 {@link WebSocketGateway}，把网关给出的
 * 帧写回连接。协议语义（阶段、seq、补发、超时）全在 {@link SubscriptionState}，
 * 因此这里的每一行都不需要测试——需要测试的部分已经有 23 条单元测试。
 *
 * <p>依赖通过 {@code WebSocketGateway.holder()} 静态注入。原因是 {@code @ServerEndpoint} 的实例由容器
 * 反射创建，不是 Spring Bean，构造器注入不可用。静态持有是这种集成下最小的代价；
 * 网关本身无状态（状态在连接表与各连接的 {@code SubscriptionState} 里），
 * 因此这个静态引用不会引入共享可变状态。
 *
 * <p>发送用 {@code synchronized} 保护：{@code jakarta.websocket.RemoteEndpoint}
 * **不是线程安全的**。多个线程（心跳定时器、事件扇出、帧处理）可能同时写同一个连接，
 * 不加保护会交错出损坏的 JSON 帧——而症状是客户端偶发解析失败，很难复现。
 */
@ServerEndpoint(WebSocketEndpoint.PATH)
@Component
public class WebSocketEndpoint {

    /** 路径与 websocket-protocol.md §3 一致。 */
    public static final String PATH = "/ws";

    private static final Logger log = LoggerFactory.getLogger(WebSocketEndpoint.class);

    private final Object writeLock = new Object();
    private String connectionId;
    private Session session;

    @OnOpen
    public void onOpen(Session session) {
        this.session = session;
        this.connectionId = session.getId();
        WebSocketGateway gateway = WebSocketGateway.holder();
        if (gateway == null) {
            log.warn("WebSocket 网关未装配，连接 {} 将不会收到任何帧", connectionId);
            return;
        }
        gateway.onOpen(connectionId, new SessionSender());
    }

    @OnMessage
    public void onMessage(String text) {
        WebSocketGateway gateway = WebSocketGateway.holder();
        if (gateway == null) {
            return;
        }
        gateway.onText(connectionId, text);
    }

    @OnClose
    public void onClose() {
        WebSocketGateway gateway = WebSocketGateway.holder();
        if (gateway != null) {
            gateway.onClose(connectionId);
        }
    }

    @OnError
    public void onError(Throwable error) {
        // 单个连接出错不应影响其它连接，也不应让异常逃逸到容器的错误日志里刷屏。
        log.debug("WebSocket 连接 {} 出错", connectionId, error);
        WebSocketGateway gateway = WebSocketGateway.holder();
        if (gateway != null) {
            gateway.onClose(connectionId);
        }
    }

    /** 把网关给出的帧写到这条连接。 */
    private final class SessionSender implements WebSocketGateway.Sender {

        @Override
        public void send(String id, Map<String, Object> wire) {
            Session current = session;
            if (current == null || !current.isOpen()) {
                return;
            }
            String text = com.wolfmind.infrastructure.json.Json.write(wire);
            synchronized (writeLock) {
                try {
                    // 同步发送：异步发送在连接阻塞时会把帧堆在内存里，
                    // 而积压上限（§9）已经在协议层管住了这件事，传输层不必再排队。
                    current.getBasicRemote().sendText(text);
                } catch (IOException error) {
                    log.debug("发送失败，连接 {}", id, error);
                }
            }
        }

        @Override
        public void close(String id, int code, String reason) {
            Session current = session;
            if (current == null || !current.isOpen()) {
                return;
            }
            try {
                current.close(new CloseReason(
                        CloseReason.CloseCodes.getCloseCode(code), reason));
            } catch (IOException error) {
                log.debug("关闭失败，连接 {}", id, error);
            }
        }
    }
}