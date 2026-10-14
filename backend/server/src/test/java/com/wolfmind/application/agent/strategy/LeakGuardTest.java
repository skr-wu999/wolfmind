package com.wolfmind.application.agent.strategy;

import com.wolfmind.application.agent.model.AgentView;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** {@link LeakGuard}：正反例（strategy-layer.md §8.2、§11 第六行）。 */
class LeakGuardTest {

    private final LeakGuard guard = new LeakGuard();
    private final FactExtractor extractor = new FactExtractor();

    private StrategyPort.LeakCheckResult check(String text, AgentView view) {
        return guard.check(text, view, extractor.extractStore(view));
    }

    private static AgentView villagerView() {
        return AgentViews.builder()
                .phase(StrategyCodes.DAY_DISCUSS)
                .round(2)
                .me(1, StrategyCodes.VILLAGER, "VILLAGE", true)
                .nineSeats(1)
                .build();
    }

    private static AgentView wolfView() {
        return AgentViews.builder()
                .phase(StrategyCodes.DAY_DISCUSS)
                .round(2)
                .me(3, StrategyCodes.WEREWOLF, "WOLF", true)
                .nineSeats(3)
                .privateFact(AgentViews.teammate(6, 1))
                .privateFact(AgentViews.nightKill(5, 2))
                .build();
    }

    private static AgentView seerView() {
        return AgentViews.builder()
                .phase(StrategyCodes.DAY_DISCUSS)
                .round(2)
                .me(4, StrategyCodes.SEER, "VILLAGE", true)
                .nineSeats(4)
                .privateFact(AgentViews.inspect(5, "WOLF", 1))
                .build();
    }

    @Test
    @DisplayName("反例：村民说出\"女巫昨晚救了 5 号\"——视图之外的座位私有事实")
    void villagerCannotKnowWitchSave() {
        StrategyPort.LeakCheckResult result = check("女巫昨晚救了 5 号，今天我们应该跟她的判断", villagerView());

        assertTrue(result.leaked());
        assertEquals(StrategyCodes.LEAK_PRIVATE_FACT, result.violationKind());
    }

    @Test
    @DisplayName("反例：任何人说出死因（\"3 号是被毒死的\"）——JUDGE 层事实")
    void anyoneCannotStateDeathCause() {
        StrategyPort.LeakCheckResult result = check("3 号是被毒死的，我确定", villagerView());

        assertTrue(result.leaked());
        assertEquals(StrategyCodes.LEAK_JUDGE_FACT, result.violationKind());
    }

    @Test
    @DisplayName("反例：村民替预言家报出查验结论——没有公开宣称时它不可能知道")
    void villagerCannotKnowInspectionResult() {
        StrategyPort.LeakCheckResult result = check("预言家查验了 6 号，6 号是狼", villagerView());

        assertTrue(result.leaked());
        assertEquals(StrategyCodes.LEAK_PRIVATE_FACT, result.violationKind());
    }

    @Test
    @DisplayName("正例：狼人提刀口与队友都允许，它本来就知道")
    void wolfMaySpeakAboutItsOwnKnowledge() {
        AgentView view = wolfView();

        assertFalse(check("我们昨晚刀了 5 号，今天别暴露", view).leaked());
        assertFalse(check("6 号是我的队友，我不会投他", view).leaked());
    }

    @Test
    @DisplayName("正例：自称身份与自述行为都允许（宣称不是窃取事实）")
    void selfClaimsAreAllowed() {
        assertFalse(check("我是预言家，我查验了 5 号是狼", seerView()).leaked());
        assertFalse(check("我是预言家，请大家跟我的票", villagerView()).leaked());
        assertFalse(check("我昨晚救了 5 号", villagerView()).leaked());
    }

    @Test
    @DisplayName("正例：公开宣称过的信息可以被复述")
    void publicClaimsBecomeVisible() {
        AgentView view = AgentViews.builder()
                .phase(StrategyCodes.DAY_DISCUSS)
                .round(2)
                .me(1, StrategyCodes.VILLAGER, "VILLAGE", true)
                .nineSeats(1)
                .speech(2, 1, StrategyCodes.DAY_DISCUSS, 0, "我是预言家，查验了6号是狼")
                .speech(5, 1, StrategyCodes.DAY_DISCUSS, 1, "我是女巫，我救了3号")
                .build();

        assertFalse(check("2 号说预言家查验了 6 号是狼，我们先记下", view).leaked());
        assertFalse(check("5 号自称女巫，说她救了 3 号", view).leaked());
    }

    @Test
    @DisplayName("正例：推测、疑问与普通表态都不算泄漏（保守优先）")
    void speculationAndOrdinarySpeechAreAllowed() {
        AgentView view = villagerView();

        assertFalse(check("如果女巫救了 5 号，那 5 号应该是好人", view).leaked());
        assertFalse(check("昨晚女巫救了 5 号吗？", view).leaked());
        assertFalse(check("我觉得 3 号发言很飘，先记一下", view).leaked());
    }

    @Test
    @DisplayName("正例：女巫自己提用药记录允许（私有事实可见）")
    void witchMaySpeakAboutHerOwnMedicine() {
        AgentView view = AgentViews.builder()
                .phase(StrategyCodes.DAY_DISCUSS)
                .round(2)
                .me(7, StrategyCodes.WITCH, "VILLAGE", true)
                .nineSeats(7)
                .privateFact(AgentViews.saved(5, 1))
                .build();

        assertFalse(check("我昨晚把解药用在了 5 号身上", view).leaked());
    }

    @Test
    @DisplayName("空文本与无关文本直接放行")
    void blankTextIsClean() {
        assertFalse(check(null, villagerView()).leaked());
        assertFalse(check("   ", villagerView()).leaked());
        assertFalse(check("今天天气不错", villagerView()).leaked());
    }

    @Test
    @DisplayName("可见指纹集合包含自己的私有事实与公开宣称")
    void visibleFingerprintsCoverPrivateAndPublic() {
        AgentView view = AgentViews.builder()
                .phase(StrategyCodes.DAY_DISCUSS)
                .round(2)
                .me(4, StrategyCodes.SEER, "VILLAGE", true)
                .nineSeats(4)
                .privateFact(AgentViews.inspect(5, "WOLF", 1))
                .speech(2, 1, StrategyCodes.DAY_DISCUSS, 0, "我是女巫，我救了3号")
                .build();

        var visible = guard.visibleFingerprints(extractor.extractStore(view));

        assertTrue(visible.contains("INSPECT:5"));
        assertTrue(visible.contains("SAVE:3"));
        assertFalse(visible.contains("POISON:3"));
    }
}
