package com.wolfmind.application.agent.strategy;

import com.wolfmind.application.agent.model.AgentContext;
import com.wolfmind.application.agent.model.AgentView;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * 怀疑模型：村民阵营视角"谁最像狼"（strategy-layer.md §4.2）。
 *
 * <p>五个因子的权重取自文档，一字不改：
 * <pre>
 * 宣称矛盾 0.30 | 投票一致性 0.25 | 发言规避 0.20 | 出局关联 0.15 | 时间线 0.10
 * </pre>
 *
 * <p><b>真值来源</b>：预言家视角的"宣称矛盾"直接读自己的 {@code PRIVATE_FACT} 查验结果，
 * 这是它唯一的真值来源（§4.2 末段）；其他角色只能用公开事实推断，例如"出现两个预言家宣称"
 * 意味着至少一个在说假话。两者共用同一个公式入口，谁有真值谁优先。
 *
 * <p><b>两条受视图限制的落地口径</b>（已在交付报告中留痕）：
 * <ul>
 *   <li>{@code AgentView.votes} 只携带"当前投票轮"的公开票，因此"是否总投好人"退化为
 *       "本轮是否投了我已确认的好人"，"与他人投票的对立关系"用本轮票型分布衡量；</li>
 *   <li>同理，"关键轮次的行为突变"用发言活跃度的跨轮突变与"死亡公布之后才首次发声"衡量，
 *       而不是跨轮票型变化——跨轮票型在视图里拿不到。不为了凑公式去推断视图里没有的数据。</li>
 * </ul>
 *
 * <p>纯函数：不用随机数；排序平局按 {@link TieBreak}（§13 末条）。
 */
public final class SuspicionModel {

    /** §4.2 的权重。 */
    public static final double WEIGHT_CONTRADICTION = 0.30;
    public static final double WEIGHT_VOTE_CONSISTENCY = 0.25;
    public static final double WEIGHT_EVASION = 0.20;
    public static final double WEIGHT_LINK = 0.15;
    public static final double WEIGHT_TIMELINE = 0.10;

    /** 平局键的用途名。 */
    public static final String TIE_BREAK_PURPOSE = "SUSPICION";

    /** 总分降序、平局按 {@link TieBreak}，最后按座位号。 */
    public static Comparator<SuspicionAssessment> order(TieBreak tieBreak) {
        return Comparator.comparingDouble(SuspicionAssessment::score).reversed()
                .thenComparingLong(assessment -> tieBreak.key(assessment.seatNo()))
                .thenComparingInt(SuspicionAssessment::seatNo);
    }

    /** 评估一个座位。 */
    public SuspicionAssessment assess(AgentView view, FactStore facts, int seatNo) {
        double contradiction = contradiction(view, facts, seatNo);
        double voteConsistency = voteConsistency(view, facts, seatNo);
        double evasion = evasion(view, facts, seatNo);
        double link = exileLink(view, facts, seatNo);
        double timeline = timelineShift(view, facts, seatNo);

        double base = clamp01(WEIGHT_CONTRADICTION * contradiction
                + WEIGHT_VOTE_CONSISTENCY * voteConsistency
                + WEIGHT_EVASION * evasion
                + WEIGHT_LINK * link
                + WEIGHT_TIMELINE * timeline);
        double score = applyTruth(base, view, facts, seatNo);

        Map<String, Double> factors = new LinkedHashMap<>();
        factors.put(SuspicionAssessment.CONTRADICTION, contradiction);
        factors.put(SuspicionAssessment.VOTE_CONSISTENCY, voteConsistency);
        factors.put(SuspicionAssessment.EVASION, evasion);
        factors.put(SuspicionAssessment.LINK, link);
        factors.put(SuspicionAssessment.TIMELINE, timeline);
        // 真值修正单独列出：没有查验结果的座位该项恒为 0，总分就是五个因子的加权和
        factors.put(SuspicionAssessment.TRUTH_ADJUSTMENT, score - base);

        return new SuspicionAssessment(seatNo, score, factors,
                tags(view, facts, seatNo, contradiction, voteConsistency, evasion, link, timeline));
    }

    /**
     * 真值修正：预言家已有查验结果时，让排序与真值一致（§4.2 末段的"唯一真值来源"）。
     *
     * <p>为什么需要它：查验结果只进"宣称矛盾"这一个 0.30 权重的因子，而其余四个因子
     * 最多能叠加 0.70——一个被查验为好人的座位仍然可能被公开推断推到高嫌疑，这会让预言家
     * 的排序与它自己手里的真值相矛盾。修正只做单调升降权（狼人下限 0.8、好人上限 0.1），
     * 不改变因子的定义与权重，且修正量在 {@code factors} 里可查。
     */
    private double applyTruth(double base, AgentView view, FactStore facts, int seatNo) {
        if (facts.isCheckedWolf(seatNo)) {
            return Math.max(base, 0.8);
        }
        if (facts.isCheckedGood(seatNo)) {
            return Math.min(base, 0.1);
        }
        return base;
    }

    /** 评估一组座位并按 {@link #order} 排序。 */
    public List<SuspicionAssessment> rank(AgentView view, FactStore facts, Collection<Integer> seats) {
        List<SuspicionAssessment> result = new ArrayList<>();
        if (view == null || facts == null || seats == null) {
            return result;
        }
        for (int seatNo : new LinkedHashSet<>(seats)) {
            result.add(assess(view, facts, seatNo));
        }
        result.sort(order(TieBreak.of(view, TIE_BREAK_PURPOSE)));
        return result;
    }

    /** 存活的他人，按怀疑度降序（投票/毒药/开枪候选的基础集合）。 */
    public List<SuspicionAssessment> rankAliveOthers(AgentView view, FactStore facts) {
        List<Integer> seats = new ArrayList<>();
        if (view != null) {
            for (AgentView.SeatView seat : view.aliveOthers()) {
                seats.add(seat.seatNo());
            }
        }
        return rank(view, facts, seats);
    }

    /** 当前怀疑度最高的存活座位；没有可用候选时为空。 */
    public java.util.Optional<Integer> topSuspectSeat(AgentView view, FactStore facts) {
        List<SuspicionAssessment> ranked = rankAliveOthers(view, facts);
        return ranked.isEmpty()
                ? java.util.Optional.empty()
                : java.util.Optional.of(ranked.get(0).seatNo());
    }

    /**
     * 宣称矛盾（0.30）：与已知私有事实冲突，或出现多个同类身份宣称。
     *
     * <p>预言家先看真值：被自己查验为狼的座位直接满分，被查验为好人的座位直接零分
     * （真值优先于任何公开推断）。其他角色只能数宣称：同一神职出现两个宣称者时，
     * 每个宣称者都拿到较高的矛盾分——两人里至少有一个在说假话。
     */
    double contradiction(AgentView view, FactStore facts, int seatNo) {
        boolean iAmSeer = view != null && view.me() != null
                && StrategyCodes.SEER.equals(StrategyCodes.normalize(view.me().role()));
        if (iAmSeer) {
            if (facts.isCheckedWolf(seatNo)) {
                return 1.0;
            }
            if (facts.isCheckedGood(seatNo)) {
                return 0.0;
            }
        }
        // 公开推断：同一神职出现两个宣称者时，每个宣称者都拿到较高矛盾分（至少一人在说假话）；
        // 只有一个宣称者时只给轻微分——那可能正是真预言家，不该被当成嫌疑人。
        Map<String, Integer> claimantCount = new TreeMap<>();
        for (AgentContext.ClaimFact claim : facts.claims()) {
            String role = StrategyCodes.normalize(claim.claimedRole());
            if (isDivineRole(role)) {
                claimantCount.merge(role, 1, Integer::sum);
            }
        }
        double duplicate = 0.0;
        for (AgentContext.ClaimFact claim : facts.claimsBy(seatNo)) {
            String role = StrategyCodes.normalize(claim.claimedRole());
            if (!isDivineRole(role)) {
                continue;
            }
            int claimants = claimantCount.getOrDefault(role, 0);
            duplicate = Math.max(duplicate, claimants >= 2 ? 0.5 : 0.15);
        }
        return clamp01(duplicate);
    }

    /**
     * 投票一致性（0.25）：是否投了我已确认的好人，以及是否与本轮多数票对立。
     *
     * <p>"投好人"是这个因子里最强的信号：一次就足以说明问题，因此不做次数衰减。
     */
    double voteConsistency(AgentView view, FactStore facts, int seatNo) {
        List<AgentContext.VoteFact> votes = facts.votesBy(seatNo);
        if (votes.isEmpty()) {
            return 0.4;
        }
        boolean hostile = false;
        Integer target = null;
        boolean abstained = false;
        for (AgentContext.VoteFact vote : votes) {
            if (vote.abstain() || vote.targetSeat() == null) {
                abstained = true;
                continue;
            }
            target = vote.targetSeat();
            boolean votedKnownGood = facts.isCheckedGood(vote.targetSeat());
            boolean votedMe = view != null && view.me() != null && vote.targetSeat() == view.me().seatNo();
            if (votedKnownGood || votedMe) {
                hostile = true;
            }
        }
        double hostileFactor = hostile ? 1.0 : 0.0;
        double agreement = majorityAgreement(facts, target, abstained);
        return clamp01(0.6 * hostileFactor + 0.4 * (1.0 - agreement));
    }

    private double majorityAgreement(FactStore facts, Integer target, boolean abstained) {
        Map<Integer, Integer> tally = new TreeMap<>(facts.voteTally());
        if (tally.isEmpty()) {
            return 0.5;
        }
        int max = tally.values().stream().mapToInt(Integer::intValue).max().orElse(0);
        List<Integer> leaders = tally.entrySet().stream()
                .filter(entry -> entry.getValue() == max)
                .map(Map.Entry::getKey)
                .toList();
        if (leaders.size() != 1) {
            // 平票：没有"多数票"可言，保持中性，避免把平票当成对立
            return 0.5;
        }
        if (abstained || target == null) {
            return 0.5;
        }
        return target.equals(leaders.get(0)) ? 1.0 : 0.0;
    }

    /** 发言规避（0.20）：沉默 + 含糊（不点名、不给结论）。 */
    double evasion(AgentView view, FactStore facts, int seatNo) {
        if (view == null || facts.speeches().isEmpty()) {
            // 还没有任何人发言时，"不说话"没有信息量，给中性基线而不是全员满分
            return 0.3;
        }
        int speeches = facts.speechesBy(seatNo).size();
        int round = Math.max(1, view.round());
        double silence = clamp01(1.0 - speeches / (double) round);
        Set<Integer> mentioned = new LinkedHashSet<>();
        for (AgentContext.SpeechFact speech : facts.speechesBy(seatNo)) {
            mentioned.addAll(speech.mentions());
        }
        int conclusions = facts.verifiableConclusionsBy(seatNo);
        double vagueness = clamp01(1.0 - (mentioned.size() + conclusions) / 3.0);
        return clamp01(0.5 * silence + 0.5 * vagueness);
    }

    /**
     * 出局关联（0.15）：与"已知狼人"的关系链。
     *
     * <p>"已知"只认本座位的私有查验结果与视图里可见的角色字段——正是 §4.2 的"若已知"，
     * 不是把公开死亡当成狼人（视图里的死亡不含死因与角色，那不是可见事实）。
     */
    double exileLink(AgentView view, FactStore facts, int seatNo) {
        if (knownWolfSeats(view, facts).contains(seatNo)) {
            return 1.0;
        }
        Map<Integer, Integer> myVotes = voteTargetsByRound(facts, seatNo);
        if (myVotes.isEmpty()) {
            return 0.0;
        }
        int links = 0;
        for (int wolfSeat : knownWolfSeats(view, facts)) {
            Map<Integer, Integer> wolfVotes = voteTargetsByRound(facts, wolfSeat);
            for (Map.Entry<Integer, Integer> entry : myVotes.entrySet()) {
                if (entry.getValue().equals(wolfVotes.get(entry.getKey()))) {
                    links++;
                }
            }
        }
        return clamp01(links / 2.0);
    }

    /**
     * 时间线（0.10）：关键轮次的行为突变。
     *
     * <p>两个结构信号：相邻轮次发言活跃度的突变（沉默变活跃或反过来），
     * 以及"首次发声发生在已有死亡公布之后"——在关键事件后突然开口是复盘里最常被追问的行为。
     */
    double timelineShift(AgentView view, FactStore facts, int seatNo) {
        Map<Integer, Integer> activity = new TreeMap<>();
        for (AgentContext.SpeechFact speech : facts.speechesBy(seatNo)) {
            activity.merge(speech.round(), 1, Integer::sum);
        }
        if (activity.isEmpty()) {
            return 0.0;
        }
        double changes = 0.0;
        Integer previousRound = null;
        Integer previousCount = null;
        for (Map.Entry<Integer, Integer> entry : activity.entrySet()) {
            if (previousRound != null && entry.getKey() == previousRound + 1
                    && Math.abs(entry.getValue() - previousCount) >= 2) {
                changes++;
            }
            previousRound = entry.getKey();
            previousCount = entry.getValue();
        }
        int firstSpeechRound = activity.keySet().iterator().next();
        int firstDeathRound = facts.deaths().stream()
                .mapToInt(AgentContext.DeathFact::round)
                .min()
                .orElse(Integer.MAX_VALUE);
        if (firstDeathRound != Integer.MAX_VALUE && firstSpeechRound > firstDeathRound) {
            changes++;
        }
        return clamp01(changes / 3.0);
    }

    private List<String> tags(
            AgentView view, FactStore facts, int seatNo, double contradiction, double voteConsistency,
            double evasion, double link, double timeline) {
        List<String> tags = new ArrayList<>();
        if (facts.isCheckedWolf(seatNo)) {
            tags.add("CHECKED_WOLF");
        }
        if (facts.isCheckedGood(seatNo)) {
            tags.add("CHECKED_GOOD");
        }
        if (contradiction >= 0.5) {
            tags.add("CLAIM_CONFLICT");
        }
        if (voteConsistency >= 0.6) {
            tags.add("HOSTILE_VOTE");
        }
        if (evasion >= 0.6) {
            tags.add("EVASIVE");
        }
        if (link >= 0.5) {
            tags.add("WOLF_LINK");
        }
        if (timeline >= 0.34) {
            tags.add("TIMELINE_SHIFT");
        }
        if (view != null && view.me() != null
                && facts.votesBy(seatNo).stream()
                        .anyMatch(vote -> !vote.abstain() && vote.targetSeat() != null
                                && vote.targetSeat() == view.me().seatNo())) {
            tags.add("VOTED_ME");
        }
        return tags;
    }

    /** 本座位可见的"已知狼人"集合：私有查验结果、视图角色字段、可见队友名单。 */
    Set<Integer> knownWolfSeats(AgentView view, FactStore facts) {
        Set<Integer> result = new LinkedHashSet<>();
        if (view == null) {
            return result;
        }
        List<Integer> seats = new ArrayList<>();
        if (view.me() != null) {
            seats.add(view.me().seatNo());
        }
        for (AgentView.SeatView seat : view.other()) {
            seats.add(seat.seatNo());
        }
        for (int seatNo : seats) {
            if (facts.isCheckedWolf(seatNo) || CampRules.isWolfSeat(view, facts, seatNo)) {
                result.add(seatNo);
            }
        }
        return result;
    }

    private static Map<Integer, Integer> voteTargetsByRound(FactStore facts, int voterSeat) {
        Map<Integer, Integer> result = new TreeMap<>();
        for (AgentContext.VoteFact vote : facts.votesBy(voterSeat)) {
            if (!vote.abstain() && vote.targetSeat() != null) {
                result.put(vote.round(), vote.targetSeat());
            }
        }
        return result;
    }

    private static boolean isDivineRole(String role) {
        return StrategyCodes.SEER.equals(role)
                || StrategyCodes.WITCH.equals(role)
                || StrategyCodes.HUNTER.equals(role);
    }

    private static double clamp01(double value) {
        if (Double.isNaN(value)) {
            return 0.0;
        }
        return Math.max(0.0, Math.min(1.0, value));
    }
}
