package com.wolfmind.application.agent.fake;

import com.wolfmind.application.agent.memory.MemoryPort;

import java.util.ArrayList;
import java.util.List;

/** 内存记忆实现：同时用于验证"跨局记忆的隐私过滤"由实现负责。 */
public final class InMemoryMemory implements MemoryPort {

    private final List<MemoryEntry> shortTerm = new ArrayList<>();
    private final List<MemoryEntry> longTerm = new ArrayList<>();

    @Override
    public List<MemoryEntry> loadShortTerm(String gameId, String playerId, int limit) {
        return shortTerm.stream().limit(Math.max(0, limit)).toList();
    }

    @Override
    public List<MemoryEntry> loadLongTerm(String agentConfigId, String role, String phase, int limit) {
        return longTerm.stream()
                .filter(entry -> entry.role() == null || entry.role().equals(role))
                .filter(entry -> entry.phase() == null || entry.phase().equals(phase))
                .limit(Math.max(0, limit))
                .toList();
    }

    @Override
    public void saveLongTerm(String agentConfigId, MemoryEntry entry) {
        longTerm.add(entry);
    }

    @Override
    public void appendShortTerm(String gameId, String playerId, MemoryEntry entry) {
        shortTerm.add(entry);
    }

    @Override
    public void clearShortTerm(String gameId, String playerId) {
        shortTerm.clear();
    }

    public List<MemoryEntry> longTermEntries() {
        return List.copyOf(longTerm);
    }

    public List<MemoryEntry> shortTermEntries() {
        return List.copyOf(shortTerm);
    }
}
