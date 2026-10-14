package com.wolfmind.application.agent.model;

/**
 * 回合信号：把领域的 {@code TURN_GRANTED} 事件"翻译"成 Agent 侧能用的形状。
 *
 * <p>为什么需要它：{@code application.agent} 不能引用 {@code domain.game.event} 或
 * {@code domain.game.model}（DEP-7），因此派发器不能直接消费领域事件。
 * 由 {@code infrastructure.ai} 的接线层把事件字段映射成这个记录，Agent 侧只认它。
 *
 * <p>字段与 {@code TURN_GRANTED} 的 payload 一一对应，因此没有信息丢失：
 * {@code playerId} / {@code seatNo} / {@code phase} / {@code turnIndex} / {@code deadline} /
 * {@code controllerType} 全部来自事件，而非 Runtime 推导——AI 不自行开始回合。
 *
 * @param gameId       对局
 * @param playerId     回合属于哪个座位
 * @param seatNo       座位号
 * @param phase        阶段（字符串形式）
 * @param turnIndex    回合序号
 * @param deadline     提交截止时刻（毫秒时间戳）
 * @param version      事件版本，即回合开始时的对局版本
 * @param controllerType 该座位的控制者类型；只有 {@code AI} / {@code BOT} 才派发给 Agent
 */
public record TurnSignal(
        String gameId,
        String playerId,
        int seatNo,
        String phase,
        int turnIndex,
        long deadline,
        int version,
        String controllerType,
        com.wolfmind.application.scheduling.ExecutionFence fence) {

    public TurnSignal(String gameId, String playerId, int seatNo, String phase, int turnIndex,
                      long deadline, int version, String controllerType) {
        this(gameId, playerId, seatNo, phase, turnIndex, deadline, version, controllerType, null);
    }

    public TurnSignal {
        if (gameId == null || playerId == null) {
            throw new IllegalArgumentException("TurnSignal 需要 gameId 与 playerId");
        }
        if (phase == null || phase.isBlank()) {
            throw new IllegalArgumentException("TurnSignal 需要 phase");
        }
    }

    /** 该回合是否应当由 Agent 处理：只有 AI 与 BOT 座位才派发，HUMAN 座位等真人提交。 */
    public boolean drivesAgent() {
        return "AI".equals(controllerType) || "BOT".equals(controllerType);
    }

    public boolean needsSeatLookup() {
        return "-".equals(controllerType) || controllerType == null || controllerType.isBlank();
    }

    public TurnKey turnKey() {
        return new TurnKey(gameId, playerId, version);
    }
}
