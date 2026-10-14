package com.wolfmind.infrastructure.redis;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.wolfmind.application.scheduling.*;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import java.util.*;

public final class RedisTurnTaskStore implements TurnTaskStorePort {
    static final String TASKS = "wolfmind:scheduler:tasks";
    static final String READY = "wolfmind:scheduler:ready";
    static final String DEADLINES = "wolfmind:scheduler:deadlines";
    static final String RUNNING = "wolfmind:scheduler:running";
    static final String BUDGETS = "wolfmind:scheduler:budgets";
    private static final ObjectMapper JSON = new ObjectMapper();
    private final StringRedisTemplate redis;
    public RedisTurnTaskStore(StringRedisTemplate redis) { this.redis = redis; }

    private static final String RECONCILE = """
        local proposed = cjson.decode(ARGV[1]); local keep = {}
        for _,t in ipairs(proposed) do keep[t.taskId] = true end
        for _,id in ipairs(redis.call('SMEMBERS',KEYS[5])) do
          local raw = redis.call('HGET',KEYS[1],id)
          if raw and not keep[id] then
            local t = cjson.decode(raw)
            if t.status == 'READY' or t.status == 'RUNNING' or t.status == 'WAITING_DEADLINE' then
              t.status='CANCELLED'; t.leaseOwner=''; t.leaseUntil=0; t.lastErrorCode='WINDOW_CLOSED'
              redis.call('HSET',KEYS[1],id,cjson.encode(t))
              redis.call('ZREM',KEYS[2],id); redis.call('ZREM',KEYS[3],id); redis.call('ZREM',KEYS[4],id)
            end
          end
        end
        for _,t in ipairs(proposed) do
          if redis.call('HSETNX',KEYS[1],t.taskId,cjson.encode(t)) == 1 then
            redis.call('SADD',KEYS[5],t.taskId)
            local index=KEYS[2]; if t.kind=='TIMEOUT' then index=KEYS[3] end
            redis.call('ZADD',index,t.readyAt,t.taskId)
          end
        end
        return 1
        """;
    private static final String MUTATE = """
        local raw=redis.call('HGET',KEYS[1],ARGV[2]); if not raw then return '' end
        local t=cjson.decode(raw); local op=ARGV[1]; local now=tonumber(ARGV[3])
        if op=='CLAIM' then
          if t.status~='READY' or t.readyAt>now then return '' end
          t.status='RUNNING'; t.attempts=t.attempts+1; t.leaseToken=t.leaseToken+1
          t.leaseOwner=ARGV[4]; t.leaseUntil=now+tonumber(ARGV[5])
          if t.kind=='ACTION' then t.leaseUntil=math.min(t.deadline,t.leaseUntil) end
          redis.call('ZREM',KEYS[2],t.taskId); redis.call('ZREM',KEYS[3],t.taskId)
          redis.call('ZADD',KEYS[4],t.leaseUntil,t.taskId)
        elseif op=='RECOVER' then
          if t.status~='RUNNING' or t.leaseUntil>now then return '' end
          t.status='READY'; if t.kind=='ACTION' then t.status='WAITING_DEADLINE' end
          t.leaseOwner=''; t.leaseUntil=0; t.lastErrorCode='INTERRUPTED'
          redis.call('ZREM',KEYS[4],t.taskId)
          if t.status=='READY' then
            local index=KEYS[2]; if t.kind=='TIMEOUT' then index=KEYS[3] end
            redis.call('ZADD',index,t.readyAt,t.taskId)
          end
        else
          if t.status~='RUNNING' or t.leaseToken~=tonumber(ARGV[5]) or t.leaseOwner~=ARGV[4] then return '' end
          if op=='FINISH' then
            t.status=ARGV[6]; t.resultEventIds=cjson.decode(ARGV[7]); t.lastErrorCode=ARGV[8]
            t.leaseOwner=''; t.leaseUntil=0; redis.call('ZREM',KEYS[4],t.taskId)
            if t.status=='READY' then
              local index=KEYS[2]; if t.kind=='TIMEOUT' then index=KEYS[3] end
              redis.call('ZADD',index,t.readyAt,t.taskId)
            end
          else
            if t.kind~='ACTION' or now>=t.deadline or now>=t.leaseUntil then return '' end
            if op=='RESERVE' then
              local field=t.taskId..':'..ARGV[6]; local limitField=field..':limit'
              redis.call('HSETNX',KEYS[5],limitField,ARGV[7])
              local limit=tonumber(redis.call('HGET',KEYS[5],limitField))
              local count=tonumber(redis.call('HGET',KEYS[5],field) or '0')
              if count>=limit then return '' end
              redis.call('HINCRBY',KEYS[5],field,1)
            end
            return raw
          end
        end
        local encoded=cjson.encode(t); redis.call('HSET',KEYS[1],t.taskId,encoded); return encoded
        """;
    private static final DefaultRedisScript<Long> RECONCILE_SCRIPT = new DefaultRedisScript<>(RECONCILE, Long.class);
    private static final DefaultRedisScript<String> MUTATE_SCRIPT = new DefaultRedisScript<>(MUTATE, String.class);
    private static List<String> keys() { return List.of(TASKS, READY, DEADLINES, RUNNING, BUDGETS); }
    private static String gameTasks(String id) { return "game:" + id + ":scheduler:tasks"; }

    static String encode(Object value) {
        try { return JSON.writeValueAsString(value); } catch (Exception error) { throw new IllegalArgumentException(error); }
    }
    static TurnTask decode(String raw) {
        try {
            var node = JSON.readTree(raw);
            if (node.path("resultEventIds").isObject()) ((com.fasterxml.jackson.databind.node.ObjectNode) node).putArray("resultEventIds");
            return JSON.treeToValue(node, TurnTask.class);
        } catch (Exception error) { throw new IllegalStateException("Invalid scheduler task", error); }
    }
    @Override public void reconcile(String gameId, List<TurnTask> tasks) {
        redis.execute(RECONCILE_SCRIPT, List.of(TASKS, READY, DEADLINES, RUNNING, gameTasks(gameId)), encode(tasks));
    }
    @Override public List<TurnTask> tasks(String gameId) {
        Set<String> ids = redis.opsForSet().members(gameTasks(gameId));
        if (ids == null || ids.isEmpty()) return List.of();
        return load(ids);
    }
    private List<TurnTask> load(Set<String> ids) {
        return redis.opsForHash().multiGet(TASKS, new ArrayList<Object>(ids)).stream()
                .filter(Objects::nonNull).map(v -> decode(v.toString())).toList();
    }
    @Override public List<TurnTask> due(long now, boolean timeouts, int limit) {
        Set<String> ids = redis.opsForZSet().rangeByScore(timeouts ? DEADLINES : READY, 0, now, 0, limit);
        return ids == null || ids.isEmpty() ? List.of() : load(ids);
    }
    @Override public Optional<TurnTask> claim(String id, String owner, long now, long lease) {
        String raw = redis.execute(MUTATE_SCRIPT, keys(), "CLAIM", id, ""+now, owner, ""+lease);
        return raw == null || raw.isEmpty() ? Optional.empty() : Optional.of(decode(raw));
    }
    @Override public boolean finish(TurnTask task, TurnTask.Status status, List<String> events, String error) {
        String raw = redis.execute(MUTATE_SCRIPT, keys(), "FINISH", task.taskId(), "0", task.leaseOwner(),
                ""+task.leaseToken(), status.name(), encode(events), error == null ? "" : error);
        return raw != null && !raw.isEmpty();
    }
    @Override public boolean valid(ExecutionFence fence, long now) {
        String raw = redis.execute(MUTATE_SCRIPT, keys(), "VALID", fence.taskId(), ""+now,
                fence.leaseOwner(), ""+fence.leaseToken());
        if (raw == null || raw.isEmpty()) return false;
        TurnTask task = decode(raw);
        return task.gameId().equals(fence.gameId()) && task.playerId().equals(fence.playerId()) && task.windowId() == fence.windowId();
    }
    @Override public boolean reserve(ExecutionFence fence, String counter, int limit, long now) {
        if (!valid(fence, now)) return false;
        String raw = redis.execute(MUTATE_SCRIPT, keys(), "RESERVE", fence.taskId(), ""+now,
                fence.leaseOwner(), ""+fence.leaseToken(), counter, ""+limit);
        return raw != null && !raw.isEmpty();
    }
    @Override public int recover(long now) {
        Set<String> ids = redis.opsForZSet().rangeByScore(RUNNING, 0, now, 0, 200);
        int count = 0;
        if (ids != null) for (String id : ids) {
            String raw = redis.execute(MUTATE_SCRIPT, keys(), "RECOVER", id, ""+now);
            if (raw != null && !raw.isEmpty()) count++;
        }
        return count;
    }
    @Override public void purge(String gameId) {
        var script = new DefaultRedisScript<Long>("""
            for _,id in ipairs(redis.call('SMEMBERS',KEYS[6])) do
              redis.call('HDEL',KEYS[1],id)
              redis.call('ZREM',KEYS[2],id); redis.call('ZREM',KEYS[3],id); redis.call('ZREM',KEYS[4],id)
              redis.call('HDEL',KEYS[5],id..':MODEL',id..':MODEL:limit',id..':TOOL',id..':TOOL:limit')
            end
            redis.call('DEL',KEYS[6]); return 1
            """, Long.class);
        redis.execute(script, List.of(TASKS, READY, DEADLINES, RUNNING, BUDGETS, gameTasks(gameId)));
    }
}
