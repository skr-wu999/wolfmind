package com.wolfmind.application.agent.strategy;

import com.wolfmind.application.agent.model.AgentView;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 复读检查：发言与视图内已有发言高度相似即命中。
 *
 * <p>相似度取去掉数字、标点、空白与 ASCII 字母后的相邻双字集合的 Dice 系数。座位号、轮次数字被去掉，
 * 因此"我是 5 号……"与"我是 6 号……"按同一段话计算。纯函数，同一输入同一结果。
 *
 * <p>与某条发言比较前，先去掉"除它之外还有至少两条发言都用过"的双字。同一天大家复述同一条公开事实
 * （"1 号夜里出局，遗言自称女巫……"）是正常的，这部分不该算成谁抄谁；而比较对象本身不计入出现次数，
 * 否则一旦有人整段照抄成功，第三个照抄者会因为原文已"出现在两条里"而漏检。
 *
 * <p>阈值 0.7 来自两局实测：整段照抄 0.89–1.00；对局 {@code g_r_mv5ggj6c_1} 第一天六条各自表述的发言
 * 最高 0.51，同一开场白加自己判断的发言 0.55–0.63。旧算法（不去公共片段、阈值 0.6）在这局把
 * 两条正常发言连拦两次，退成了安全模板。
 */
public final class EchoGuard {

    public static final double DEFAULT_THRESHOLD = 0.7;

    /** 规范化后少于这么多字的发言不比较：短句天然容易撞词，例如"过""同意 3 号"。 */
    static final int MIN_CHARS = 20;

    /** 一个双字在比较对象以外的这么多条发言里出现过，就算公共片段。 */
    static final int COMMON_MIN_SPEECHES = 2;

    private final double threshold;

    public EchoGuard() {
        this(DEFAULT_THRESHOLD);
    }

    public EchoGuard(double threshold) {
        if (threshold <= 0.0 || threshold > 1.0) {
            throw new IllegalArgumentException("threshold 必须在 (0, 1]");
        }
        this.threshold = threshold;
    }

    public StrategyPort.EchoCheckResult check(String text, AgentView view) {
        if (text == null || view == null) {
            return StrategyPort.EchoCheckResult.clean();
        }
        String normalized = normalize(text);
        if (normalized.length() < MIN_CHARS) {
            return StrategyPort.EchoCheckResult.clean();
        }
        Set<String> mine = bigrams(normalized);
        List<AgentView.SpeechView> comparable = new ArrayList<>();
        List<Set<String>> grams = new ArrayList<>();
        Map<String, Integer> frequency = new HashMap<>();
        for (AgentView.SpeechView speech : view.speeches()) {
            String other = normalize(speech.content());
            if (other.length() < MIN_CHARS) {
                continue;
            }
            Set<String> otherGrams = bigrams(other);
            comparable.add(speech);
            grams.add(otherGrams);
            otherGrams.forEach(gram -> frequency.merge(gram, 1, Integer::sum));
        }
        AgentView.SpeechView closest = null;
        double best = 0.0;
        for (int index = 0; index < comparable.size(); index++) {
            Set<String> other = grams.get(index);
            double similarity = dice(withoutCommon(mine, other, frequency), withoutCommon(other, other, frequency));
            if (similarity > best) {
                best = similarity;
                closest = comparable.get(index);
            }
        }
        if (closest == null || best < threshold) {
            return StrategyPort.EchoCheckResult.clean();
        }
        boolean self = view.me() != null && view.me().seatNo() == closest.seatNo();
        return StrategyPort.EchoCheckResult.echo(closest.seatNo(), closest.round(), best, self);
    }

    static String normalize(String text) {
        if (text == null) {
            return "";
        }
        StringBuilder builder = new StringBuilder(text.length());
        text.codePoints()
                .filter(codePoint -> codePoint > 0x7F && Character.isLetter(codePoint))
                .forEach(builder::appendCodePoint);
        return builder.toString();
    }

    static Set<String> bigrams(String normalized) {
        Set<String> grams = new HashSet<>();
        int[] codePoints = normalized.codePoints().toArray();
        for (int index = 0; index + 1 < codePoints.length; index++) {
            grams.add(new String(codePoints, index, 2));
        }
        return grams;
    }

    /** 去掉在 {@code target} 以外的发言里出现过至少 {@link #COMMON_MIN_SPEECHES} 次的双字。 */
    private static Set<String> withoutCommon(Set<String> grams, Set<String> target, Map<String, Integer> frequency) {
        Set<String> kept = new HashSet<>();
        for (String gram : grams) {
            int elsewhere = frequency.getOrDefault(gram, 0) - (target.contains(gram) ? 1 : 0);
            if (elsewhere < COMMON_MIN_SPEECHES) {
                kept.add(gram);
            }
        }
        return kept;
    }

    static double dice(Set<String> left, Set<String> right) {
        if (left.isEmpty() && right.isEmpty()) {
            return 0.0;
        }
        int shared = 0;
        for (String gram : left) {
            if (right.contains(gram)) {
                shared++;
            }
        }
        return 2.0 * shared / (left.size() + right.size());
    }
}
