package com.wolfmind.infrastructure.memory;

import com.wolfmind.application.lab.LabAgentConfig;
import com.wolfmind.application.lab.LabBatch;
import com.wolfmind.application.lab.LabStorePort;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 进程内实验室存储（{@code wolfmind.storage=memory}）。
 *
 * <p>与其它内存实现一样，它是 Redis 实现的**同形替换点**：语义（覆盖保存、按更新时间倒序、
 * 缺失 id 不出现）必须与 {@code RedisLabStore} 完全一致，否则"内存模式跑通的实验流程"
 * 在 Redis 模式下会以另一种顺序或另一种可见性出现。
 */
public final class InMemoryLabStore implements LabStorePort {

    private final Map<String, LabAgentConfig> configs = new ConcurrentHashMap<>();
    private final Map<String, LabBatch> batches = new ConcurrentHashMap<>();

    @Override
    public LabAgentConfig saveConfig(LabAgentConfig config) {
        configs.put(config.agentConfigId(), config);
        return config;
    }

    @Override
    public Optional<LabAgentConfig> config(String agentConfigId) {
        return agentConfigId == null ? Optional.empty() : Optional.ofNullable(configs.get(agentConfigId));
    }

    @Override
    public List<LabAgentConfig> configs(String ownerId, int page, int pageSize) {
        List<LabAgentConfig> all = new ArrayList<>();
        for (LabAgentConfig config : configs.values()) {
            if (ownerId == null || ownerId.equals(config.ownerId())) {
                all.add(config);
            }
        }
        all.sort(Comparator.comparingLong(LabAgentConfig::updatedAt).reversed()
                .thenComparing(LabAgentConfig::agentConfigId));
        return slice(all, page, pageSize);
    }

    @Override
    public List<LabAgentConfig> configsByIds(List<String> agentConfigIds) {
        List<LabAgentConfig> result = new ArrayList<>();
        for (String id : agentConfigIds) {
            LabAgentConfig config = configs.get(id);
            if (config != null) {
                result.add(config);
            }
        }
        return List.copyOf(result);
    }

    @Override
    public LabBatch saveBatch(LabBatch batch) {
        batches.put(batch.batchId(), batch);
        return batch;
    }

    @Override
    public Optional<LabBatch> batch(String batchId) {
        return batchId == null ? Optional.empty() : Optional.ofNullable(batches.get(batchId));
    }

    @Override
    public List<LabBatch> batches(String ownerId, int page, int pageSize) {
        List<LabBatch> all = new ArrayList<>();
        for (LabBatch batch : batches.values()) {
            if (ownerId == null || ownerId.equals(batch.ownerId())) {
                all.add(batch);
            }
        }
        all.sort(Comparator.comparingLong(LabBatch::createdAt).reversed()
                .thenComparing(LabBatch::batchId));
        return slice(all, page, pageSize);
    }

    @Override
    public List<LabBatch> runningBatches() {
        List<LabBatch> running = new ArrayList<>();
        for (LabBatch batch : batches.values()) {
            if (LabBatch.RUNNING.equals(batch.status())) {
                running.add(batch);
            }
        }
        running.sort(Comparator.comparingLong(LabBatch::createdAt));
        return List.copyOf(running);
    }

    /**
     * 与 Redis 实现共用的分页语义；改动这里必须同步那边。
     *
     * <p>它是 {@code public} 的，因为 {@code RedisLabStore} 在另一个包里也要用它——
     * 让两个实现共用同一段分页代码，比"各写一遍然后慢慢漂移"便宜。
     */
    public static <T> List<T> slice(List<T> all, int page, int pageSize) {
        int normalizedPage = Math.max(1, page);
        int normalizedSize = pageSize <= 0 ? 20 : Math.min(pageSize, 100);
        int from = Math.min((normalizedPage - 1) * normalizedSize, all.size());
        int to = Math.min(from + normalizedSize, all.size());
        return List.copyOf(all.subList(from, to));
    }

    /** 便于测试断言"没有泄漏到共享状态"；生产不使用。 */
    Map<String, Object> sizes() {
        Map<String, Object> sizes = new LinkedHashMap<>();
        sizes.put("configs", configs.size());
        sizes.put("batches", batches.size());
        return sizes;
    }
}
