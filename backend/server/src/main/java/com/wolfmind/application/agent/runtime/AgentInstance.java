package com.wolfmind.application.agent.runtime;

import com.wolfmind.application.agent.AgentRuntime;
import com.wolfmind.application.agent.model.AgentConfig;
import com.wolfmind.application.agent.model.TurnRequest;
import com.wolfmind.application.agent.model.TurnResult;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 一个 AI 座位的实例（agent-runtime.md §2）。
 *
 * <p>它持有一个座位的配置与执行上下文，并保证**单线程执行**：同一实例并发调用
 * {@code executeTurn} 时，后到者被拒绝而不是排队——因为"同一 gameId 同时只有一个回合窗口"
 * （§11），并发意味着上游派发出了重复事件，此时应当由幂等键收敛，而不是让两个推理同时跑。
 *
 * <p>{@code alive} 标志实现"destroyAgent 后不再响应 TURN_GRANTED"（§14 生命周期一行）。
 */
public final class AgentInstance {

    private final AgentRuntime.AgentHandle handle;
    private final AgentConfig config;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private volatile boolean alive = true;

    public AgentInstance(AgentRuntime.AgentHandle handle, AgentConfig config) {
        this.handle = handle;
        this.config = config;
    }

    public AgentRuntime.AgentHandle handle() {
        return handle;
    }

    public AgentConfig config() {
        return config;
    }

    public boolean alive() {
        return alive;
    }

    /** 释放实例：之后不再接受回合。 */
    public synchronized void destroy() {
        alive = false;
    }

    /**
     * 执行一个回合。
     *
     * @param request 回合请求
     * @param executor 实际的执行体（由 Runtime 提供，内部是 {@link AgentWorkflow}）
     * @return 回合结果；实例已释放或并发调用时返回 {@code FAILED} 且不产生任何事件
     */
    public TurnResult execute(TurnRequest request, TurnExecutor executor) {
        synchronized (this) {
            if (!alive) {
                return failed(request, "AGENT_DESTROYED");
            }
            if (!running.compareAndSet(false, true)) {
                // 同一实例的并发回合：拒绝后到者。幂等键保证即使上游重放也不会产生第二条行动事件。
                return failed(request, "AGENT_BUSY");
            }
        }
        try {
            return executor.execute(this, request);
        } finally {
            running.set(false);
        }
    }

    private TurnResult failed(TurnRequest request, String errorCode) {
        return new TurnResult(
                TurnResult.Status.FAILED,
                request.gameId(),
                request.playerId(),
                null,
                java.util.List.of(),
                0,
                java.util.List.of(),
                null,
                java.util.List.of(),
                0,
                0,
                0,
                0L,
                false,
                errorCode);
    }

    /** 执行体：由 Runtime 注入，便于把实例状态与工作流解耦。 */
    @FunctionalInterface
    public interface TurnExecutor {
        TurnResult execute(AgentInstance instance, TurnRequest request);
    }
}
