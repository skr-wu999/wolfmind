package com.wolfmind.infrastructure.memory;

import com.wolfmind.application.port.TimePort;

import java.time.Clock;

/**
 * 系统时钟实现。
 *
 * <p>领域层禁止取当前时间（INV-16、DEP-2），时间只能由应用层通过本端口注入。本实现是唯一
 * 允许调用系统时钟的地方之一，另一个是测试用的固定时钟。
 *
 * <p>为什么引入 {@link Clock} 而不是直接调 {@code System.currentTimeMillis()}：
 * 时间相关的测试（锁 TTL、幂等键过期、回合超时）需要可控时间，直接调静态方法会让这些
 * 测试只能靠 {@code Thread.sleep} 等待，既慢又不稳定。
 */
public final class SystemTimePort implements TimePort {

    private final Clock clock;

    public SystemTimePort() {
        this(Clock.systemUTC());
    }

    public SystemTimePort(Clock clock) {
        if (clock == null) {
            throw new IllegalArgumentException("clock 不能为空");
        }
        this.clock = clock;
    }

    @Override
    public long nowMillis() {
        return clock.millis();
    }

    /** 暴露给需要同一时间基准的基础设施组件（锁、幂等键）复用，避免同一进程内出现两个时钟。 */
    public Clock clock() {
        return clock;
    }
}
