package com.wolfmind.infrastructure.redis;

import com.wolfmind.application.port.ReplayArchivePort;
import com.wolfmind.application.scheduling.EventConsumptionPort;
import org.springframework.data.redis.connection.stream.*;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.domain.Range;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public final class RedisEventConsumption implements EventConsumptionPort {
    static final String GROUP = "cg-agent";
    static final String BLOCKED = "wolfmind:scheduler:blocked";
    static final String DIRECTORY_INITIALIZED = "wolfmind:scheduler:directory-initialized";
    private final StringRedisTemplate redis;
    private final RedisEventStore store;
    private final String group;
    private final String consumer = "wolfmind-" + UUID.randomUUID();
    private final String directory;
    private final String blocked;
    private final String failures;
    private final long claimIdleMs;
    private final ReplayArchivePort archive;
    private final Set<String> subscribed = ConcurrentHashMap.newKeySet();
    private final Set<String> forgotten = ConcurrentHashMap.newKeySet();
    private final Map<String,Integer> acknowledged = new ConcurrentHashMap<>();
    private final Set<String> leases = ConcurrentHashMap.newKeySet();
    private final java.util.concurrent.Semaphore wakeup = new java.util.concurrent.Semaphore(0);
    private final AutoCloseable subscription;

    public RedisEventConsumption(StringRedisTemplate redis, RedisEventStore store) {
        this(redis, store, GROUP, null, 60_000L);
    }
    public RedisEventConsumption(StringRedisTemplate redis, RedisEventStore store, String group,
                                  ReplayArchivePort archive, long claimIdleMs) {
        if (!Set.of(GROUP, "cg-projection", "cg-broadcast", "cg-insight").contains(group))
            throw new IllegalArgumentException("Unknown consumer group: " + group);
        if (claimIdleMs < 0) throw new IllegalArgumentException("claimIdleMs must be nonnegative");
        this.redis = redis; this.store = store; this.group = group; this.archive = archive;
        this.claimIdleMs = claimIdleMs; this.directory = directory(group);
        blocked = group.equals(GROUP) ? BLOCKED : "wolfmind:consumer:" + group + ":blocked";
        failures = group.equals(GROUP) ? "wolfmind:scheduler:event-failures" : "wolfmind:consumer:" + group + ":failures";
        String initialized = group.equals(GROUP) ? DIRECTORY_INITIALIZED : "wolfmind:consumer:" + group + ":initialized";
        if (!Boolean.TRUE.equals(redis.hasKey(initialized))) {
            List<String> existing = new ArrayList<>(store.gameIds());
            if (group.equals("cg-broadcast")) existing.addAll(store.roomIds());
            if (archive != null && !group.equals(GROUP)) existing.addAll(archive.completedGameIds());
            if (!existing.isEmpty()) redis.opsForSet().add(directory, existing.toArray(String[]::new));
            redis.opsForValue().set(initialized, "1");
        }
        subscription = store.onAppend(id -> {
            if (Boolean.TRUE.equals(redis.opsForSet().isMember(directory, id))) {
                forgotten.remove(id); subscribed.add(id);
                wakeup.release();
            }
            else if (group.equals("cg-insight") && Boolean.TRUE.equals(redis.opsForHash().hasKey(repairs(group),id))) wakeup.release();
        });
    }
    static String directory(String group) {
        return group.equals(GROUP) ? RedisEventStore.AGENT_STREAMS : "wolfmind:consumer:" + group + ":streams";
    }
    static String repairs(String group) { return "wolfmind:consumer:" + group + ":repairs"; }

    @Override public List<Delivery> read(int limit, long blockMillis) {
        if (group.equals("cg-insight")) {
            for (var entry : redis.opsForHash().entries(repairs(group)).entrySet()) {
                String id = entry.getKey().toString();
                if (Boolean.TRUE.equals(redis.opsForHash().hasKey(blocked, id))) continue;
                if (!acquire(id)) continue;
                var history = store.exists(id) ? store.read(id, 1, Integer.MAX_VALUE) : archived(id);
                if (!history.isEmpty()) return List.of(new Delivery(id, history, entry.getValue().toString()));
                release(id);
            }
        }
        Set<String> members = redis.opsForSet().members(directory);
        subscribed.retainAll(members == null ? Set.of() : members);
        if (members != null) subscribed.addAll(members);
        subscribed.removeAll(forgotten);
        List<Delivery> result = new ArrayList<>();
        for (String id : subscribed.stream().sorted().toList()) {
            if (Boolean.TRUE.equals(redis.opsForHash().hasKey(blocked, id))) continue;
            if (!acquire(id)) continue;
            String key = RedisKeys.events(id);
            if (!store.exists(id)) {
                if (group.equals("cg-broadcast")) { forget(id); release(id); continue; }
                var history = archived(id);
                if (!history.isEmpty()) result.add(new Delivery(id, history, "ARCHIVE"));
                else if (!group.equals(GROUP)) redis.opsForHash().put(blocked,id,"STREAM_MISSING_NO_COMPLETE_ARCHIVE");
                if (history.isEmpty()) release(id);
                continue;
            }
            try { redis.opsForStream().createGroup(key, ReadOffset.from("0-0"), group); }
            catch (RuntimeException error) {
                boolean exists = false;
                for (Throwable cause = error; cause != null; cause = cause.getCause())
                    if (cause.getMessage() != null && cause.getMessage().contains("BUSYGROUP")) exists = true;
                if (!exists) throw error;
            }
            var pel = redis.opsForStream().pending(key, group, Range.unbounded(), limit);
            if (pel != null) {
                var abandoned = pel.stream().filter(p -> !consumer.equals(p.getConsumerName())
                                && p.getElapsedTimeSinceLastDelivery().toMillis() >= claimIdleMs)
                        .map(PendingMessage::getId).toArray(RecordId[]::new);
                if (abandoned.length > 0) redis.opsForStream().claim(key, group, consumer,
                        Duration.ofMillis(claimIdleMs), abandoned);
                if (pel.stream().anyMatch(p -> !consumer.equals(p.getConsumerName())
                        && p.getElapsedTimeSinceLastDelivery().toMillis() < claimIdleMs)) { release(id); continue; }
            }
            var records = redis.opsForStream().read(Consumer.from(group, consumer), StreamReadOptions.empty().count(limit),
                    StreamOffset.create(key, ReadOffset.from("0")));
            if (records == null || records.isEmpty()) records = redis.opsForStream().read(Consumer.from(group, consumer),
                    StreamReadOptions.empty().count(limit), StreamOffset.create(key, ReadOffset.lastConsumed()));
            add(result, id, records);
            if (result.stream().noneMatch(delivery -> delivery.gameId().equals(id))) release(id);
        }
        if (result.isEmpty() && blockMillis > 0) {
            try { wakeup.tryAcquire(blockMillis,java.util.concurrent.TimeUnit.MILLISECONDS); }
            catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
        }
        return result;
    }
    private List<com.wolfmind.domain.game.event.EventEnvelope> archived(String id) {
        if (archive == null || id.startsWith("room:") || archive.completedGame(id).isEmpty()) return List.of();
        return archive.events(id, 1, Integer.MAX_VALUE, Integer.MAX_VALUE);
    }
    private void add(List<Delivery> result, String id, List<MapRecord<String, Object, Object>> records) {
        if (records == null || records.isEmpty()) return;
        var decoded = new ArrayList<com.wolfmind.domain.game.event.EventEnvelope>();
        for (var record : records) {
            try { decoded.add(RedisEventStore.toEnvelope(record.getValue())); }
            catch (RuntimeException invalid) { poison(id, record.getId().getValue(), record.getValue(), "EVENT_DECODE"); break; }
        }
        if (!decoded.isEmpty()) result.add(new Delivery(id, decoded));
    }
    private void poison(String id, String entryId, Map<Object,Object> fields, String code) {
        Long attempts = redis.opsForHash().increment(failures, id + ":" + entryId, 1);
        if (attempts != null && attempts >= 3 && !Boolean.TRUE.equals(redis.opsForHash().hasKey(blocked,id))) {
            Map<Object,Object> dead = new LinkedHashMap<>(fields);
            dead.put("entryId",entryId); dead.put("groupId",group); dead.put("errorCode",code); dead.put("attempts",attempts.toString());
            redis.opsForStream().add(MapRecord.create(RedisKeys.events(id) + ":dlq", dead));
            redis.expire(RedisKeys.events(id) + ":dlq", Duration.ofDays(7));
            redis.opsForHash().put(blocked,id,code + ":" + entryId);
        }
    }
    @Override public void failed(Delivery delivery, String code) {
        var first = delivery.events().getFirst();
        Map<Object,Object> metadata = new LinkedHashMap<>();
        metadata.put("eventId", first.eventId()); metadata.put("gameId", delivery.gameId()); metadata.put("type", first.type());
        poison(delivery.gameId(), first.version() + "-0", metadata, code);
        release(delivery);
    }
    @Override public void acknowledge(Delivery delivery) {
        acknowledged.merge(delivery.gameId(), delivery.events().getLast().version(), Math::max);
        if (delivery.receipt() != null) {
            if (!delivery.receipt().equals("ARCHIVE")) redis.execute(new DefaultRedisScript<>("""
                    if redis.call('HGET',KEYS[1],ARGV[1]) == ARGV[2] then
                      return redis.call('HDEL',KEYS[1],ARGV[1])
                    end
                    return 0
                    """, Long.class), List.of(repairs(group)), delivery.gameId(), delivery.receipt());
            release(delivery);
            return;
        }
        redis.opsForStream().acknowledge(RedisKeys.events(delivery.gameId()), group,
                delivery.events().stream().map(e -> e.version() + "-0").toArray(String[]::new));
        release(delivery);
    }
    @Override public void forget(String id) {
        Long removed = redis.execute(new DefaultRedisScript<>("""
                local tail=redis.call('XREVRANGE',KEYS[1],'+','-','COUNT',1)
                local current=0
                if #tail > 0 then current=tonumber(string.match(tail[1][1],'^(%d+)%-')) end
                if current > tonumber(ARGV[2]) then return 0 end
                redis.call('SREM',KEYS[2],ARGV[1]); return 1
                """, Long.class), List.of(RedisKeys.events(id),directory),id,Integer.toString(acknowledged.getOrDefault(id,0)));
        if (Long.valueOf(1L).equals(removed)) { subscribed.remove(id); forgotten.add(id); }
    }
    @Override public long blockedCount() { return redis.opsForHash().size(blocked); }
    @Override public long pendingCount() {
        long count = redis.opsForHash().size(repairs(group));
        for (String id : subscribed) if (store.exists(id)) {
            try { count += redis.opsForStream().pending(RedisKeys.events(id), group).getTotalPendingMessages(); }
            catch (RuntimeException notCreatedYet) { }
        }
        return count;
    }
    private String leaseKey(String id) { return "wolfmind:consumer:"+group+":lease:"+id; }
    private boolean acquire(String id) {
        Long result=redis.execute(new DefaultRedisScript<>("""
                local owner=redis.call('GET',KEYS[1])
                if owner == ARGV[1] then redis.call('PEXPIRE',KEYS[1],ARGV[2]); return 1 end
                if not owner then redis.call('SET',KEYS[1],ARGV[1],'PX',ARGV[2],'NX'); return 1 end
                return 0
                """,Long.class),List.of(leaseKey(id)),consumer,Long.toString(Math.max(60000L,claimIdleMs+5000L)));
        if(Long.valueOf(1L).equals(result)){leases.add(id);return true;}
        return false;
    }
    private void release(String id) {
        redis.execute(new DefaultRedisScript<>("""
                if redis.call('GET',KEYS[1]) == ARGV[1] then return redis.call('DEL',KEYS[1]) end
                return 0
                """,Long.class),List.of(leaseKey(id)),consumer);
        leases.remove(id);
    }
    @Override public void release(Delivery delivery) { release(delivery.gameId()); }
    @Override public void close() {
        try { subscription.close(); } catch (Exception ignored) { }
        for(String id:List.copyOf(leases)) release(id);
    }
}
