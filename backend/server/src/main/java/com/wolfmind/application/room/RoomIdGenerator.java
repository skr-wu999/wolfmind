package com.wolfmind.application.room;

import com.wolfmind.application.port.TimePort;

import java.util.concurrent.atomic.AtomicLong;

/**
 * 房间号生成器。
 *
 * <p>房间号是**不透明字符串**（api-spec.md 1），客户端不得解析。当前形态
 * {@code r_<时间戳><序列>} 只保证"同一进程内不重复、可读、可排序"。
 *
 * <p>为什么不用 UUID：房间号会出现在 URL 与用户可见的界面里，短且可读的价值高于极高熵。
 * 多实例部署时这里要换成集中式发号（Redis INCR 或数据库序列），已记为 Phase 5 工作。
 */
public final class RoomIdGenerator {

    private final AtomicLong sequence = new AtomicLong();

    /** 生成房间号；同一实例内单调递增。 */
    public String next(TimePort time) {
        long stamp = time.nowMillis();
        long seq = sequence.incrementAndGet();
        return "r_" + Long.toString(stamp, 36) + "_" + Long.toString(seq, 36);
    }
}