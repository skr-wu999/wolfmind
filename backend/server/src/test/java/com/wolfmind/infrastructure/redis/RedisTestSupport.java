package com.wolfmind.infrastructure.redis;

import org.junit.jupiter.api.Assumptions;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * Redis 集成测试的连接：固定使用 {@link #DATABASE} 号库，每个用例前 {@code FLUSHDB}，不触碰 0 号库。
 *
     * <p>Redis 不可达时逐项报告跳过（离线构建仍可运行其余用例）；设置 {@code -Dwolfmind.redis.required=true}
 * 时改为失败，用于必须拿到 Redis 证据的验收运行。地址取 {@code wolfmind.redis.host/port}，默认 localhost:6379。
 */
final class RedisTestSupport {

    static final int DATABASE = 15;
    private static volatile String unavailableReason;

    private RedisTestSupport() {}

    static String host() {
        return System.getProperty("wolfmind.redis.host", "localhost");
    }

    static int port() {
        return Integer.getInteger("wolfmind.redis.port", 6379);
    }

    /** Optional offline mode returns null; flush aborts each test rather than its class container. */
    static LettuceConnectionFactory connect() {
        RedisStandaloneConfiguration configuration = new RedisStandaloneConfiguration(host(), port());
        configuration.setDatabase(DATABASE);
        LettuceConnectionFactory factory = new LettuceConnectionFactory(configuration);
        factory.afterPropertiesSet();
        factory.start();
        try (var connection = factory.getConnection()) {
            connection.ping();
            unavailableReason = null;
        } catch (RuntimeException unavailable) {
            factory.destroy();
            if (Boolean.getBoolean("wolfmind.redis.required")) {
                throw new AssertionError("wolfmind.redis.required=true 但 Redis 不可达：" + host() + ":" + port(),
                        unavailable);
            }
            unavailableReason = "Redis 不可达（" + host() + ":" + port() + "），本项 Redis 集成测试未执行";
            System.err.println(unavailableReason + "；将逐项记录跳过，强制验收请使用 -Dwolfmind.redis.required=true");
            return null;
        }
        return factory;
    }

    static void flush(StringRedisTemplate redis) {
        if (redis == null) {
            if (unavailableReason == null) throw new AssertionError("Redis test fixture was not initialized");
            if (Boolean.getBoolean("wolfmind.redis.required")) throw new AssertionError(unavailableReason);
            Assumptions.abort(unavailableReason);
        }
        redis.execute(connection -> {
            connection.serverCommands().flushDb();
            return null;
        }, true);
    }
}
