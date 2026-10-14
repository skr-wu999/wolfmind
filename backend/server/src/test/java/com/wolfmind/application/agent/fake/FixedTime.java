package com.wolfmind.application.agent.fake;

import com.wolfmind.application.port.TimePort;

/** 固定时钟：让回合 deadline 与轨迹时间戳在测试里可复现。 */
public final class FixedTime implements TimePort {

    private long now;

    public FixedTime(long now) {
        this.now = now;
    }

    public FixedTime advance(long millis) {
        this.now += millis;
        return this;
    }

    @Override
    public long nowMillis() {
        return now;
    }
}
