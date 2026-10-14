package com.wolfmind.application.agent.runtime;

import com.wolfmind.application.port.MetricsPort;
import com.wolfmind.application.port.TimePort;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 模型成本守卫（Phase 5 的"模型成本可控"）。
 *
 * <p><b>它守的是"钱"，不是"这一回合的时间"。</b>{@link BudgetGuard} 回答"这次调用赶得上提交窗口吗"，
 * 本类回答"这一局已经烧了多少 token，还该不该继续烧"。两者是不同的问题：
 * 一个慢模型可以在时限内花掉十万 token，而一个很快的模型也可能已经用光了预算。
 *
 * <p><b>超预算不是"对局失败"，而是"降级到规则兜底"。</b>这与 agent-runtime.md §10 的失败策略一致：
 * 规则基线是合法的行动来源，因此整局仍会正常打完，只是从某一回合起不再调用模型。
 * 这是唯一一个"成本可以硬性封顶"的地方——如果超预算就抛异常，对局会卡住，那比多花钱更糟。
 *
 * <p><b>按局计费而不是全局计费。</b>全局上限会让"某一局跑飞"变成"后续所有对局都不能用模型"，
 * 而排障时最需要的是"哪一局跑飞了"。全局总额通过指标（{@code wolfmind.llm.*.tokens}）观察。
 */
public final class LlmCostGuard {

    /** 不限额度。 */
    public static final long UNLIMITED = 0L;

    private final long maxTokensPerGame;
    private final TimePort time;
    private final Map<String, GameCost> byGame = new ConcurrentHashMap<>();

    /**
     * @param maxTokensPerGame 单局 token 上限（prompt + completion）；{@link #UNLIMITED} 表示不限
     */
    public LlmCostGuard(long maxTokensPerGame, TimePort time) {
        this.maxTokensPerGame = Math.max(0L, maxTokensPerGame);
        this.time = time;
    }

    /** 不限预算的实例；未配置上限时用它。 */
    public static LlmCostGuard unlimited(TimePort time) {
        return new LlmCostGuard(UNLIMITED, time);
    }

    public boolean limited() {
        return maxTokensPerGame > UNLIMITED;
    }

    /**
     * 这一局现在还能不能再调一次模型。
     *
     * <p>判断发生在调用**之前**：已经花出去的 token 追不回来，能省的只有下一次。
     */
    public boolean allow(String gameId) {
        if (!limited()) {
            return true;
        }
        GameCost cost = byGame.get(gameId);
        return cost == null || cost.tokens() < maxTokensPerGame;
    }

    /** 记一次调用实际花掉的 token。 */
    public void record(String gameId, int promptTokens, int completionTokens) {
        if (!limited() || gameId == null) {
            return;
        }
        GameCost cost = byGame.computeIfAbsent(gameId, key -> new GameCost());
        cost.add(Math.max(0, promptTokens) + Math.max(0, completionTokens));
    }

    /** 对局结束时释放它的账本；不释放会让一个长跑实例的账本随对局数无界增长。 */
    public void forget(String gameId) {
        byGame.remove(gameId);
    }

    public long tokensSpent(String gameId) {
        GameCost cost = byGame.get(gameId);
        return cost == null ? 0L : cost.tokens();
    }

    /** 当前被预算挡住的对局数；作为仪表暴露，用于回答"降级是不是因为预算"。 */
    public long blockedGames() {
        if (!limited()) {
            return 0L;
        }
        return byGame.values().stream().filter(cost -> cost.tokens() >= maxTokensPerGame).count();
    }

    /** 运维视图：上限与当前账本条数。 */
    public Map<String, Object> report() {
        Map<String, Object> report = new LinkedHashMap<>();
        report.put("maxTokensPerGame", limited() ? maxTokensPerGame : null);
        report.put("trackedGames", byGame.size());
        report.put("blockedGames", blockedGames());
        report.put("note", limited()
                ? "超过上限的对局不再调用模型，改为规则兜底；对局仍会正常结束。"
                : "未设置单局 token 上限（wolfmind.llm.max-tokens-per-game=0 表示不限）。");
        return report;
    }

    /** 一次累计；只在 {@link #record} 里被更新。 */
    private static final class GameCost {
        private final AtomicLong tokens = new AtomicLong();

        private void add(long delta) {
            tokens.addAndGet(delta);
        }

        private long tokens() {
            return tokens.get();
        }
    }
}
