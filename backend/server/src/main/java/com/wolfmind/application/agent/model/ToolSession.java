package com.wolfmind.application.agent.model;

/**
 * 工具会话：把"谁在什么身份下调用工具"固定下来（tool-gateway.md §4 G3）。
 *
 * <p><b>身份由 Runtime 注入，模型不可指定。</b>这是"AI 无法操作别的座位"在协议层的保证：
 * 模型即使在自己的输出里写了 {@code playerId}，也会被忽略或判为身份不匹配。
 *
 * <p>{@code idempotencyKeyPrefix} 与 {@code expectedVersion} 同样由 Runtime 提供：
 * 前者绑定回合版本（{@code tool:{gameId}:{version}:{playerId}:{toolName}}），
 * 后者取自工具会话开始时的视图版本。二者都不进 prompt。
 *
 * @param gameId          对局标识
 * @param playerId        本座位玩家 id
 * @param seatNo          本座位号
 * @param role            本座位角色（字符串形式）
 * @param phase           会话开始时的阶段
 * @param turnVersion     会话开始时的对局版本；用于构造幂等键与 expectedVersion
 * @param deadline        提交截止时刻（毫秒时间戳）
 * @param remainingToolCalls 本回合剩余工具调用次数
 */
public record ToolSession(
        String gameId,
        String playerId,
        int seatNo,
        String role,
        String phase,
        int turnVersion,
        long deadline,
        int remainingToolCalls,
        int expectedRound,
        com.wolfmind.application.scheduling.ExecutionFence fence) {

    public ToolSession(String gameId, String playerId, int seatNo, String role, String phase,
                       int turnVersion, long deadline, int remainingToolCalls, int expectedRound) {
        this(gameId, playerId, seatNo, role, phase, turnVersion, deadline, remainingToolCalls, expectedRound, null);
    }

    public ToolSession(String gameId, String playerId, int seatNo, String role, String phase,
                       int turnVersion, long deadline, int remainingToolCalls) {
        this(gameId, playerId, seatNo, role, phase, turnVersion, deadline, remainingToolCalls, -1);
    }

    public ToolSession {
        if (gameId == null || gameId.isBlank()) {
            throw new IllegalArgumentException("gameId 不能为空");
        }
        if (playerId == null || playerId.isBlank()) {
            throw new IllegalArgumentException("playerId 不能为空，身份必须由 Runtime 注入");
        }
        if (seatNo < 1) {
            throw new IllegalArgumentException("seatNo 必须 ≥ 1");
        }
        if (remainingToolCalls < 0) {
            throw new IllegalArgumentException("remainingToolCalls 不能为负");
        }
    }

    /**
     * 幂等键（agent-runtime.md §9、tool-gateway.md §6）。
     *
     * <p>含 {@code $version} 是关键：同一回合重复执行（进程重启、事件重放）收敛为同一个键，
     * 因此不会产生第二条行动事件；而下一个回合天然是新键。
     */
    public String idempotencyKey(String toolName) {
        if (fence != null) return "task:" + fence.taskId();
        return "tool:" + gameId + ":" + turnVersion + ":" + playerId + ":" + toolName;
    }

    /** 扣减一次工具调用额度。 */
    public ToolSession consumeToolCall() {
        return new ToolSession(
                gameId, playerId, seatNo, role, phase, turnVersion, deadline, remainingToolCalls - 1, expectedRound, fence);
    }

    public boolean budgetExhausted() {
        return remainingToolCalls <= 0;
    }

    public TurnKey turnKey() {
        return new TurnKey(gameId, playerId, turnVersion);
    }
}
