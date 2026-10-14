package com.wolfmind.application.fake;

import com.wolfmind.application.port.TimePort;

/** 假时钟：应用层是唯一的取时处，固定时间让事件 {@code occurredAt} 可断言。 */
public final class FakeTime implements TimePort {

    private final long now;

    public FakeTime(long now) {
        this.now = now;
    }

    @Override
    public long nowMillis() {
        return now;
    }
}
