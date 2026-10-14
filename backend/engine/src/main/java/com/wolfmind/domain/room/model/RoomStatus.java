package com.wolfmind.domain.room.model;

/**
 * 房间状态（redis-schema.md `room:{id}:meta.status`）。
 *
 * <p>三态是**互斥且单向**的：{@code WAITING → RUNNING → CLOSED}。开局后不再回到 WAITING
 * （INV-R2 要求"全部就绪才能开局"，回到 WAITING 会让就绪语义失效）。
 */
public enum RoomStatus {
    /** 可入座、可准备、可改配置。 */
    WAITING,
    /** 对局已开始；座位冻结，不再接受入座与准备变更。 */
    RUNNING,
    /** 已关闭；不再接受任何变更。 */
    CLOSED
}