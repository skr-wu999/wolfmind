package com.wolfmind.application.agent.runtime;

import com.wolfmind.application.agent.AgentRuntime;
import com.wolfmind.application.agent.model.AgentConfig;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 实例注册表：{@code (gameId, playerId)} → {@link AgentInstance}（agent-runtime.md §2）。
 *
 * <p>两条语义：
 * <ul>
 *   <li><b>创建幂等</b>：同一 {@code (gameId, playerId)} 重复 {@code register} 返回既有实例，
 *       因此事件重放不会产生重复实例（agent-runtime.md §3）；</li>
 *   <li><b>释放安全</b>：{@code unregister} 对未知句柄是 no-op，允许 {@code destroyAgent}
 *       被重复调用（例如 {@code GAME_FINISHED} 与席位接管同时到达）。</li>
 * </ul>
 */
public final class AgentRegistry {

    private final Map<String, AgentInstance> instances = new ConcurrentHashMap<>();

    /**
     * 注册（或取回）实例。
     *
     * @return 既有实例或新建实例，以及"本次是否新建"
     */
    public Registration register(String gameId, String playerId, int seatNo, AgentConfig config) {
        String key = key(gameId, playerId);
        AgentInstance existing = instances.get(key);
        if (existing != null) {
            return new Registration(existing, false);
        }
        AgentInstance created = new AgentInstance(
                new AgentRuntime.AgentHandle(gameId, playerId, seatNo, config.agentConfigId()), config);
        AgentInstance raced = instances.putIfAbsent(key, created);
        if (raced != null) {
            // 并发注册时以先到者为准：两者配置相同（同一局内配置不变），因此丢弃后者是安全的
            return new Registration(raced, false);
        }
        return new Registration(created, true);
    }

    public Optional<AgentInstance> find(String gameId, String playerId) {
        return Optional.ofNullable(instances.get(key(gameId, playerId)));
    }

    public void unregister(String gameId, String playerId) {
        instances.remove(key(gameId, playerId));
    }

    /** 释放一整局的全部实例，返回释放数量。 */
    public int unregisterGame(String gameId) {
        String prefix = gameId + ":";
        List<String> keys = instances.keySet().stream().filter(key -> key.startsWith(prefix)).toList();
        keys.forEach(instances::remove);
        return keys.size();
    }

    public int countInGame(String gameId) {
        String prefix = gameId + ":";
        return (int) instances.keySet().stream().filter(key -> key.startsWith(prefix)).count();
    }

    public Collection<AgentInstance> all() {
        return List.copyOf(instances.values());
    }

    /** 便于测试与运维：当前实例的键集合。 */
    public List<String> keys() {
        return List.copyOf(instances.keySet());
    }

    private String key(String gameId, String playerId) {
        return gameId + ":" + playerId;
    }

    /** 注册结果。 */
    public record Registration(AgentInstance instance, boolean created) {}
}
