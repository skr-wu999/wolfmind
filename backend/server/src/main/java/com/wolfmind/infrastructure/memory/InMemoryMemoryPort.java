package com.wolfmind.infrastructure.memory;

import com.wolfmind.application.agent.memory.MemoryPort;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 内存记忆实现：Phase 3 的最小可用形态，Redis/SQLite 版本的同形替换点。
 *
 * <p>按 agent-runtime.md §7 的三层语义落地：
 * <ul>
 *   <li><b>短期</b>按 {@code (gameId, playerId)} 隔离，对局结束或释放座位时清空；</li>
 *   <li><b>长期</b>按 {@code agentConfigId} 共享，并按 {@code role + phase} 过滤召回；</li>
 *   <li><b>隐私过滤在本类内完成</b>（§7 规则 2）：跨局记忆只保留策略性内容与匿名化统计，
 *       任何看起来像"具体对局隐藏信息"的条目在写入时被丢弃。</li>
 * </ul>
 *
 * <p>隐私过滤用白名单式判断而不是黑名单正则：只有 {@code STRATEGY} / {@code REFLECTION}
 * 这类策略性条目允许跨局，{@code EPISODE} 里含座位号与角色断言的条目一律拒绝。
 * 这一层必须在写入路径上，而不是在读取时——否则数据已经落库，泄漏只是时间问题。
 */
public final class InMemoryMemoryPort implements MemoryPort {

    /** 单键短期记忆上限，避免长局无限增长（真实实现交给 Redis 的 TTL 与裁剪）。 */
    private static final int SHORT_TERM_LIMIT = 200;

    /** 跨局记忆总条数上限，防止实验室批量对局把内存吃满。 */
    private static final int LONG_TERM_LIMIT = 5_000;

    private final Map<String, List<MemoryEntry>> shortTerm = new ConcurrentHashMap<>();
    private final Map<String, List<MemoryEntry>> longTerm = new ConcurrentHashMap<>();

    private final List<String> rejectedForPrivacy = new java.util.concurrent.CopyOnWriteArrayList<>();

    @Override
    public List<MemoryEntry> loadShortTerm(String gameId, String playerId, int limit) {
        List<MemoryEntry> entries = shortTerm.getOrDefault(key(gameId, playerId), List.of());
        return entries.stream().limit(Math.max(0, limit)).toList();
    }

    @Override
    public List<MemoryEntry> loadLongTerm(String agentConfigId, String role, String phase, int limit) {
        return longTerm.getOrDefault(agentConfigId, List.of()).stream()
                .filter(entry -> entry.role() == null || entry.role().equals(role))
                .filter(entry -> entry.phase() == null || entry.phase().equals(phase))
                .sorted(Comparator.comparingDouble(MemoryEntry::importance).reversed())
                .limit(Math.max(0, limit))
                .toList();
    }

    @Override
    public void saveLongTerm(String agentConfigId, MemoryEntry entry) {
        if (!isPrivacySafe(entry)) {
            // 拒绝而不是脱敏后写入：我们无法可靠地把"3 号是预言家"改写成安全的策略结论，
            // 静默改写会造出一条语义错误的记忆，比丢弃更糟。
            rejectedForPrivacy.add(entry.content());
            return;
        }
        longTerm.compute(agentConfigId, (key, existing) -> {
            List<MemoryEntry> list = existing == null ? new ArrayList<>() : new ArrayList<>(existing);
            list.add(entry);
            if (list.size() > LONG_TERM_LIMIT) {
                list = new ArrayList<>(list.subList(list.size() - LONG_TERM_LIMIT, list.size()));
            }
            return List.copyOf(list);
        });
    }

    @Override
    public void appendShortTerm(String gameId, String playerId, MemoryEntry entry) {
        shortTerm.compute(key(gameId, playerId), (key, existing) -> {
            List<MemoryEntry> list = existing == null ? new ArrayList<>() : new ArrayList<>(existing);
            list.add(entry);
            if (list.size() > SHORT_TERM_LIMIT) {
                list = new ArrayList<>(list.subList(list.size() - SHORT_TERM_LIMIT, list.size()));
            }
            return List.copyOf(list);
        });
    }

    @Override
    public void clearShortTerm(String gameId, String playerId) {
        shortTerm.remove(key(gameId, playerId));
    }

    /**
     * 跨局记忆的隐私过滤（agent-runtime.md §7 规则 2）。
     *
     * <p>规则：
     * <ol>
     *   <li>{@code STRATEGY} 与 {@code REFLECTION} 允许——它们的语义是"我该怎么打"，不含具体座位身份；</li>
     *   <li>{@code FACT} 与 {@code EPISODE} 需要额外检查：出现"具体座位号 + 角色断言"的组合即拒绝；</li>
     *   <li>任何条目只要出现"某号是某角色"的模式就拒绝，无论类型。</li>
     * </ol>
     */
    boolean isPrivacySafe(MemoryEntry entry) {
        if (containsSeatRoleAssertion(entry.content())) {
            return false;
        }
        return entry.type() == MemoryEntry.Type.STRATEGY || entry.type() == MemoryEntry.Type.REFLECTION;
    }

    /**
     * 是否出现"座位 + 角色"的绑定断言。
     *
     * <p>这是保守判断：只要同一条文本里同时出现数字座位与角色词，就认为它携带了具体对局的身份信息。
     * 宁可少记一条策略，也不让一条记忆在下一局变成跨局泄漏。
     */
    private boolean containsSeatRoleAssertion(String content) {
        if (content == null) {
            return false;
        }
        boolean mentionsRole = content.contains("预言家") || content.contains("女巫")
                || content.contains("猎人") || content.contains("狼人") || content.contains("村民");
        if (!mentionsRole) {
            return false;
        }
        for (int index = 0; index < content.length(); index++) {
            char ch = content.charAt(index);
            if (ch >= '1' && ch <= '9') {
                return true;
            }
        }
        return false;
    }

    /** 因隐私过滤被拒绝的条目数，供测试与运维断言"过滤真的在生效"。 */
    public int rejectedForPrivacyCount() {
        return rejectedForPrivacy.size();
    }

    public List<String> rejectedForPrivacy() {
        return List.copyOf(rejectedForPrivacy);
    }

    public int longTermSize(String agentConfigId) {
        return longTerm.getOrDefault(agentConfigId, List.of()).size();
    }

    private String key(String gameId, String playerId) {
        return gameId + ":" + playerId;
    }
}
