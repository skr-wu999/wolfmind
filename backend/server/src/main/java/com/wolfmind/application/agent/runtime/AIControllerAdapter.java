package com.wolfmind.application.agent.runtime;

import com.wolfmind.application.agent.AgentRuntime;
import com.wolfmind.application.agent.model.AgentConfig;
import com.wolfmind.application.agent.model.AgentView;
import com.wolfmind.application.agent.model.TurnRequest;
import com.wolfmind.application.agent.model.TurnResult;
import com.wolfmind.application.agent.model.TurnSignal;
import com.wolfmind.application.agent.tool.ToolGatewayPort;
import com.wolfmind.application.agent.trace.AgentTracePort;
import com.wolfmind.application.agent.view.AgentViewQueryPort;
import com.wolfmind.application.port.TimePort;

import java.util.Map;

/**
 * 回合派发适配器（agent-runtime.md §4 的 {@code AIControllerAdapter}）。
 *
 * <p>它接收调度器从已提交窗口构造的 {@code TurnSignal}，决定是否执行 Agent：
 * <ol>
 *   <li>读座位控制者类型：{@code HUMAN} 忽略（等真人提交）；</li>
 *   <li>{@code AI} / {@code BOT} → 装配 {@link TurnRequest} 并调 {@link AgentRuntime#executeTurn}；</li>
 *   <li>座位没有实例时按"AI 补位"处理：惰性创建实例（{@code PLAYER_CONTROLLER_CHANGED} 场景）。</li>
 * </ol>
 *
 * <p>资格来自领域的回合/阶段事件，调度器只派发当前仍有效的窗口，因此
 * <ul>
 *   <li>AI 无法通过任何工具改变"轮到谁"；</li>
 *   <li>同一 {@code gameId} 同时只有一个回合窗口，AI 回合天然串行；{@code DAY_VOTE} 例外，
 *       驱动并发派发各 AI 座位（agent-runtime.md §4.1）；</li>
 *   <li>AI 卡死不会让对局停下：{@code deadline} 到期由超时看护提交 {@code SkipAction}。</li>
 * </ul>
 */
public final class AIControllerAdapter {

    private final AgentRuntime runtime;
    private final AgentViewQueryPort views;
    private final ToolGatewayPort gateway;
    private final TimePort time;
    private final AgentConfig defaultConfig;

    public AIControllerAdapter(
            AgentRuntime runtime,
            AgentViewQueryPort views,
            ToolGatewayPort gateway,
            TimePort time,
            AgentConfig defaultConfig) {
        this.runtime = runtime;
        this.views = views;
        this.gateway = gateway;
        this.time = time;
        this.defaultConfig = defaultConfig;
    }

    /**
     * 派发一个回合。
     *
     * @param signal  由 {@code TURN_GRANTED} 映射而来
     * @param configOf 该座位的配置解析函数（实验室按座位指定配置）；返回 {@code null} 时依次用
     *                 该座位实例登记的配置（开局时按房间与模型设置创建）、默认配置
     * @return 回合结果；不派发时返回 {@code null}
     */
    public TurnResult dispatch(TurnSignal signal, java.util.function.Function<Integer, AgentConfig> configOf) {
        if (!signal.drivesAgent()) {
            // 真人座位：不派发。这里返回 null 而不是 FAILED，因为"不派发"是正常路径。
            return null;
        }
        AgentConfig config = configOf == null ? null : configOf.apply(signal.seatNo());
        if (config == null) {
            config = runtime.configOf(signal.gameId(), signal.playerId()).orElse(defaultConfig);
        }
        AgentView view = views.viewFor(signal.gameId(), signal.playerId());
        if (view == null || view.me() == null) {
            return failed(signal, "VIEW_UNAVAILABLE");
        }
        if (!"RUNNING".equals(view.status())) return failed(signal, "GAME_CLOSED");
        if ("HUMAN".equals(view.me().controllerType())) return null;
        // 惰性创建：支持"真人掉线、AI 补位"（agent-runtime.md §3 最后一行）
        runtime.createAgent(new AgentRuntime.CreateAgentCommand(
                signal.gameId(), signal.playerId(), signal.seatNo(), config));

        TurnRequest request = new TurnRequest(
                signal.gameId(),
                signal.playerId(),
                signal.seatNo(),
                view.me().role(),
                view.me().camp(),
                signal.phase(),
                view.round(),
                signal.turnIndex(),
                signal.version(),
                signal.deadline(),
                view,
                gateway.availableTools(view.me().role(), signal.phase()),
                config.budget(),
                config, signal.fence());
        return runtime.executeTurn(request);
    }

    private TurnResult failed(TurnSignal signal, String errorCode) {
        return new TurnResult(
                TurnResult.Status.FAILED, signal.gameId(), signal.playerId(), null,
                java.util.List.of(), signal.version(), java.util.List.of(), null,
                java.util.List.of(), 0, 0, 0, 0L, false, errorCode);
    }

    /**
     * 对局结束或座位被接管时的清理。
     *
     * <p>{@code GAME_FINISHED} / {@code GAME_ABORTED} → 释放整局；
     * {@code PLAYER_CONTROLLER_CHANGED} 转 {@code HUMAN} → 只释放该座位。
     */
    public void onGameFinished(String gameId) {
        runtime.destroyGame(gameId);
    }

    public void onSeatTakenOverByHuman(String gameId, String playerId) {
        runtime.destroyAgent(new AgentRuntime.AgentHandle(gameId, playerId, 0, ""));
    }

    /** 便于接线层与测试：把轨迹类型常量集中一处，避免字符串拼写错误。 */
    public static Map<String, String> traceContract() {
        return Map.of(
                "turnStarted", AgentTracePort.TraceEventType.AGENT_TURN_STARTED.name(),
                "llmInvoked", AgentTracePort.TraceEventType.AGENT_LLM_INVOKED.name(),
                "toolCalled", AgentTracePort.TraceEventType.AGENT_TOOL_CALLED.name(),
                "turnFinished", AgentTracePort.TraceEventType.AGENT_TURN_FINISHED.name());
    }

    /** 便于测试断言"未派发"的语义。 */
    public boolean shouldDispatch(TurnSignal signal) {
        return signal.drivesAgent();
    }

    /** 当前时间来源，供测试替换。 */
    public TimePort time() {
        return time;
    }
}
