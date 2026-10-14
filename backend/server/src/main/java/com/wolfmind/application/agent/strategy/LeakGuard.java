package com.wolfmind.application.agent.strategy;

import com.wolfmind.application.agent.model.AgentContext;
import com.wolfmind.application.agent.model.AgentView;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 信息边界守卫（strategy-layer.md §8.2）。
 *
 * <p>它拦的是"这个 Agent 说出了它自己都不可能知道的事实"，顺带拦住模型幻觉出的上帝视角。
 * 判定方式是**结构化事实指纹匹配**（座位号 × 事实类型），不做语义审查：
 * <ul>
 *   <li>座位私有事实（查验、解药、毒药）出现在视图外 → 违规；</li>
 *   <li>{@code JUDGE} 层事实（"3 号是被毒死的"这类死因断言）→ 违规；</li>
 *   <li>狼人提狼队内部事实（刀口、队友）→ 允许，它本来就知道；</li>
 *   <li>自称身份（"我是预言家"）与自述行为（"我救了 5 号"）→ 允许，这是宣称不是窃取事实；</li>
 *   <li>与候选集无关的额外承诺 → 允许，只记录到 Trace。</li>
 * </ul>
 *
 * <p>保守性（§8.2 末段"宁可漏判也不误杀"）体现在三处明确的宽容上：
 * 带推测词（猜、可能、如果…）的句子不判违规；第一人称的自述不判违规；"刀"这类
 * 狼人杀通用表述一律不判违规（只有毒药相关的死因才作为 {@code JUDGE} 事实拦截，
 * 因为它不可能来自公开信息）。这些宽容都在注释里写明，便于复核时判断是不是漏判。
 */
public final class LeakGuard {

    /** 座位号与关键词的关联窗口：同一句话里离得足够近才算"指向该座位"。 */
    private static final int WINDOW = 14;

    private static final Pattern SEAT = Pattern.compile("(\\d{1,2})\\s*号");

    /** 解药类表述：只有女巫本人与公开宣称者才可能知道。 */
    private static final Pattern SAVE = Pattern.compile("(救了|救下|施救|解药|救过|救活|救一下)");

    /** 毒药类表述。 */
    private static final Pattern POISON = Pattern.compile("(毒了|毒杀|下毒|毒药|被毒)");

    /** 查验类表述：只有预言家本人与公开给出结论者才可能知道。 */
    private static final Pattern INSPECT = Pattern.compile("(查验|验了|验过|验人|查杀|金水)");

    /** {@code JUDGE} 层死因断言：毒药死因不可能从公开信息里得到（刀口不在此列，见类注释）。 */
    private static final Pattern JUDGE_DEATH_CAUSE =
            Pattern.compile("(被毒死|毒死|毒杀|中毒身亡|死于毒|毒发身亡|被女巫毒|死因)");

    /** 推测词：出现即视为猜测而不是陈述事实。 */
    private static final Pattern HEDGE =
            Pattern.compile("(猜|可能|也许|大概|如果|应该|假设|推测|怀疑|觉得|似乎|听说|难道|是不是)");

    /** 第一人称：自述行为属于宣称，不属于窃取事实。 */
    private static final Pattern FIRST_PERSON = Pattern.compile("我");

    /** 句读边界：判断"这一句是谁在说"时使用。 */
    private static final String BOUNDARIES = "。！？!?\n；;，,";

    /** 检查一段文本；返回违规详情或 {@link StrategyPort.LeakCheckResult#clean()}。 */
    public StrategyPort.LeakCheckResult check(String text, AgentView view, FactStore facts) {
        if (text == null || text.isBlank() || view == null || facts == null) {
            return StrategyPort.LeakCheckResult.clean();
        }
        StrategyPort.LeakCheckResult judge = checkJudgeFacts(text);
        if (judge.leaked()) {
            return judge;
        }
        return checkPrivateFingerprints(text, view, facts);
    }

    /** {@code JUDGE} 层事实：第三人称的死因断言。 */
    private StrategyPort.LeakCheckResult checkJudgeFacts(String text) {
        Matcher matcher = JUDGE_DEATH_CAUSE.matcher(text);
        while (matcher.find()) {
            String clause = clauseBefore(text, matcher.start());
            if (FIRST_PERSON.matcher(clause).find()) {
                // 女巫说"我毒了他"是在自述行为（她确实知道），不是在替上帝发言
                continue;
            }
            return StrategyPort.LeakCheckResult.violation(
                    StrategyCodes.LEAK_JUDGE_FACT,
                    "第三人称死因断言：" + matcher.group(1) + "（JUDGE 层事实）");
        }
        return StrategyPort.LeakCheckResult.clean();
    }

    /** 视图外的座位私有事实：座位号与私有事实关键词在窗口内共现，且该指纹不可见。 */
    private StrategyPort.LeakCheckResult checkPrivateFingerprints(String text, AgentView view, FactStore facts) {
        List<int[]> seats = new ArrayList<>();
        Matcher seatMatcher = SEAT.matcher(text);
        while (seatMatcher.find()) {
            int seatNo = Integer.parseInt(seatMatcher.group(1));
            if (isKnownSeat(view, seatNo)) {
                seats.add(new int[] {seatNo, seatMatcher.start(), seatMatcher.end()});
            }
        }
        if (seats.isEmpty()) {
            return StrategyPort.LeakCheckResult.clean();
        }
        Set<String> visible = visibleFingerprints(facts);
        StrategyPort.LeakCheckResult violation =
                scan(text, seats, SAVE, "SAVE", visible);
        if (violation.leaked()) {
            return violation;
        }
        violation = scan(text, seats, POISON, "POISON", visible);
        if (violation.leaked()) {
            return violation;
        }
        return scan(text, seats, INSPECT, "INSPECT", visible);
    }

    private StrategyPort.LeakCheckResult scan(
            String text, List<int[]> seats, Pattern keyword, String kind, Set<String> visible) {
        Matcher matcher = keyword.matcher(text);
        while (matcher.find()) {
            String clause = clauseBefore(text, matcher.start());
            if (HEDGE.matcher(clause).find() || FIRST_PERSON.matcher(clause).find()) {
                // 推测与自述都不算"说出了不可能知道的事实"
                continue;
            }
            if (isQuestion(text, matcher.end())) {
                continue;
            }
            int[] nearest = nearestSeat(seats, matcher.start(), matcher.end());
            if (nearest == null) {
                continue;
            }
            String fingerprint = kind + ":" + nearest[0];
            if (!visible.contains(fingerprint)) {
                return StrategyPort.LeakCheckResult.violation(
                        StrategyCodes.LEAK_PRIVATE_FACT,
                        "座位 " + nearest[0] + " × " + kind + "（视图外的私有事实）");
            }
        }
        return StrategyPort.LeakCheckResult.clean();
    }

    /**
     * 本座位可见的私有事实指纹集合（§8.2 的"可见集合"）。
     *
     * <p>两种来源：自己的私有事实（预言家的查验、女巫的用药），以及公开宣称
     * （预言家报出的查验结论、女巫公开的用药），后者一旦说出口就变成了公共信息，
     * 别人复述它不构成泄漏。
     */
    Set<String> visibleFingerprints(FactStore facts) {
        Set<String> visible = new LinkedHashSet<>();
        for (AgentContext.PrivateFactView fact : facts.privateFacts()) {
            AgentView.PrivateFact.FactKind kind = FactStore.kindOf(fact);
            if (kind == AgentView.PrivateFact.FactKind.SEER_INSPECT) {
                visible.add("INSPECT:" + fact.targetSeat());
            } else if (kind == AgentView.PrivateFact.FactKind.WITCH_SAVED) {
                visible.add("SAVE:" + fact.targetSeat());
            } else if (kind == AgentView.PrivateFact.FactKind.WITCH_POISONED) {
                visible.add("POISON:" + fact.targetSeat());
            }
        }
        for (AgentContext.ClaimFact claim : facts.claims()) {
            if (claim.targetSeat() == null) {
                continue;
            }
            String result = StrategyCodes.normalize(claim.result());
            if ("SAVE".equals(result)) {
                visible.add("SAVE:" + claim.targetSeat());
            } else if ("POISON".equals(result)) {
                visible.add("POISON:" + claim.targetSeat());
            } else if ("WOLF".equals(result) || "GOOD".equals(result)) {
                visible.add("INSPECT:" + claim.targetSeat());
            }
        }
        return visible;
    }

    private boolean isKnownSeat(AgentView view, int seatNo) {
        if (view.me() != null && view.me().seatNo() == seatNo) {
            return true;
        }
        return view.seat(seatNo).isPresent();
    }

    /** 取关键词所在的小句（从最近的句读边界到关键词），用于判断推测词与第一人称。 */
    private String clauseBefore(String text, int keywordStart) {
        int boundary = -1;
        for (int i = 0; i < keywordStart && i < text.length(); i++) {
            if (BOUNDARIES.indexOf(text.charAt(i)) >= 0) {
                boundary = i;
            }
        }
        return text.substring(boundary + 1, keywordStart);
    }

    /** 关键词之后紧跟问号或"吗"，按疑问句处理（不是在陈述事实）。 */
    private boolean isQuestion(String text, int keywordEnd) {
        // 窗口取 6 个字符：中文疑问句里"5 号吗？"这类结构中间会夹座位号与空格
        int end = Math.min(text.length(), keywordEnd + 6);
        String tail = text.substring(keywordEnd, end);
        return tail.contains("?") || tail.contains("？") || tail.contains("吗");
    }

    private int[] nearestSeat(List<int[]> seats, int start, int end) {
        int[] best = null;
        int bestDistance = Integer.MAX_VALUE;
        for (int[] seat : seats) {
            int distance = Math.min(Math.abs(start - seat[2]), Math.abs(seat[1] - end));
            if (distance <= WINDOW && distance < bestDistance) {
                best = seat;
                bestDistance = distance;
            }
        }
        return best;
    }
}
