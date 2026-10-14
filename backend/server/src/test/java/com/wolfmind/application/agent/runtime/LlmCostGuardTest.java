package com.wolfmind.application.agent.runtime;

import com.wolfmind.application.port.TimePort;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 单局 token 预算的验收（Phase 5 的"模型成本可控"）。
 *
 * <p>它守的是一条产品判断：<b>超预算必须降级，不能抛异常</b>。
 * 抛异常会让对局卡在一个没有后续动作的回合上，而"这一局不再调用模型"只是让 AI 退回规则基线——
 * 多花一次钱比卡住整局轻。
 */
class LlmCostGuardTest {

    private final AtomicLong now = new AtomicLong(1_730_000_000_000L);
    private final TimePort time = now::get;

    @Test
    @DisplayName("限额为 0 表示不限：不记账、不阻塞")
    void unlimitedGuardNeverBlocks() {
        LlmCostGuard guard = LlmCostGuard.unlimited(time);
        assertFalse(guard.limited());
        assertTrue(guard.allow("g_1"));

        guard.record("g_1", 1_000_000, 1_000_000);
        assertTrue(guard.allow("g_1"), "未配置上限时任何用量都不该被拒");
        assertEquals(0L, guard.tokensSpent("g_1"), "不限额度时不记账：记账会随对局数无界增长");
    }

    @Test
    @DisplayName("达到上限后拒绝后续调用，且拒绝是按局隔离的")
    void budgetIsPerGame() {
        LlmCostGuard guard = new LlmCostGuard(1_000, time);
        assertTrue(guard.allow("g_1"));

        guard.record("g_1", 600, 500);
        assertFalse(guard.allow("g_1"), "已花 1100 > 上限 1000，下一次调用必须被拒");
        assertEquals(1_100L, guard.tokensSpent("g_1"));
        assertTrue(guard.allow("g_2"), "另一局有自己的账本：一局跑飞不该让所有对局都不能用模型");
        assertEquals(1L, guard.blockedGames());
    }

    @Test
    @DisplayName("账本在对局结束后释放")
    void forgetReleasesTheLedger() {
        LlmCostGuard guard = new LlmCostGuard(100, time);
        guard.record("g_1", 200, 0);
        assertFalse(guard.allow("g_1"));

        guard.forget("g_1");
        assertTrue(guard.allow("g_1"));
        assertEquals(0L, guard.tokensSpent("g_1"));
    }

    @Test
    @DisplayName("运维视图写清楚上限与是否限额，避免把'没配'读成'免费'")
    void reportExplainsWhetherACapExists() {
        assertTrue(LlmCostGuard.unlimited(time).report().containsKey("maxTokensPerGame"));
        assertEquals(null, LlmCostGuard.unlimited(time).report().get("maxTokensPerGame"),
                "未配置时必须给 null 而不是 0：0 在这个上下文里表示'不限'，会被读成'没有上限'以外的意思");
        assertEquals(50_000L, new LlmCostGuard(50_000, time).report().get("maxTokensPerGame"));
        assertTrue(String.valueOf(new LlmCostGuard(50_000, time).report().get("note")).contains("兜底"));
        assertTrue(String.valueOf(LlmCostGuard.unlimited(time).report().get("note")).contains("不限"));
    }
}
