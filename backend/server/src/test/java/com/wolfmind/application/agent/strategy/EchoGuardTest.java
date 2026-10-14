package com.wolfmind.application.agent.strategy;

import com.wolfmind.application.agent.model.AgentView;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 复读检查：样本取自对局 g_r_mv56e2su_1 与 g_r_mv5ggj6c_1 的真实发言。 */
class EchoGuardTest {

    /** g_r_mv5ggj6c_1 第一天：1 号夜里出局，2、3、5、6、8、9 号的开场都复述了同一条公开事实。 */
    private static final String DAY1_SEAT_2 = "1号夜里出局，遗言自称女巫。夜里出局的原因不公开，我不去猜。"
            + "作为2号普通村民，我先说我的判断：1号如果是真女巫，那狼刀准，好人少一个关键神职；"
            + "如果1号是狼悍跳女巫，那遗言就是做局。目前没有更多信息，我倾向于先听后面几位的发言，"
            + "看谁急着带节奏、谁在替1号定性。我暂时不站边，也不乱指人，等发言齐了再投票。";
    private static final String DAY1_SEAT_3 = "我是3号，普通村民。1号夜里出局，遗言自称女巫，但夜里出局的原因不公开，我不去猜。"
            + "2号说先听后面发言、不站边，这个态度我认同，第一天信息太少，急着给1号定性的人反而可疑。"
            + "我的判断是：1号若是真女巫，好人少一个关键神职，后面要更谨慎；若是狼悍跳，遗言就是做局。"
            + "目前我不指人，先听完2到9号的发言，看谁的逻辑有硬伤、谁在替1号强行定性，再决定投票。";
    private static final String DAY1_SEAT_5 = "5号发言。1号夜里出局，遗言自称女巫，但夜里出局的原因不公开，这点我不去猜。"
            + "前面几位大多在说先听发言、不急着定性，我理解这种谨慎，但我觉得第一天也不能完全空转，总得有人把话说实。"
            + "4号只留一句不表态，信息太少，我先记着。我的态度是：1号的身份现在无法确认，与其纠结遗言，"
            + "不如看后面6到9号谁在认真给逻辑、谁在敷衍。我先不指人，等发言齐了再投票。";
    private static final String DAY1_SEAT_6 = "6号发言。1号夜里出局，遗言自称女巫，但夜里出局的原因不公开，我不去猜。"
            + "前面2、3、5号都在说先听发言、不急着定性，这个思路我认同，第一天信息确实少。"
            + "不过5号点了4号一句，我觉得4号只留一句不表态，信息量确实偏低，先记着。"
            + "我的态度是：不急着给1号定性，也不乱指人，重点看后面7、8、9号谁在认真给逻辑、谁在敷衍带节奏，"
            + "等发言齐了再投票。";
    private static final String DAY1_SEAT_8 = "8号发言。先说我的立场：1号夜里出局，遗言自称女巫，但夜里出局的原因不公开，"
            + "我不会去猜，也不会因为一句遗言就给他定性。前面几位里，2号、3号、5号、6号都主张先听发言再判断，"
            + "这个方向我基本同意，第一天信息确实少，急着定性容易出错。但我不完全认同把注意力都放在“谁敷衍”上——"
            + "4号和7号发言短，可能只是没想法，未必就是狼，单凭这个记人有点草率。我更在意的是9号接下来怎么说，"
            + "以及有没有人在发言里悄悄替1号定性、或者急着推一个方向。我暂时不指人，等9号说完再综合投票。";
    private static final String DAY1_SEAT_9 = "9号发言。1号夜里出局，遗言自称女巫，夜里出局的原因不公开，我不去猜，"
            + "也不凭一句遗言给他定性。前面2、3、5、6号都主张先听发言再判断，方向我认同；"
            + "8号提醒不要因为4号、7号发言短就急着记人，这点我也同意，第一天信息少，单凭发言长短定狼太草率。"
            + "我的态度是：不急着给1号定性，也不乱指人，重点看后面投票时谁在硬推方向、谁在跟风。"
            + "我先不指人，等投票阶段再综合判断。";

    private static final String SEAT_4 = "4号发言。首轮无死亡公布，信息确实少。我是普通村民，没有额外信息。"
            + "前面1号2号3号都在说先听一圈、不站边，态度都偏保守，暂时看不出明显问题。"
            + "我建议后面的人多给点实质判断，比如怀疑谁、理由是什么，别只喊口号。我自己先不站边，等听完一圈再定。";
    private static final String SEAT_5_COPY = "5号发言。首轮无死亡公布，信息确实少。前面1号到4号都在说先听一圈、不站边，"
            + "态度都偏保守，暂时看不出明显问题。我建议后面的人多给点实质判断，比如怀疑谁、理由是什么，别只喊口号。"
            + "我自己先不站边，等听完一圈再定。";
    private static final String SEAT_1 = "1号发言。首轮无信息，我先给个基础判断：没有死亡公布，说明昨晚大概率是平安夜或女巫用药。"
            + "我建议今天先听后面几位的发言，重点看谁在无信息情况下急着带节奏、乱归票。我自己暂时不站边，等听完一圈再定。";
    private static final String SEAT_2 = "2号发言。首轮无死亡公布，信息量确实很少。我认同1号说的先听一圈再定，不急着归票。"
            + "我自己是普通村民，没有额外信息。建议后面发言的人多给点实质判断，比如对谁有怀疑、理由是什么，别只喊口号。"
            + "我先过，等听完再表态。";

    private final EchoGuard guard = new EchoGuard();

    private static AgentView view(int mySeat, AgentView.SpeechView... speeches) {
        AgentViews.Builder builder = AgentViews.builder()
                .phase(StrategyCodes.DAY_DISCUSS)
                .me(mySeat, StrategyCodes.VILLAGER, "VILLAGE", true)
                .nineSeats(mySeat);
        for (AgentView.SpeechView speech : speeches) {
            builder.speech(speech.seatNo(), speech.round(), speech.phase(), speech.turnIndex(), speech.content());
        }
        return builder.build();
    }

    @Test
    @DisplayName("只换了座位号的整段照抄命中，并指出被抄的座位与轮次")
    void nearCopyIsEcho() {
        AgentView view = view(5, AgentViews.speech(4, 1, StrategyCodes.DAY_DISCUSS, 3, SEAT_4));

        StrategyPort.EchoCheckResult result = guard.check(SEAT_5_COPY, view);

        assertTrue(result.echo());
        assertEquals(4, result.seatNo());
        assertEquals(1, result.round());
        assertTrue(result.similarity() >= 0.85, "similarity=" + result.similarity());
        assertTrue(result.detail().startsWith("4 号第 1 轮的发言"), result.detail());
    }

    @Test
    @DisplayName("话题相近但各自表述的发言不命中")
    void topicalOverlapIsNotEcho() {
        AgentView view = view(2, AgentViews.speech(1, 1, StrategyCodes.DAY_DISCUSS, 0, SEAT_1));

        StrategyPort.EchoCheckResult result = guard.check(SEAT_2, view);

        assertFalse(result.echo(), "similarity=" + result.similarity());
    }

    @Test
    @DisplayName("短句不比较：‘同意 3 号’这类话天然会撞词")
    void shortSpeechesAreIgnored() {
        AgentView view = view(5, AgentViews.speech(4, 1, StrategyCodes.DAY_DISCUSS, 3, "我同意3号的看法，先听听。"));

        assertFalse(guard.check("我同意3号的看法，先听听。", view).echo());
    }

    @Test
    @DisplayName("与自己此前的发言雷同也命中，提示语改为‘你自己’")
    void selfRepeatIsReportedAsSelf() {
        AgentView view = view(4, AgentViews.speech(4, 1, StrategyCodes.DAY_DISCUSS, 3, SEAT_4));

        StrategyPort.EchoCheckResult result = guard.check(SEAT_4, view);

        assertTrue(result.echo());
        assertTrue(result.self());
        assertTrue(result.detail().startsWith("你自己第 1 轮的发言"), result.detail());
    }

    @Test
    @DisplayName("复述同一条公开事实的开场不算复读：g_r_mv5ggj6c_1 第一天六条发言依次检查全部通过")
    void sharedOpeningIsNotEcho() {
        String[] day1 = {DAY1_SEAT_2, DAY1_SEAT_3, DAY1_SEAT_5, DAY1_SEAT_6, DAY1_SEAT_8, DAY1_SEAT_9};
        int[] seats = {2, 3, 5, 6, 8, 9};
        for (int index = 0; index < day1.length; index++) {
            AgentView.SpeechView[] earlier = new AgentView.SpeechView[index];
            for (int prior = 0; prior < index; prior++) {
                earlier[prior] = AgentViews.speech(seats[prior], 1, StrategyCodes.DAY_DISCUSS, prior, day1[prior]);
            }
            StrategyPort.EchoCheckResult result = guard.check(day1[index], view(seats[index], earlier));
            assertFalse(result.echo(), seats[index] + " 号 similarity=" + result.similarity());
        }
    }

    @Test
    @DisplayName("套用前人开场白、后半段给出自己信息的发言不算复读")
    void sharedOpeningWithOwnClaimIsNotEcho() {
        String seer = "我是4号，预言家。1号夜里出局，遗言自称女巫，但夜里出局的原因不公开，我不去猜。"
                + "我昨晚查验了1号，是好人，和他自称女巫对得上。2号3号说先听后面发言、不站边，这个态度我认同。"
                + "目前我不指人，先听完后面的发言，看谁在替1号强行定性，再决定投票。";
        AgentView view = view(4,
                AgentViews.speech(2, 1, StrategyCodes.DAY_DISCUSS, 0, DAY1_SEAT_2),
                AgentViews.speech(3, 1, StrategyCodes.DAY_DISCUSS, 1, DAY1_SEAT_3));

        StrategyPort.EchoCheckResult result = guard.check(seer, view);

        assertFalse(result.echo(), "similarity=" + result.similarity());
    }

    @Test
    @DisplayName("前面有多条发言时，只换座位号的照抄仍命中")
    void copyAmongManySpeechesIsEcho() {
        AgentView view = view(9,
                AgentViews.speech(2, 1, StrategyCodes.DAY_DISCUSS, 0, DAY1_SEAT_2),
                AgentViews.speech(3, 1, StrategyCodes.DAY_DISCUSS, 1, DAY1_SEAT_3),
                AgentViews.speech(5, 1, StrategyCodes.DAY_DISCUSS, 2, DAY1_SEAT_5),
                AgentViews.speech(6, 1, StrategyCodes.DAY_DISCUSS, 3, DAY1_SEAT_6),
                AgentViews.speech(8, 1, StrategyCodes.DAY_DISCUSS, 4, DAY1_SEAT_8));

        StrategyPort.EchoCheckResult result = guard.check(DAY1_SEAT_6.replace("6号", "9号"), view);

        assertTrue(result.echo());
        assertEquals(6, result.seatNo());
        assertTrue(result.similarity() >= 0.85, "similarity=" + result.similarity());
    }

    @Test
    @DisplayName("已经有人照抄过同一条发言，第三个照抄者仍命中")
    void secondCopyOfSameSpeechIsEcho() {
        AgentView view = view(5,
                AgentViews.speech(2, 1, StrategyCodes.DAY_DISCUSS, 0, DAY1_SEAT_2),
                AgentViews.speech(3, 1, StrategyCodes.DAY_DISCUSS, 1, DAY1_SEAT_3),
                AgentViews.speech(4, 1, StrategyCodes.DAY_DISCUSS, 2, DAY1_SEAT_3.replace("3号", "4号")));

        StrategyPort.EchoCheckResult result = guard.check(DAY1_SEAT_3.replace("3号", "5号"), view);

        assertTrue(result.echo(), "similarity=" + result.similarity());
        assertTrue(result.similarity() >= 0.85, "similarity=" + result.similarity());
    }

    @Test
    @DisplayName("没有可比的发言时不命中")
    void emptyTimelineIsClean() {
        assertFalse(guard.check(SEAT_5_COPY, view(5)).echo());
        assertFalse(guard.check(null, view(5)).echo());
    }
}
