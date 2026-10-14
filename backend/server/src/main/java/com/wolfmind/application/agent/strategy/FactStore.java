package com.wolfmind.application.agent.strategy;

import com.wolfmind.application.agent.model.AgentContext;
import com.wolfmind.application.agent.model.AgentView;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;

/**
 * 本局事实的只读索引（strategy-layer.md §2 的 {@code FactStore}、§3）。
 *
 * <p>它把 {@link AgentContext.Facts}（不可变记录）变成可查询的只读视图，供两个打分模型与
 * 候选生成复用。三条不能破的规矩：
 * <ol>
 *   <li>只装角色可见的事实：{@code JUDGE} 事实（{@code PLAYER_KILLED} 原始记录）与
 *       他人 {@code PRIVATE} 事实在结构上就进不来，它们根本不在 {@link AgentView} 里；</li>
 *   <li>纯只读：任何"顺手写回"都会让同一回合执行两次得到不同结果；</li>
 *   <li>索引构建顺序固定：全部用插入序集合，保证同一输入产生同一迭代顺序。</li>
 * </ol>
 *
 * <p>私有事实按 {@link AgentView.PrivateFact.FactKind} 这个封闭词表归类；未知 kind
 * （{@code kindOrNull() == null}）一律按"没有该事实"处理——词表新增取值时旧代码应当优雅退化，
 * 而不是让整局崩掉，也不能把不认识的事实猜成某种真值。
 */
public final class FactStore {

    private final AgentView view;
    private final AgentContext.Facts facts;
    private final Map<Integer, List<AgentContext.SpeechFact>> speechesBySeat = new LinkedHashMap<>();
    private final Map<Integer, List<AgentContext.VoteFact>> votesByVoter = new LinkedHashMap<>();
    private final Map<Integer, List<AgentContext.ClaimFact>> claimsBySeat = new LinkedHashMap<>();
    private final Set<Integer> teammates = new LinkedHashSet<>();
    private final List<SkillSignal> skillSignals = new ArrayList<>();

    public FactStore(AgentView view, AgentContext.Facts facts) {
        this.view = view;
        this.facts = facts;
        indexSpeeches();
        indexVotes();
        indexClaims();
    }

    public static FactStore of(AgentView view, AgentContext.Facts facts) {
        return new FactStore(view, facts);
    }

    public AgentView view() {
        return view;
    }

    public AgentContext.Facts facts() {
        return facts;
    }

    public List<AgentContext.SpeechFact> speeches() {
        return facts.speeches();
    }

    public List<AgentContext.VoteFact> votes() {
        return facts.votes();
    }

    public List<AgentContext.DeathFact> deaths() {
        return facts.deaths();
    }

    public List<AgentContext.ClaimFact> claims() {
        return facts.claims();
    }

    public List<AgentContext.PrivateFactView> privateFacts() {
        return facts.privateFacts();
    }

    public List<AgentContext.SpeechFact> speechesBy(int seatNo) {
        return speechesBySeat.getOrDefault(seatNo, List.of());
    }

    public List<AgentContext.VoteFact> votesBy(int voterSeat) {
        return votesByVoter.getOrDefault(voterSeat, List.of());
    }

    public List<AgentContext.ClaimFact> claimsBy(int seatNo) {
        return claimsBySeat.getOrDefault(seatNo, List.of());
    }

    /** 公开发言里的技能信号（"我救了 5 号"这类公开宣称，§3 的 {@code SKILL_SIGNAL}）。 */
    public List<SkillSignal> skillSignals() {
        return List.copyOf(skillSignals);
    }

    /** 本座位可见的狼队友座位集合（{@code WOLF_TEAMMATE}）。 */
    public Set<Integer> knownTeammates() {
        return Set.copyOf(teammates);
    }

    /** 解药是否已用（{@code WITCH_SAVED} 出现即已用；候选集不应再给出必然被拒的解药选项）。 */
    public boolean antidoteUsed() {
        return hasFact(AgentView.PrivateFact.FactKind.WITCH_SAVED, null);
    }

    /** 毒药是否已用（{@code WITCH_POISONED} 出现即已用）。 */
    public boolean poisonUsed() {
        return hasFact(AgentView.PrivateFact.FactKind.WITCH_POISONED, null);
    }

    /** 本座位私有事实里是否记录了"解药用在某座位身上"。 */
    public boolean ownSaveRecord(int seatNo) {
        return hasFact(AgentView.PrivateFact.FactKind.WITCH_SAVED, seatNo);
    }

    /** 本座位私有事实里是否记录了"毒药用在某座位身上"。 */
    public boolean ownPoisonRecord(int seatNo) {
        return hasFact(AgentView.PrivateFact.FactKind.WITCH_POISONED, seatNo);
    }

    /** 预言家查验真值：{@code WOLF} / {@code GOOD}；没有自己的查验记录时为空。 */
    public Optional<String> inspectVerdict(int seatNo) {
        return facts.privateFacts().stream()
                .filter(fact -> kindOf(fact) == AgentView.PrivateFact.FactKind.SEER_INSPECT)
                .filter(fact -> fact.targetSeat() == seatNo)
                .map(fact -> normalizeVerdict(fact.value()))
                .filter(verdict -> !verdict.isEmpty())
                .findFirst();
    }

    /** 该座位是否被本座位的私有查验认定为狼。 */
    public boolean isCheckedWolf(int seatNo) {
        return inspectVerdict(seatNo).map("WOLF"::equals).orElse(false);
    }

    /** 该座位是否被本座位的私有查验认定为好人。 */
    public boolean isCheckedGood(int seatNo) {
        return inspectVerdict(seatNo).map("GOOD"::equals).orElse(false);
    }

    /**
     * 当夜被刀者：女巫解药候选的唯一来源（§5）。
     *
     * <p>只认当前轮的 {@code NIGHT_KILL}：用上一夜的刀口去生成解药候选，会产出一个必然被
     * 领域拒绝的目标（"解药只能救当夜被狼刀的目标"）。{@code round} 未填写（0）时按当夜处理，
     * 兼容尚未填写轮次的投影实现。
     */
    public Optional<Integer> nightKillVictim() {
        int currentRound = view == null ? 0 : view.round();
        return facts.privateFacts().stream()
                .filter(fact -> kindOf(fact) == AgentView.PrivateFact.FactKind.NIGHT_KILL)
                .filter(fact -> fact.round() == currentRound || fact.round() <= 0)
                .map(AgentContext.PrivateFactView::targetSeat)
                .filter(seatNo -> seatNo > 0)
                .findFirst();
    }

    /** 某个座位的最近一次非弃权投票目标。 */
    public Optional<Integer> lastVoteTargetOf(int voterSeat) {
        List<AgentContext.VoteFact> votes = votesBy(voterSeat);
        for (int i = votes.size() - 1; i >= 0; i--) {
            AgentContext.VoteFact vote = votes.get(i);
            if (!vote.abstain() && vote.targetSeat() != null) {
                return Optional.of(vote.targetSeat());
            }
        }
        return Optional.empty();
    }

    /** 指定轮次的票型分布，键按座位号升序。 */
    public Map<Integer, Integer> voteTally(int round) {
        Map<Integer, Integer> tally = new TreeMap<>();
        for (AgentContext.VoteFact vote : facts.votes()) {
            if (vote.round() != round || vote.abstain() || vote.targetSeat() == null) {
                continue;
            }
            tally.merge(vote.targetSeat(), 1, Integer::sum);
        }
        return tally;
    }

    /** 当前轮次的票型分布。 */
    public Map<Integer, Integer> voteTally() {
        return voteTally(view == null ? 0 : view.round());
    }

    /** 公开事件条数：时间线是否需要压缩由它决定（agent-runtime.md §6 规则 2）。 */
    public int publicEventCount() {
        return facts.speeches().size() + facts.votes().size() + facts.deaths().size();
    }

    /** 在他人发言中点名过该座位的发言者集合（影响力因子的"被关注度"代理）。 */
    public Set<Integer> mentionersOf(int seatNo) {
        Set<Integer> result = new LinkedHashSet<>();
        for (AgentContext.SpeechFact speech : facts.speeches()) {
            if (speech.seatNo() != seatNo && speech.mentions().contains(seatNo)) {
                result.add(speech.seatNo());
            }
        }
        return result;
    }

    /** 公开宣称把该座位认定为好人（预言家金水 / 查验好人），用于"公开可信度"。 */
    public boolean publiclyTrusted(int seatNo) {
        return facts.claims().stream()
                .anyMatch(claim -> claim.targetSeat() != null
                        && claim.targetSeat() == seatNo
                        && "GOOD".equals(normalizeVerdict(claim.result())));
    }

    /** 某座位公开给出的可验证结论条数（信息量因子的分子）。 */
    public int verifiableConclusionsBy(int seatNo) {
        return (int) claimsBy(seatNo).stream()
                .filter(claim -> claim.targetSeat() != null && claim.result() != null && !claim.result().isBlank())
                .count();
    }

    /**
     * 查验结论归一：只认"狼"与"好人"两档，其余返回空串（未知不等于好人）。
     *
     * <p>词表里 {@code SEER_INSPECT} 的取值是 {@code WOLF} / {@code VILLAGE}，
     * 这里同时容忍 {@code GOOD} 与中文写法，避免同一语义两种拼写导致真值丢失。
     */
    public static String normalizeVerdict(String value) {
        String text = StrategyCodes.normalize(value);
        if (text.isEmpty()) {
            return "";
        }
        if (text.contains("WOLF") || text.contains("狼")) {
            return "WOLF";
        }
        if (text.contains("GOOD") || text.contains("VILLAGE") || text.contains("HUMAN")
                || text.contains("好人") || text.contains("民")) {
            return "GOOD";
        }
        return "";
    }

    /** 解析私有事实的词表值；未知 kind 返回 {@code null}，由调用方按"没有该事实"处理。 */
    public static AgentView.PrivateFact.FactKind kindOf(AgentContext.PrivateFactView fact) {
        if (fact == null || fact.kind() == null) {
            return null;
        }
        try {
            return AgentView.PrivateFact.FactKind.valueOf(fact.kind().strip().toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException exception) {
            return null;
        }
    }

    private boolean hasFact(AgentView.PrivateFact.FactKind kind, Integer seatNo) {
        return facts.privateFacts().stream()
                .filter(fact -> kindOf(fact) == kind)
                .anyMatch(fact -> seatNo == null || fact.targetSeat() == seatNo);
    }

    private void indexSpeeches() {
        for (AgentContext.SpeechFact speech : facts.speeches()) {
            speechesBySeat.computeIfAbsent(speech.seatNo(), key -> new ArrayList<>()).add(speech);
        }
    }

    private void indexVotes() {
        for (AgentContext.VoteFact vote : facts.votes()) {
            votesByVoter.computeIfAbsent(vote.voterSeat(), key -> new ArrayList<>()).add(vote);
        }
    }

    private void indexClaims() {
        for (AgentContext.ClaimFact claim : facts.claims()) {
            claimsBySeat.computeIfAbsent(claim.seatNo(), key -> new ArrayList<>()).add(claim);
            String result = StrategyCodes.normalize(claim.result());
            int target = claim.targetSeat() == null ? 0 : claim.targetSeat();
            if ("SAVE".equals(result)) {
                skillSignals.add(new SkillSignal("SAVE", claim.seatNo(), target, claim.round()));
            } else if ("POISON".equals(result)) {
                skillSignals.add(new SkillSignal("POISON", claim.seatNo(), target, claim.round()));
            }
        }
        for (AgentContext.PrivateFactView fact : facts.privateFacts()) {
            if (kindOf(fact) == AgentView.PrivateFact.FactKind.WOLF_TEAMMATE && fact.targetSeat() > 0) {
                teammates.add(fact.targetSeat());
            }
        }
    }

    /** 公开的技能信号（§3 的 {@code SKILL_SIGNAL} 折叠结果）。 */
    public record SkillSignal(String kind, int actorSeat, int targetSeat, int round) {}
}
