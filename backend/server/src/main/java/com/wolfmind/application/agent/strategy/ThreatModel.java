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

/**
 * 威胁模型：狼队视角"杀谁收益最高"（strategy-layer.md §4.1）。
 *
 * <p>五个因子的权重取自文档，一字不改：
 * <pre>
 * 神职嫌疑 0.35 | 信息量 0.20 | 影响力 0.20 | 存活轮次 0.10 | 击杀风险 0.15
 * </pre>
 *
 * <p>两个"文档给了名字、没给公式"的因子在这里按下列口径实现，口径写进注释以便复核：
 * <ul>
 *   <li><b>击杀风险</b>是收益的反面：文档把它列成 0.15 的权重，而 §1 的狼人策略是
 *       "降低暴露风险"，因此这里把它写成 {@code 0.15 × 击杀安全性}（安全性 = 1 − 风险），
 *       等价于给总分减去 {@code 0.15 × 风险}，同时保持总分落在 {@code 0..1}；
 *       风险本身由"刀口是否与本狼票型冲突"与"目标是否已被公开认定为好人"合成；</li>
 *   <li><b>存活轮次</b>按"存活轮数 ÷ 当前轮数"计算。对存活候选它自然饱和到 1.0，
 *       这是文档"存活越久威胁越高"的必然结果——活着的候选人人都活到了现在。
 *       不额外发明区分度（那会变成第二条文档没有的规则）。</li>
 * </ul>
 *
 * <p>纯函数：不读时钟、不用随机数，排序平局按 {@link TieBreak}（§13 末条）。
 */
public final class ThreatModel {

    /** §4.1 的权重。 */
    public static final double WEIGHT_DIVINE_SUSPECT = 0.35;
    public static final double WEIGHT_INFORMATION = 0.20;
    public static final double WEIGHT_INFLUENCE = 0.20;
    public static final double WEIGHT_SURVIVAL = 0.10;
    public static final double WEIGHT_KILL_SAFETY = 0.15;

    /** 平局键的用途名：狼队成员共用它，保证同一夜的威胁排序一致。 */
    public static final String TIE_BREAK_PURPOSE = "THREAT";

    /** 总分降序、平局按 {@link TieBreak}，最后按座位号。任何一种输入下都必须给出全序，这是可复现性的前提。 */
    public static Comparator<ThreatAssessment> order(TieBreak tieBreak) {
        return Comparator.comparingDouble(ThreatAssessment::score).reversed()
                .thenComparingLong(assessment -> tieBreak.key(assessment.seatNo()))
                .thenComparingInt(ThreatAssessment::seatNo);
    }

    /** 评估一个座位。 */
    public ThreatAssessment assess(AgentView view, FactStore facts, int seatNo) {
        double divine = divineSuspect(view, facts, seatNo);
        double information = information(view, facts, seatNo);
        double influence = influence(view, facts, seatNo);
        double survival = survival(view, facts, seatNo);
        double killSafety = 1.0 - killRisk(view, facts, seatNo);

        double score = clamp01(WEIGHT_DIVINE_SUSPECT * divine
                + WEIGHT_INFORMATION * information
                + WEIGHT_INFLUENCE * influence
                + WEIGHT_SURVIVAL * survival
                + WEIGHT_KILL_SAFETY * killSafety);

        Map<String, Double> factors = new LinkedHashMap<>();
        factors.put(ThreatAssessment.DIVINE_SUSPECT, divine);
        factors.put(ThreatAssessment.INFORMATION, information);
        factors.put(ThreatAssessment.INFLUENCE, influence);
        factors.put(ThreatAssessment.SURVIVAL, survival);
        factors.put(ThreatAssessment.KILL_SAFETY, killSafety);

        return new ThreatAssessment(seatNo, score, factors, tags(view, facts, seatNo, divine, information, influence, killSafety));
    }

    /** 评估一组座位并按 {@link #order} 排序。 */
    public List<ThreatAssessment> rank(AgentView view, FactStore facts, Collection<Integer> seats) {
        List<ThreatAssessment> result = new ArrayList<>();
        if (view == null || facts == null || seats == null) {
            return result;
        }
        for (int seatNo : new LinkedHashSet<>(seats)) {
            result.add(assess(view, facts, seatNo));
        }
        result.sort(order(TieBreak.of(view, TIE_BREAK_PURPOSE)));
        return result;
    }

    /** 存活且非狼人阵营的座位，按威胁降序（狼刀候选的基础集合）。 */
    public List<ThreatAssessment> rankAliveOpponents(AgentView view, FactStore facts) {
        List<Integer> seats = new ArrayList<>();
        if (view != null) {
            for (AgentView.SeatView seat : view.aliveOthers()) {
                if (!CampRules.isWolfSeat(view, facts, seat.seatNo())) {
                    seats.add(seat.seatNo());
                }
            }
        }
        return rank(view, facts, seats);
    }

    /**
     * 神职嫌疑：预言家宣称、给查验结论、强势带节奏（§4.1）。
     *
     * <p>宣称强度按"这个身份一旦为真对狼队的危害"给分：预言家最高，女巫次之，猎人再次。
     * 带节奏能力用发言条数与被点名广度衡量，两者都只在公开事实里取数。
     */
    double divineSuspect(AgentView view, FactStore facts, int seatNo) {
        double claimStrength = 0.0;
        boolean conclusion = false;
        for (AgentContext.ClaimFact claim : facts.claimsBy(seatNo)) {
            claimStrength = Math.max(claimStrength, roleThreat(claim.claimedRole()));
            if (claim.targetSeat() != null && claim.result() != null && !claim.result().isBlank()) {
                conclusion = true;
            }
        }
        if (claimStrength > 0.0 && conclusion) {
            claimStrength = clamp01(claimStrength + 0.1);
        }
        int speeches = facts.speechesBy(seatNo).size();
        Set<Integer> mentioned = new LinkedHashSet<>();
        for (AgentContext.SpeechFact speech : facts.speechesBy(seatNo)) {
            mentioned.addAll(speech.mentions());
        }
        double assertiveness = clamp01(0.6 * Math.min(1.0, speeches / 3.0)
                + 0.4 * Math.min(1.0, mentioned.size() / 4.0));
        return clamp01(0.65 * claimStrength + 0.35 * assertiveness);
    }

    /**
     * 信息量：发言提供的有效信息条数与可验证结论（§4.1）。
     *
     * <p>"可验证结论"指给出了目标与结果（例如"查验 5 号是狼"）；只喊口号的发言不计入，
     * 否则刷屏就能变成高威胁目标。
     */
    double information(AgentView view, FactStore facts, int seatNo) {
        int conclusions = facts.verifiableConclusionsBy(seatNo);
        int informativeSpeeches = (int) facts.speechesBy(seatNo).stream()
                .filter(speech -> !speech.mentions().isEmpty() || speech.length() >= 40)
                .count();
        return clamp01(0.6 * Math.min(1.0, conclusions / 2.0)
                + 0.4 * Math.min(1.0, informativeSpeeches / 3.0));
    }

    /**
     * 影响力：被他人跟随的程度（§4.1）。
     *
     * <p>"跟随"的判定基准是该座位公开表达过的立场：他投过的目标，加上他在发言里点名过的目标
     * （宣称/指认）。只看票型会把"喊了半天却还没投票"的带节奏者算成没有影响力，
     * 而那恰恰是狼队最想先处理掉的人。
     *
     * <p>当前轮还没有任何他人投票时（第一夜、开局），退化为"被他人点名的广度"，
     * 否则该因子在这些阶段恒为 0，五个因子里有一个永久失效。
     */
    double influence(AgentView view, FactStore facts, int seatNo) {
        Set<Integer> stance = new LinkedHashSet<>();
        facts.lastVoteTargetOf(seatNo).ifPresent(stance::add);
        for (AgentContext.ClaimFact claim : facts.claimsBy(seatNo)) {
            if (claim.targetSeat() != null && claim.targetSeat() > 0) {
                stance.add(claim.targetSeat());
            }
        }
        int otherVotes = 0;
        int followers = 0;
        for (AgentContext.VoteFact vote : facts.votes()) {
            if (vote.voterSeat() == seatNo || vote.abstain() || vote.targetSeat() == null) {
                continue;
            }
            otherVotes++;
            if (stance.contains(vote.targetSeat())) {
                followers++;
            }
        }
        if (otherVotes > 0 && !stance.isEmpty()) {
            return clamp01(followers / (double) otherVotes);
        }
        int speakers = 0;
        for (AgentContext.SpeechFact speech : facts.speeches()) {
            if (speech.seatNo() != seatNo) {
                speakers++;
            }
        }
        if (speakers == 0) {
            return 0.0;
        }
        return clamp01(facts.mentionersOf(seatNo).size() / (double) speakers);
    }

    /** 存活轮次：已经活过的轮数 ÷ 当前轮数（活着的人自然为 1.0，见类注释）。 */
    double survival(AgentView view, FactStore facts, int seatNo) {
        int round = view == null ? 0 : Math.max(1, view.round());
        boolean dead = facts.deaths().stream().anyMatch(death -> death.seatNo() == seatNo);
        if (!dead) {
            return 1.0;
        }
        int deathRound = facts.deaths().stream()
                .filter(death -> death.seatNo() == seatNo)
                .mapToInt(AgentContext.DeathFact::round)
                .max()
                .orElse(1);
        return clamp01((deathRound - 1) / (double) round);
    }

    /**
     * 击杀风险：刀口是否暴露狼队（§4.1）。
     *
     * <p>两项结构证据：
     * <ul>
     *   <li>本狼的票型与刀口是否冲突：我公开投过他，夜里刀他与我的公开立场一致，风险低；
     *       我投了别人却刀他，白天容易被"你昨天还在说别人"抓住；</li>
     *   <li>目标是否已被公开认定为好人（预言家给出"好人/金水"结论）：这样的目标夜里死掉，
     *       白天的复盘会直接指向狼队。</li>
     * </ul>
     * 没有投票记录时给中间值 0.5：不是"零风险"，也不是"必然暴露"。
     */
    public double killRisk(AgentView view, FactStore facts, int seatNo) {
        if (view == null || view.me() == null) {
            return 0.5;
        }
        Integer myLastVote = facts.lastVoteTargetOf(view.me().seatNo()).orElse(null);
        double voteMismatch;
        if (myLastVote == null) {
            voteMismatch = 0.5;
        } else {
            voteMismatch = myLastVote == seatNo ? 0.0 : 1.0;
        }
        double publicTrust = facts.publiclyTrusted(seatNo) ? 1.0 : 0.0;
        return clamp01(0.5 * voteMismatch + 0.5 * publicTrust);
    }

    private List<String> tags(
            AgentView view, FactStore facts, int seatNo,
            double divine, double information, double influence, double killSafety) {
        List<String> tags = new ArrayList<>();
        Set<String> claimedRoles = new LinkedHashSet<>();
        for (AgentContext.ClaimFact claim : facts.claimsBy(seatNo)) {
            if (claim.claimedRole() != null) {
                claimedRoles.add(StrategyCodes.normalize(claim.claimedRole()));
            }
        }
        if (claimedRoles.contains(StrategyCodes.SEER)) {
            tags.add("SEER_CLAIM");
        }
        if (claimedRoles.contains(StrategyCodes.WITCH)) {
            tags.add("WITCH_CLAIM");
        }
        if (claimedRoles.contains(StrategyCodes.HUNTER)) {
            tags.add("HUNTER_CLAIM");
        }
        if (divine >= 0.6) {
            tags.add("HIGH_THREAT");
        }
        if (information >= 0.6) {
            tags.add("INFO_RICH");
        }
        if (influence >= 0.5) {
            tags.add("INFLUENTIAL");
        }
        if (killSafety >= 0.75) {
            tags.add("SAFE_KILL");
        }
        if (facts.publiclyTrusted(seatNo)) {
            tags.add("PUBLIC_TRUSTED");
        }
        if (facts.knownTeammates().contains(seatNo)) {
            tags.add("TEAMMATE");
        }
        return tags;
    }

    private static double roleThreat(String role) {
        String normalized = StrategyCodes.normalize(role);
        return switch (normalized) {
            case StrategyCodes.SEER -> 1.0;
            case StrategyCodes.WITCH -> 0.85;
            case StrategyCodes.HUNTER -> 0.7;
            case StrategyCodes.VILLAGER, StrategyCodes.WEREWOLF -> 0.0;
            default -> 0.0;
        };
    }

    private static double clamp01(double value) {
        if (Double.isNaN(value)) {
            return 0.0;
        }
        return Math.max(0.0, Math.min(1.0, value));
    }
}
