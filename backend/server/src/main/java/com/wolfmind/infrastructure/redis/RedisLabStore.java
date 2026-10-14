package com.wolfmind.infrastructure.redis;

import com.wolfmind.application.lab.LabAgentConfig;
import com.wolfmind.application.lab.LabBatch;
import com.wolfmind.application.lab.LabStorePort;
import com.wolfmind.infrastructure.json.Json;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Redis 实验室存储（{@code wolfmind.storage=redis}）。
 *
 * <p><b>为什么不落 SQLite。</b>database-schema.md 的六张业务表是被批准的契约，里面没有
 * "实验配置"这一类；而批次与对局的关联已经在 {@code games.lab_batch_id} 上，
 * 这里保存的是操作者可见的配置文本与进度视图。什么时候该为它建表，是"实验数据要长期保留"
 * 这个需求出现时再决定的事，不是现在猜。
 *
 * <p><b>没有 TTL。</b>与进行中对局的键不同，实验配置是账号资产：过期删除会让"我上周存的配置
 * 不见了"变成一个无法解释的现象。规模由 {@code lab.maxConfigsPerOwner} 控制（见 §12 的限制表）。
 *
 * <p>键空间（redis-schema.md §15）：
 * {@code wolfmind:lab:config:{id}}、{@code wolfmind:lab:configs}（集合）、
 * {@code wolfmind:lab:batch:{id}}、{@code wolfmind:lab:batches}（集合）。
 */
public final class RedisLabStore implements LabStorePort {

    private static final String CONFIG_PREFIX = "wolfmind:lab:config:";
    private static final String CONFIG_INDEX = "wolfmind:lab:configs";
    private static final String BATCH_PREFIX = "wolfmind:lab:batch:";
    private static final String BATCH_INDEX = "wolfmind:lab:batches";

    private final StringRedisTemplate redis;

    public RedisLabStore(StringRedisTemplate redis) {
        this.redis = redis;
    }

    // ------------------------------------------------------------------ 配置

    @Override
    public LabAgentConfig saveConfig(LabAgentConfig config) {
        redis.opsForValue().set(CONFIG_PREFIX + config.agentConfigId(), Json.write(config));
        redis.opsForSet().add(CONFIG_INDEX, config.agentConfigId());
        return config;
    }

    @Override
    public Optional<LabAgentConfig> config(String agentConfigId) {
        if (agentConfigId == null) {
            return Optional.empty();
        }
        String json = redis.opsForValue().get(CONFIG_PREFIX + agentConfigId);
        return json == null ? Optional.empty() : Optional.of(readConfig(json));
    }

    @Override
    public List<LabAgentConfig> configs(String ownerId, int page, int pageSize) {
        List<LabAgentConfig> all = new ArrayList<>();
        for (String id : ids(CONFIG_INDEX)) {
            String json = redis.opsForValue().get(CONFIG_PREFIX + id);
            if (json == null) {
                // 值被删但集合里还留着 id：顺手清掉，避免它永远占着一次分页名额。
                redis.opsForSet().remove(CONFIG_INDEX, id);
                continue;
            }
            LabAgentConfig config = readConfig(json);
            if (ownerId == null || ownerId.equals(config.ownerId())) {
                all.add(config);
            }
        }
        all.sort(Comparator.comparingLong(LabAgentConfig::updatedAt).reversed()
                .thenComparing(LabAgentConfig::agentConfigId));
        return com.wolfmind.infrastructure.memory.InMemoryLabStore.slice(all, page, pageSize);
    }

    @Override
    public List<LabAgentConfig> configsByIds(List<String> agentConfigIds) {
        List<LabAgentConfig> result = new ArrayList<>();
        for (String id : agentConfigIds) {
            config(id).ifPresent(result::add);
        }
        return List.copyOf(result);
    }

    // ------------------------------------------------------------------ 批次

    @Override
    public LabBatch saveBatch(LabBatch batch) {
        redis.opsForValue().set(BATCH_PREFIX + batch.batchId(), Json.write(batch));
        redis.opsForSet().add(BATCH_INDEX, batch.batchId());
        return batch;
    }

    @Override
    public Optional<LabBatch> batch(String batchId) {
        if (batchId == null) {
            return Optional.empty();
        }
        String json = redis.opsForValue().get(BATCH_PREFIX + batchId);
        return json == null ? Optional.empty() : Optional.of(readBatch(json));
    }

    @Override
    public List<LabBatch> batches(String ownerId, int page, int pageSize) {
        List<LabBatch> all = new ArrayList<>();
        for (String id : ids(BATCH_INDEX)) {
            String json = redis.opsForValue().get(BATCH_PREFIX + id);
            if (json == null) {
                redis.opsForSet().remove(BATCH_INDEX, id);
                continue;
            }
            LabBatch batch = readBatch(json);
            if (ownerId == null || ownerId.equals(batch.ownerId())) {
                all.add(batch);
            }
        }
        all.sort(Comparator.comparingLong(LabBatch::createdAt).reversed().thenComparing(LabBatch::batchId));
        return com.wolfmind.infrastructure.memory.InMemoryLabStore.slice(all, page, pageSize);
    }

    @Override
    public List<LabBatch> runningBatches() {
        List<LabBatch> running = new ArrayList<>();
        for (String id : ids(BATCH_INDEX)) {
            String json = redis.opsForValue().get(BATCH_PREFIX + id);
            if (json == null) {
                continue;
            }
            LabBatch batch = readBatch(json);
            if (LabBatch.RUNNING.equals(batch.status())) {
                running.add(batch);
            }
        }
        running.sort(Comparator.comparingLong(LabBatch::createdAt));
        return List.copyOf(running);
    }

    // ------------------------------------------------------------------ 编解码

    private Set<String> ids(String indexKey) {
        Set<String> members = redis.opsForSet().members(indexKey);
        return members == null ? Set.of() : members;
    }

    private static LabAgentConfig readConfig(String json) {
        Map<String, Object> map = Json.readObject(json);
        return new LabAgentConfig(
                text(map, "agentConfigId"),
                text(map, "ownerId"),
                text(map, "name"),
                text(map, "role"),
                text(map, "model"),
                number(map, "temperature"),
                text(map, "strategyProfile"),
                text(map, "persona"),
                text(map, "memoryMode"),
                text(map, "promptTemplateId"),
                longValue(map, "createdAt"),
                longValue(map, "updatedAt"));
    }

    private static LabBatch readBatch(String json) {
        Map<String, Object> map = Json.readObject(json);
        List<LabBatch.GameRef> games = new ArrayList<>();
        if (map.get("games") instanceof List<?> list) {
            for (Object item : list) {
                if (item instanceof Map<?, ?> game) {
                    games.add(readGame(game));
                }
            }
        }
        List<String> configIds = new ArrayList<>();
        if (map.get("agentConfigIds") instanceof List<?> list) {
            for (Object item : list) {
                if (item instanceof String id) {
                    configIds.add(id);
                }
            }
        }
        return new LabBatch(
                text(map, "batchId"),
                text(map, "name"),
                text(map, "ownerId"),
                text(map, "status"),
                (int) longValue(map, "total"),
                (int) longValue(map, "maxConcurrentGames"),
                configIds,
                games,
                text(map, "error"),
                longValue(map, "createdAt"),
                longValue(map, "updatedAt"));
    }

    private static LabBatch.GameRef readGame(Map<?, ?> game) {
        Map<String, LabBatch.AssignedConfig> assignments = new LinkedHashMap<>();
        if (game.get("roleAssignments") instanceof Map<?, ?> raw) {
            for (Map.Entry<?, ?> entry : raw.entrySet()) {
                if (entry.getValue() instanceof Map<?, ?> value) {
                    assignments.put(String.valueOf(entry.getKey()), new LabBatch.AssignedConfig(
                            stringValue(value.get("agentConfigId")),
                            (int) longValue(value, "seatNo"),
                            stringValue(value.get("camp"))));
                }
            }
        }
        Object rounds = game.get("rounds");
        return new LabBatch.GameRef(
                (int) longValue(game, "index"),
                stringValue(game.get("gameId")),
                stringValue(game.get("status")),
                stringValue(game.get("winner")),
                rounds instanceof Number number ? number.intValue() : null,
                assignments);
    }

    private static String text(Map<String, Object> map, String field) {
        return stringValue(map.get(field));
    }

    private static String stringValue(Object value) {
        return value instanceof String string ? string : null;
    }

    private static double number(Map<String, Object> map, String field) {
        return map.get(field) instanceof Number number ? number.doubleValue() : 0d;
    }

    private static long longValue(Map<?, ?> map, String field) {
        return map.get(field) instanceof Number number ? number.longValue() : 0L;
    }
}
