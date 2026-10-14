package com.wolfmind.infrastructure.redis;

import com.wolfmind.application.port.EventStorePort;
import com.wolfmind.domain.game.event.EventEnvelope;
import com.wolfmind.domain.game.event.EventType;
import com.wolfmind.domain.game.model.GameException;
import com.wolfmind.domain.game.model.Visibility;
import com.wolfmind.infrastructure.json.Json;
import com.wolfmind.infrastructure.memory.GameIdCatalog;
import org.springframework.data.domain.Range;
import org.springframework.data.redis.connection.Limit;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.core.Cursor;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Redis Stream 事件存储（redis-schema.md §4）。
 *
 * <p>条目 ID 固定为 {@code {version}-0}，追加在一个 Lua 脚本里完成：先读流尾做版本前置校验，
 * 再逐条 {@code XADD}，最后刷新 TTL。脚本原子执行，因此一批事件要么全部写入，要么一条都不写；
 * 锁过期后的并发写者会在脚本里读到新流尾并得到 {@code GAME_VERSION_CONFLICT}。
 *
 * <p>与 redis-schema.md §4.4 的差异：幂等记录不在本脚本里写（{@code IdempotencyPort} 由应用服务
 * 单独记录，与内存实现一致，正确性由领域 V6 兜底）；{@code occurredAt} 由应用层给出，不维护
 * {@code game:{id}:lastTs}。
 */
public final class RedisEventStore implements EventStorePort, GameIdCatalog,
        com.wolfmind.application.port.ArchivedEventRestorePort {

    static final long GAME_RUNNING_TTL_SECONDS = 0L;
    static final long GAME_TERMINAL_TTL_SECONDS = 7L * 24 * 3600;
    static final long ROOM_OPEN_TTL_SECONDS = 24L * 3600;
    static final long ROOM_CLOSED_TTL_SECONDS = 7L * 24 * 3600;
    static final long USER_TTL_SECONDS = 365L * 24 * 3600;

    private static final int READ_PAGE = 500;

    private static final String[] FIELDS = {
            "eventId", "gameId", "version", "type", "schemaVersion", "occurredAt", "phase",
            "round", "actorId", "visibility", "causationId", "correlationId", "payload"};

    private static final String APPEND_LUA = """
            local stream = KEYS[1]
            local expected = tonumber(ARGV[1])
            local ttl = tonumber(ARGV[2])
            local n = tonumber(ARGV[3])
            local tail = redis.call('XREVRANGE', stream, '+', '-', 'COUNT', 1)
            local current = 0
            if #tail > 0 then
              current = tonumber(string.match(tail[1][1], '^(%d+)%-'))
            end
            if current ~= expected then
              return {'VERSION_CONFLICT', tostring(current)}
            end
            local names = {'eventId','gameId','version','type','schemaVersion','occurredAt','phase',
                           'round','actorId','visibility','causationId','correlationId','payload'}
            local cursor = 4
            for i = 0, n - 1 do
              local version = expected + 1 + i
              local args = {}
              for f = 1, 13 do
                args[#args + 1] = names[f]
                args[#args + 1] = ARGV[cursor + f - 1]
              end
              cursor = cursor + 13
              redis.call('XADD', stream, version .. '-0', unpack(args))
              if i == 0 and ttl > 0 then redis.call('EXPIRE', stream, ttl) end
            end
            if ttl > 0 then
              redis.call('EXPIRE', stream, ttl)
            else
              redis.call('PERSIST', stream)
              redis.call('PERSIST', KEYS[3])
              redis.call('PERSIST', KEYS[4])
              redis.call('PERSIST', KEYS[8])
              if redis.call('TYPE', KEYS[3]).ok == 'zset' then
                for _,key in ipairs(redis.call('ZRANGE', KEYS[3], 0, -1)) do
                  if string.sub(key, 1, string.len(KEYS[4]) + 1) == KEYS[4] .. ':' then
                    redis.call('PERSIST', key)
                  end
                end
              end
            end
            if ARGV[cursor] == '1' then
              redis.call('SADD', KEYS[2], ARGV[cursor+1])
              redis.call('SADD', KEYS[5], ARGV[cursor+1])
              redis.call('SADD', KEYS[6], ARGV[cursor+1])
              redis.call('SADD', KEYS[7], ARGV[cursor+1])
            elseif ARGV[cursor+2] == '1' then
              redis.call('SADD', KEYS[6], ARGV[cursor+1])
            end
            return {'APPENDED', tostring(expected + n)}
            """;

    @SuppressWarnings("rawtypes")
    private static final DefaultRedisScript<List> APPEND_SCRIPT = new DefaultRedisScript<>(APPEND_LUA, List.class);

    private static final DefaultRedisScript<Long> INSTALL_SCRIPT = new DefaultRedisScript<>("""
            if redis.call('EXISTS', KEYS[1]) == 1 then return 0 end
            if redis.call('TYPE', KEYS[2]).ok ~= 'stream'
                or redis.call('XLEN', KEYS[2]) ~= tonumber(ARGV[1]) then
              return redis.error_reply('INCOMPLETE_ARCHIVE_STAGE')
            end
            local tail = redis.call('XREVRANGE', KEYS[2], '+', '-', 'COUNT', 1)
            if tail[1][1] ~= ARGV[1] .. '-0' then return redis.error_reply('INVALID_ARCHIVE_TAIL') end
            local schedulerType = redis.call('TYPE', KEYS[5]).ok
            if schedulerType ~= 'none' and schedulerType ~= 'set' then
              return redis.error_reply('INVALID_SCHEDULER_CATALOG')
            end
            if redis.call('TYPE', KEYS[3]).ok == 'zset' then
              for _,key in ipairs(redis.call('ZRANGE', KEYS[3], 0, -1)) do
                if string.sub(key, 1, string.len(KEYS[4]) + 1) == KEYS[4] .. ':' then
                  redis.call('DEL', key)
                end
              end
            end
            redis.call('DEL', KEYS[3], KEYS[4], KEYS[7])
            redis.call('EXPIRE', KEYS[2], ARGV[2])
            redis.call('RENAME', KEYS[2], KEYS[1])
            redis.call('SREM', KEYS[5], ARGV[3])
            redis.call('SREM', KEYS[6], ARGV[3])
            return 1
            """, Long.class);

    private static final DefaultRedisScript<Long> RETENTION_SCRIPT = new DefaultRedisScript<>("""
            local tail = redis.call('XREVRANGE', KEYS[1], '+', '-', 'COUNT', 1)
            if #tail == 0 then return 0 end
            local current = tonumber(string.match(tail[1][1], '^(%d+)%-'))
            local archived = current == tonumber(ARGV[1])
            local keys = {KEYS[1], KEYS[2], KEYS[3], KEYS[4]}
            if redis.call('TYPE', KEYS[2]).ok == 'zset' then
              for _,key in ipairs(redis.call('ZRANGE', KEYS[2], 0, -1)) do
                if string.sub(key, 1, string.len(KEYS[3]) + 1) == KEYS[3] .. ':' then
                  keys[#keys+1] = key
                end
              end
            end
            for _,key in ipairs(keys) do
              if archived then
                local ttl = redis.call('TTL', key)
                if ttl == -1 or ttl > tonumber(ARGV[2]) then redis.call('EXPIRE', key, ARGV[2]) end
              else
                redis.call('PERSIST', key)
              end
            end
            return archived and 1 or 0
            """, Long.class);

    private final StringRedisTemplate redis;
    static final String AGENT_STREAMS = "wolfmind:scheduler:streams";
    private final java.util.List<java.util.function.Consumer<String>> listeners = new java.util.concurrent.CopyOnWriteArrayList<>();
    @Override public AutoCloseable onAppend(java.util.function.Consumer<String> listener) {
        listeners.add(listener); return () -> listeners.remove(listener);
    }

    public RedisEventStore(StringRedisTemplate redis) {
        this.redis = Objects.requireNonNull(redis, "redis");
    }

    @Override
    public void append(String gameId, int expectedVersion, List<EventEnvelope> events) {
        if (gameId == null || gameId.isBlank()) {
            throw GameException.Factory.validation("gameId 不能为空");
        }
        if (events == null) {
            throw GameException.Factory.validation("append 的事件列表不能为 null");
        }
        if (events.isEmpty()) {
            return;
        }
        if (expectedVersion < 0) {
            throw GameException.Factory.validation("expectedVersion 不能为负：" + expectedVersion);
        }

        List<String> args = new ArrayList<>(3 + events.size() * FIELDS.length);
        args.add(Integer.toString(expectedVersion));
        args.add(Long.toString(ttlSeconds(gameId, events)));
        args.add(Integer.toString(events.size()));
        for (int i = 0; i < events.size(); i++) {
            EventEnvelope event = events.get(i);
            if (event == null) {
                throw GameException.Factory.validation("第 " + i + " 条事件为 null");
            }
            if (!gameId.equals(event.gameId())) {
                throw GameException.Factory.validation(
                        "第 " + i + " 条事件的 gameId=" + event.gameId() + " 与流分区键不一致");
            }
            int expectedVersionForEntry = expectedVersion + 1 + i;
            if (event.version() != expectedVersionForEntry) {
                if (i == 0) {
                    throw GameException.Factory.versionConflict(expectedVersion, event.version() - 1);
                }
                throw GameException.Factory.replayOutOfOrder(expectedVersionForEntry, event.version());
            }
            args.addAll(fieldValues(event));
        }

        boolean gameStream = !gameId.startsWith("room:") && !gameId.startsWith("user:")
                && EventType.fromCode(events.getFirst().type()).isGameEvent();
        args.add(gameStream ? "1" : "0");
        args.add(gameId);
        args.add(gameId.startsWith("room:") ? "1" : "0");
        List<?> result = redis.execute(APPEND_SCRIPT, List.of(RedisKeys.events(gameId), AGENT_STREAMS,
                RedisKeys.snapshotIndex(gameId), RedisKeys.latestSnapshot(gameId),
                RedisEventConsumption.directory("cg-projection"), RedisEventConsumption.directory("cg-broadcast"),
                RedisEventConsumption.directory("cg-insight"), RedisKeys.scope(gameId) + ":state"), args.toArray());
        if (result == null || result.isEmpty()) {
            throw new IllegalStateException("Redis 追加脚本没有返回结果：" + gameId);
        }
        if ("VERSION_CONFLICT".equals(String.valueOf(result.get(0)))) {
            int current = Integer.parseInt(String.valueOf(result.get(1)));
            throw GameException.Factory.versionConflict(expectedVersion, current);
        }
        for (var listener : listeners) { try { listener.accept(gameId); } catch (RuntimeException ignored) { } }
    }

    @Override
    public List<EventEnvelope> read(String gameId, int fromVersion, int toVersion) {
        if (fromVersion > toVersion) {
            return List.of();
        }
        String key = RedisKeys.events(gameId);
        Range.Bound<String> upper = toVersion == Integer.MAX_VALUE
                ? Range.Bound.unbounded()
                : Range.Bound.inclusive(toVersion + "-0");
        List<EventEnvelope> result = new ArrayList<>();
        int from = Math.max(1, fromVersion);
        while (true) {
            List<MapRecord<String, Object, Object>> page = redis.opsForStream().range(
                    key,
                    Range.of(Range.Bound.inclusive(from + "-0"), upper),
                    Limit.limit().count(READ_PAGE));
            if (page == null || page.isEmpty()) {
                break;
            }
            for (MapRecord<String, Object, Object> record : page) {
                result.add(toEnvelope(record.getValue()));
            }
            if (page.size() < READ_PAGE) {
                break;
            }
            from = page.get(page.size() - 1).getId().getTimestamp().intValue() + 1;
        }
        return Collections.unmodifiableList(result);
    }

    @Override
    public int currentVersion(String gameId) {
        List<MapRecord<String, Object, Object>> tail = redis.opsForStream().reverseRange(
                RedisKeys.events(gameId), Range.unbounded(), Limit.limit().count(1));
        if (tail == null || tail.isEmpty()) {
            return 0;
        }
        return tail.get(0).getId().getTimestamp().intValue();
    }

    @Override
    public boolean exists(String gameId) {
        return Boolean.TRUE.equals(redis.hasKey(RedisKeys.events(gameId)));
    }

    /** {@code SCAN game:*:events}：只匹配对局流，房间与账号流前缀不同，DLQ 的后缀也不同。 */
    @Override
    public List<String> gameIds() {
        List<String> ids = new ArrayList<>();
        ScanOptions options = ScanOptions.scanOptions()
                .match(RedisKeys.GAME_PREFIX + "*" + RedisKeys.EVENTS_SUFFIX)
                .count(500)
                .build();
        try (Cursor<String> cursor = redis.scan(options)) {
            while (cursor.hasNext()) {
                String key = cursor.next();
                ids.add(key.substring(
                        RedisKeys.GAME_PREFIX.length(), key.length() - RedisKeys.EVENTS_SUFFIX.length()));
            }
        }
        ids.sort(String::compareTo);
        return List.copyOf(ids);
    }

    List<String> roomIds() {
        var ids = new ArrayList<String>();
        try (Cursor<String> cursor = redis.scan(ScanOptions.scanOptions().match("room:*:events").count(500).build())) {
            while (cursor.hasNext()) { String key = cursor.next(); ids.add(key.substring(0, key.length() - 7)); }
        }
        return List.copyOf(ids);
    }

    public void requestInsightRebuild(String gameId) {
        redis.opsForHash().put(RedisEventConsumption.repairs("cg-insight"), gameId, java.util.UUID.randomUUID().toString());
        for (var listener : listeners) { try { listener.accept(gameId); } catch (RuntimeException ignored) { } }
    }

    /** 当前 TTL 秒数；不存在返回负数（与 Redis {@code TTL} 语义一致）。供快照键对齐过期时间。 */
    long ttlSecondsOf(String gameId) {
        Long ttl = redis.getExpire(RedisKeys.events(gameId));
        return ttl == null ? -2 : ttl;
    }

    @Override
    public boolean restoreAbsent(String gameId, List<EventEnvelope> events) {
        if (events.isEmpty() || !events.getFirst().type().equals("GAME_STARTED")
                || (!events.getLast().type().equals("GAME_FINISHED") && !events.getLast().type().equals("GAME_ABORTED"))) {
            throw new IllegalArgumentException("只允许安装完整终局事件流");
        }
        List<String> args = new ArrayList<>(List.of("0", "600", Integer.toString(events.size())));
        for (int index = 0; index < events.size(); index++) {
            var event = events.get(index);
            if (!gameId.equals(event.gameId()) || event.version() != index + 1) {
                throw GameException.Factory.replayOutOfOrder(index + 1, event.version());
            }
            args.addAll(fieldValues(event));
        }
        args.add("0"); args.add(gameId);
        String staging = "wolfmind:archive-restore:" + java.util.UUID.randomUUID();
        try {
            redis.execute(APPEND_SCRIPT, List.of(staging, AGENT_STREAMS), args.toArray());
            Long installed = redis.execute(INSTALL_SCRIPT, List.of(RedisKeys.events(gameId), staging,
                    RedisKeys.snapshotIndex(gameId), RedisKeys.latestSnapshot(gameId), AGENT_STREAMS,
                    RedisEventConsumption.directory("cg-broadcast"), RedisKeys.scope(gameId) + ":state"),
                    Integer.toString(events.size()), Long.toString(GAME_TERMINAL_TTL_SECONDS), gameId);
            if (installed == null) throw new IllegalStateException("归档安装脚本无结果：" + gameId);
            return installed == 1L;
        } finally {
            redis.delete(staging);
        }
    }

    /** 启动时迁移旧保留期；当前流版本与已完成归档水位不同就取消过期。 */
    public void reconcileRetention(com.wolfmind.application.port.ReplayArchivePort archive) {
        for (String gameId : gameIds()) {
            var header = archive.completedGame(gameId).orElse(null);
            int archivedVersion = header != null && header.terminal() ? header.version() : -1;
            redis.execute(RETENTION_SCRIPT, List.of(RedisKeys.events(gameId), RedisKeys.snapshotIndex(gameId),
                    RedisKeys.latestSnapshot(gameId), RedisKeys.scope(gameId) + ":state"), Integer.toString(archivedVersion),
                    Long.toString(GAME_TERMINAL_TTL_SECONDS));
        }
    }

    /**
     * redis-schema.md §9.1 的逐键 TTL。
     *
     * <p>对局流在追加时一律取消过期，终局事件也不例外：未归档的事件不允许先过期，
     * 7 天的终局保留期只在 SQLite 归档核对通过后由 {@link #shortenAfterArchive} 设置。
     */
    static long ttlSeconds(String partition, List<EventEnvelope> events) {
        if (partition.startsWith(RedisKeys.USER_PREFIX)) {
            return USER_TTL_SECONDS;
        }
        if (partition.startsWith(RedisKeys.ROOM_PREFIX)) {
            return contains(events, EventType.ROOM_CLOSED) ? ROOM_CLOSED_TTL_SECONDS : ROOM_OPEN_TTL_SECONDS;
        }
        return GAME_RUNNING_TTL_SECONDS;
    }

    /**
     * 归档完成后把对局流与快照键的 TTL 缩到 7 天。只缩不延：已经短于 7 天的键保持原样，
     * 因此重复调用是安全的。
     */
    public void shortenAfterArchive(String gameId) {
        List<String> keys = new ArrayList<>(List.of(
                RedisKeys.events(gameId), RedisKeys.snapshotIndex(gameId), RedisKeys.latestSnapshot(gameId), RedisKeys.scope(gameId) + ":state"));
        Set<String> versioned = redis.opsForZSet().range(RedisKeys.snapshotIndex(gameId), 0, -1);
        if (versioned != null) {
            keys.addAll(versioned);
        }
        Duration terminal = Duration.ofSeconds(GAME_TERMINAL_TTL_SECONDS);
        for (String key : keys) {
            Long ttl = redis.getExpire(key);
            if (ttl != null && (ttl == -1 || ttl > GAME_TERMINAL_TTL_SECONDS)) {
                redis.expire(key, terminal);
            }
        }
    }

    private static boolean contains(List<EventEnvelope> events, EventType type) {
        for (EventEnvelope event : events) {
            if (type.code().equals(event.type())) {
                return true;
            }
        }
        return false;
    }

    private static List<String> fieldValues(EventEnvelope event) {
        return List.of(
                event.eventId(),
                event.gameId(),
                Integer.toString(event.version()),
                event.type(),
                Integer.toString(event.schemaVersion()),
                Long.toString(event.occurredAt()),
                event.phase(),
                Integer.toString(event.round()),
                event.actorId(),
                event.visibility().name(),
                event.causationId(),
                event.correlationId(),
                Json.write(event.payload()));
    }

    @SuppressWarnings("unchecked")
    static EventEnvelope toEnvelope(Map<Object, Object> fields) {
        Object payload = Json.narrowIntegers(Json.read(field(fields, "payload")));
        return new EventEnvelope(
                field(fields, "eventId"),
                field(fields, "gameId"),
                Integer.parseInt(field(fields, "version")),
                field(fields, "type"),
                Integer.parseInt(field(fields, "schemaVersion")),
                Long.parseLong(field(fields, "occurredAt")),
                field(fields, "phase"),
                Integer.parseInt(field(fields, "round")),
                field(fields, "actorId"),
                Visibility.valueOf(field(fields, "visibility")),
                field(fields, "causationId"),
                field(fields, "correlationId"),
                (Map<String, Object>) payload);
    }

    private static String field(Map<Object, Object> fields, String name) {
        Object value = fields.get(name);
        if (value == null) {
            throw new IllegalStateException("事件流条目缺少字段 " + name + "（信封字段不允许缺失）");
        }
        return value.toString();
    }
}
