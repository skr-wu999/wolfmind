package com.wolfmind.application.agent.strategy;

import com.wolfmind.application.agent.model.AgentContext;
import com.wolfmind.application.agent.model.AgentView;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 事实抽取：把角色可见的 {@link AgentView} 折叠成结构化事实（strategy-layer.md §3）。
 *
 * <p>三条设计约束：
 * <ol>
 *   <li><b>只读视图</b>：输入是已脱敏的 {@code AgentView}，{@code JUDGE} 事件（{@code PLAYER_KILLED}
 *       原始记录）与他人 {@code PRIVATE} 事件在结构上就不存在，因此 {@code FactStore} 里永远
 *       不会有"上帝视角"事实——这是 AI 不作弊的实现级保证；</li>
 *   <li><b>纯函数</b>：同样的视图必须得到同样的事实集合与同样的顺序，抽取过程不读时钟、
 *       不用随机数、不依赖 Map 的迭代顺序；</li>
 *   <li><b>解析只做结构化匹配</b>：发言文本是数据，用固定正则抽出"宣称"与"点名"，
 *       不做语义理解，也不把文本当指令。</li>
 * </ol>
 *
 * <p>{@code Facts} 是冻结的记录形状（发言 / 投票 / 死亡 / 宣称 / 私有事实五类），因此
 * §3 表里的 {@code VOTE_RESULT} 与 {@code SKILL_SIGNAL} 按下面方式落位：
 * 票型分布由 {@link FactStore#voteTally()} 从投票事实聚合（视图里没有计票事件，
 * 无法还原"计票结果"原文）；技能信号折叠进宣称事实（{@code claimedRole=WITCH}，
 * {@code result=SAVE|POISON}），由 {@link FactStore#skillSignals()} 取出。
 */
public final class FactExtractor {

    /** 座位点名："{@code 5号}"。 */
    private static final Pattern SEAT_MENTION = Pattern.compile("(\\d{1,2})\\s*号");

    /** 自称身份："{@code 我是预言家}"、"{@code 我才是预言家}"、"{@code 我也是女巫}"。 */
    private static final Pattern ROLE_CLAIM =
            Pattern.compile("我(?:才|就|也|正|其实)?(?:是|乃|为|即为|就是)\\s*(预言家|女巫|猎人|村民|平民|狼人)");

    /** 查验表述："{@code 查验5号是狼}"。 */
    private static final Pattern INSPECT_CLAIM =
            Pattern.compile("(?:查验|验人|验了|验过|查了|查过|验|查)\\s*(\\d{1,2})\\s*号?");

    /** 结论表述："{@code 5号是狼}"、"5号金水"、"5号查杀"。 */
    private static final Pattern VERDICT_CLAIM =
            Pattern.compile("(\\d{1,2})\\s*号\\s*(?:是|为|属于|被验为|被查为|被认定为)?\\s*(狼人|狼|好人|金水|查杀|村民)");

    /** 用药宣告："{@code 我救了5号}"。 */
    private static final Pattern SAVE_CLAIM =
            Pattern.compile("(?:救了|救下|施救|解药救|用了解药|使用了解药|解药给了)\\s*(\\d{1,2})\\s*号?");

    /** 毒药宣告："{@code 我毒了3号}"。 */
    private static final Pattern POISON_CLAIM =
            Pattern.compile("(?:毒了|毒杀|下毒|用毒|用毒药|使用毒药)\\s*(\\d{1,2})\\s*号?");

    /** 句子边界：宣称解析按句进行，避免跨句拼接出并不存在的结论。 */
    private static final Pattern SENTENCE_SPLIT = Pattern.compile("[。！？!?\\n；;]+");

    /** 抽取事实集合（{@code FactExtractor} 的对外契约）。 */
    public AgentContext.Facts extract(AgentView view) {
        if (view == null) {
            return AgentContext.Facts.empty();
        }
        Set<Integer> knownSeats = knownSeats(view);
        List<AgentContext.SpeechFact> speeches = new ArrayList<>();
        List<AgentContext.ClaimFact> claims = new ArrayList<>();
        for (AgentView.SpeechView speech : view.speeches()) {
            String content = speech.content() == null ? "" : speech.content();
            speeches.add(new AgentContext.SpeechFact(
                    speech.seatNo(),
                    speech.round(),
                    speech.phase(),
                    content.length(),
                    mentions(content, knownSeats)));
            claims.addAll(claimsOf(speech.seatNo(), speech.round(), content));
        }
        List<AgentContext.VoteFact> votes = new ArrayList<>();
        for (AgentView.VoteView vote : view.votes()) {
            votes.add(new AgentContext.VoteFact(
                    vote.voterSeat(), vote.targetSeatNo(), vote.abstain(), view.round()));
        }
        List<AgentContext.DeathFact> deaths = new ArrayList<>();
        for (AgentView.DeathView death : view.deaths()) {
            deaths.add(new AgentContext.DeathFact(death.seatNo(), death.round()));
        }
        List<AgentContext.PrivateFactView> privateFacts = new ArrayList<>();
        for (AgentView.PrivateFact fact : view.privateEvents()) {
            privateFacts.add(new AgentContext.PrivateFactView(
                    fact.kind(), fact.targetSeat(), fact.value(), fact.round()));
        }
        return new AgentContext.Facts(speeches, votes, deaths, claims, privateFacts);
    }

    /** 抽取事实并建立只读索引。 */
    public FactStore extractStore(AgentView view) {
        return new FactStore(view, extract(view));
    }

    /** 从一段发言里抽出点名到的座位号，按升序去重；视图里不存在的座位号不算点名。 */
    public static List<Integer> mentions(String content, Set<Integer> knownSeats) {
        Set<Integer> found = new TreeSet<>();
        if (content != null && !content.isEmpty()) {
            Matcher matcher = SEAT_MENTION.matcher(content);
            while (matcher.find()) {
                int seatNo = Integer.parseInt(matcher.group(1));
                if (knownSeats == null || knownSeats.contains(seatNo)) {
                    found.add(seatNo);
                }
            }
        }
        return List.copyOf(found);
    }

    /** 解析一条发言里的全部宣称（自称身份、查验结论、用药宣告、直接结论）。 */
    public static List<AgentContext.ClaimFact> claimsOf(int seatNo, int round, String content) {
        List<AgentContext.ClaimFact> claims = new ArrayList<>();
        if (content == null || content.isBlank()) {
            return claims;
        }
        Set<String> seen = new LinkedHashSet<>();
        for (String sentence : SENTENCE_SPLIT.split(content)) {
            if (sentence.isBlank()) {
                continue;
            }
            Matcher roleMatcher = ROLE_CLAIM.matcher(sentence);
            while (roleMatcher.find()) {
                add(claims, seen, new AgentContext.ClaimFact(
                        seatNo, round, roleOf(roleMatcher.group(1)), null, null));
            }
            Matcher inspectMatcher = INSPECT_CLAIM.matcher(sentence);
            while (inspectMatcher.find()) {
                int target = Integer.parseInt(inspectMatcher.group(1));
                // 结论只在该匹配之后的片段里找："查验了 5 号"后面的"是狼/好人"才是结论，
                // 用整句会让"不太像狼"这类修饰词把结论读反。
                add(claims, seen, new AgentContext.ClaimFact(
                        seatNo, round, StrategyCodes.SEER, target,
                        verdictOf(sentence.substring(inspectMatcher.end()))));
            }
            Matcher verdictMatcher = VERDICT_CLAIM.matcher(sentence);
            while (verdictMatcher.find()) {
                int target = Integer.parseInt(verdictMatcher.group(1));
                add(claims, seen, new AgentContext.ClaimFact(
                        seatNo, round, null, target, verdictOf(verdictMatcher.group(2))));
            }
            Matcher saveMatcher = SAVE_CLAIM.matcher(sentence);
            while (saveMatcher.find()) {
                int target = Integer.parseInt(saveMatcher.group(1));
                add(claims, seen, new AgentContext.ClaimFact(
                        seatNo, round, StrategyCodes.WITCH, target, "SAVE"));
            }
            Matcher poisonMatcher = POISON_CLAIM.matcher(sentence);
            while (poisonMatcher.find()) {
                int target = Integer.parseInt(poisonMatcher.group(1));
                add(claims, seen, new AgentContext.ClaimFact(
                        seatNo, round, StrategyCodes.WITCH, target, "POISON"));
            }
        }
        return claims;
    }

    private static void add(
            List<AgentContext.ClaimFact> claims, Set<String> seen, AgentContext.ClaimFact claim) {
        String key = claim.claimedRole() + "|" + claim.targetSeat() + "|" + claim.result();
        if (seen.add(key)) {
            claims.add(claim);
        }
    }

    private static String roleOf(String text) {
        return switch (text) {
            case "预言家" -> StrategyCodes.SEER;
            case "女巫" -> StrategyCodes.WITCH;
            case "猎人" -> StrategyCodes.HUNTER;
            case "狼人" -> StrategyCodes.WEREWOLF;
            default -> StrategyCodes.VILLAGER;
        };
    }

    /** 结论归一：{@code WOLF} / {@code GOOD} / 空串（句子里的表述不做过度推断）。 */
    private static String verdictOf(String sentence) {
        if (sentence == null) {
            return null;
        }
        if (sentence.contains("查杀") || sentence.contains("狼")) {
            return "WOLF";
        }
        if (sentence.contains("金水") || sentence.contains("好人") || sentence.contains("村民")) {
            return "GOOD";
        }
        String normalized = FactStore.normalizeVerdict(sentence);
        return normalized.isEmpty() ? null : normalized;
    }

    private static Set<Integer> knownSeats(AgentView view) {
        Set<Integer> seats = new LinkedHashSet<>();
        if (view.me() != null) {
            seats.add(view.me().seatNo());
        }
        for (AgentView.SeatView seat : view.other()) {
            seats.add(seat.seatNo());
        }
        return seats;
    }
}
