package com.wolfmind.application.agent.model;

import java.util.List;
import java.util.Map;

/**
 * 装配好的回合上下文（agent-runtime.md §6）。
 *
 * <p>五个分区各有独立预算，顺序与边界固定，便于缓存与审计（strategy-layer.md §7）：
 * {@code SYSTEM} → {@code PERSONA} → {@code CONTEXT} → {@code PRIVATE} → {@code MEMORY} →
 * {@code CANDIDATES} → {@code TASK}。
 *
 * <p>它同时是"信息边界"的载体：装配器只接受角色可见视图与候选集，**不接受完整事件流**，
 * 因此 {@code JUDGE} 事实与他人 {@code PRIVATE} 事实在结构上就进不来（§6 规则 1）。
 *
 * @param system      规则摘要、角色能力、硬约束清单、输出契约
 * @param persona     语气与人格（只影响表达）
 * @param context     公开事件时间线（按轮次分组，关键事件置顶）
 * @param privateNotes 仅自己可见的信息（查验结果、用药记录、狼队友）
 * @param memory      本局摘要 + 跨局策略摘录
 * @param candidates  候选动作集与打分、命中的硬约束、被排除的选项及原因
 * @param task        本回合要求
 * @param facts       结构化事实（供打分复用，不直接进 prompt）
 * @param truncationLevel 时间线截断层级，用于 Trace：0 未截断 / 1 关键优先 / 2 旧事件摘要化
 */
public record AgentContext(
        String system,
        String persona,
        String context,
        String privateNotes,
        String memory,
        List<Candidate> candidates,
        String task,
        Facts facts,
        int truncationLevel) {

    public AgentContext {
        candidates = List.copyOf(candidates);
    }

    /** 全部非空分区，按固定顺序拼接；供 LLM 调用与测试断言。 */
    public Map<String, String> sections() {
        Map<String, String> map = new java.util.LinkedHashMap<>();
        map.put("SYSTEM", system == null ? "" : system);
        map.put("PERSONA", persona == null ? "" : persona);
        map.put("CONTEXT", context == null ? "" : context);
        map.put("PRIVATE", privateNotes == null ? "" : privateNotes);
        map.put("MEMORY", memory == null ? "" : memory);
        map.put("CANDIDATES", renderCandidates());
        map.put("TASK", task == null ? "" : task);
        return Map.copyOf(map);
    }

    /** 候选集的可读渲染：一行一条，含打分与命中的硬约束。 */
    public String renderCandidates() {
        StringBuilder builder = new StringBuilder();
        for (Candidate candidate : candidates) {
            builder.append("- ").append(candidate.actionType());
            if (candidate.targetSeatNo() != null) {
                builder.append(' ').append(candidate.targetSeatNo()).append("号");
            }
            builder.append(" | score=").append(String.format(java.util.Locale.ROOT, "%.2f", candidate.score()));
            if (!candidate.tags().isEmpty()) {
                builder.append(" | ").append(String.join(",", candidate.tags()));
            }
            if (!candidate.constraints().isEmpty()) {
                builder.append(" | 约束: ").append(String.join("; ", candidate.constraints()));
            }
            builder.append('\n');
        }
        return builder.toString();
    }

    /**
     * 上下文里是否出现了给定的座位私有事实指纹。
     *
     * <p>供 LeakGuard 的"可见指纹集合"与测试使用：它检查的是**装配结果**，
     * 而不是事后扫描文本，因此能在调用 LLM 之前就拦住泄漏。
     */
    public boolean mentionsAnywhere(String needle) {
        if (needle == null || needle.isBlank()) {
            return false;
        }
        return sections().values().stream().anyMatch(text -> text.contains(needle));
    }

    /**
     * 结构化事实集合（strategy-layer.md §3）。
     *
     * <p>打分只用角色可见的事实；{@code JUDGE} 事实（{@code PLAYER_KILLED} 原始记录）
     * 不进入这里，这是 AI 不作弊的实现级保证。
     */
    public record Facts(
            List<SpeechFact> speeches,
            List<VoteFact> votes,
            List<DeathFact> deaths,
            List<ClaimFact> claims,
            List<PrivateFactView> privateFacts) {

        public Facts {
            speeches = List.copyOf(speeches);
            votes = List.copyOf(votes);
            deaths = List.copyOf(deaths);
            claims = List.copyOf(claims);
            privateFacts = List.copyOf(privateFacts);
        }

        public static Facts empty() {
            return new Facts(List.of(), List.of(), List.of(), List.of(), List.of());
        }
    }

    /** 一条发言事实。 */
    public record SpeechFact(int seatNo, int round, String phase, int length, List<Integer> mentions) {
        public SpeechFact {
            mentions = List.copyOf(mentions);
        }
    }

    /** 一条投票事实。 */
    public record VoteFact(int voterSeat, Integer targetSeat, boolean abstain, int round) {}

    /** 一条死亡事实（已公布，不含死因）。 */
    public record DeathFact(int seatNo, int round) {}

    /** 一条角色宣称（从发言文本解析）。 */
    public record ClaimFact(int seatNo, int round, String claimedRole, Integer targetSeat, String result) {}

    /** 一条私有事实（仅本人可见）。 */
    public record PrivateFactView(String kind, int targetSeat, String value, int round) {}
}
