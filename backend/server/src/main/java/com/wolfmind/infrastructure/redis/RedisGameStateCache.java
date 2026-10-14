package com.wolfmind.infrastructure.redis;

import com.wolfmind.application.port.GameStateCachePort;
import com.wolfmind.domain.game.model.GameSnapshot;
import com.wolfmind.infrastructure.memory.GameSnapshotCodec;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import java.util.List;

/** 只读查询投影，版本只能前进，不参与领域规则判断。 */
public final class RedisGameStateCache implements GameStateCachePort {
    private final StringRedisTemplate redis;
    public RedisGameStateCache(StringRedisTemplate redis) { this.redis = redis; }
    @Override public void put(GameSnapshot state) {
        redis.execute(new DefaultRedisScript<>("""
                local current = tonumber(redis.call('HGET',KEYS[1],'version') or '0')
                if current > tonumber(ARGV[1]) then return 0 end
                redis.call('HSET',KEYS[1],'version',ARGV[1],'state',ARGV[2])
                local ttl = redis.call('TTL',KEYS[2])
                if ttl > 0 then redis.call('EXPIRE',KEYS[1],ttl) else redis.call('PERSIST',KEYS[1]) end
                return 1
                """, Long.class), List.of(RedisKeys.scope(state.gameId().value()) + ":state", RedisKeys.events(state.gameId().value())),
                Integer.toString(state.version()), GameSnapshotCodec.encode(state));
    }
    @Override public GameSnapshot get(String id) {
        Object encoded = redis.opsForHash().get(RedisKeys.scope(id) + ":state", "state");
        return encoded == null ? null : GameSnapshotCodec.decode(encoded.toString());
    }
    @Override public void remove(String id) { redis.delete(RedisKeys.scope(id) + ":state"); }
}
