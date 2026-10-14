package com.wolfmind.domain.game.model;

/**
 * 行动上下文：承载所有非确定性来源（ddd-design.md §2.2）。
 *
 * <p>聚合内部禁止取时钟、随机数与 UUID，全部由应用层通过本记录注入；重放时不重新取值。
 * {@code randomSeed} 只用于开局等需要随机的场景，随机结果必须写入事件 payload。
 */
public record ActionContext(
        String eventId,
        long occurredAt,
        String actorId,
        String commandId,
        String correlationId,
        int expectedVersion,
        long randomSeed) {

    /** 系统触发的 actorId（超时看护、领域自动结算、恢复流程）。 */
    public static final String SYSTEM_ACTOR = "SYSTEM";

    /** 不校验期望版本（重放与测试路径）。 */
    public static final int NO_VERSION_CHECK = -1;

    public ActionContext {
        if (eventId == null || eventId.isBlank()) {
            throw new IllegalArgumentException("ActionContext.eventId 不能为空");
        }
        if (actorId == null || actorId.isBlank()) {
            throw new IllegalArgumentException("ActionContext.actorId 不能为空");
        }
        if (commandId == null) {
            commandId = eventId;
        }
        if (correlationId == null) {
            correlationId = commandId;
        }
    }

    /** 命令路径：必须给出期望版本。 */
    public static ActionContext of(
            String eventId,
            long occurredAt,
            String actorId,
            String commandId,
            String correlationId,
            int expectedVersion) {
        return new ActionContext(
                eventId, occurredAt, actorId, commandId, correlationId, expectedVersion, 0L);
    }

    /** 测试与内存模拟路径：不做版本前置校验。 */
    public static ActionContext forTest(String eventId, long occurredAt, String actorId) {
        return new ActionContext(
                eventId, occurredAt, actorId, eventId, eventId, NO_VERSION_CHECK, 0L);
    }

    /** 系统路径：超时看护与领域自动结算。 */
    public static ActionContext system(String eventId, long occurredAt) {
        return new ActionContext(
                eventId, occurredAt, SYSTEM_ACTOR, eventId, eventId, NO_VERSION_CHECK, 0L);
    }

    public ActionContext withRandomSeed(long seed) {
        return new ActionContext(
                eventId, occurredAt, actorId, commandId, correlationId, expectedVersion, seed);
    }

    public ActionContext withActor(String newActorId) {
        return new ActionContext(
                eventId, occurredAt, newActorId, commandId, correlationId, expectedVersion, randomSeed);
    }

    public ActionContext withOccurredAt(long newOccurredAt) {
        return new ActionContext(
                eventId, newOccurredAt, actorId, commandId, correlationId, expectedVersion, randomSeed);
    }

    /** 是否为系统触发。 */
    public boolean isSystem() {
        return SYSTEM_ACTOR.equals(actorId);
    }

    /** 是否要求版本前置校验（INV-11 的乐观并发）。 */
    public boolean requiresVersionCheck() {
        return expectedVersion != NO_VERSION_CHECK;
    }

    /**
     * 派生一条新事件的 id：{@code gameId:version} 形式，可排序且不依赖随机数或时钟。
     *
     * <p>应用层也可以在持久化时用 {@code version} 直接生成，这里提供的默认值是确定性的。
     */
    public String eventIdFor(int version) {
        return eventId + "-" + version;
    }
}
