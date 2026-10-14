package com.wolfmind.application.agent.strategy;

import com.wolfmind.application.agent.model.Candidate;

import java.util.List;

/**
 * 角色策略：一个角色的"候选集生成 + 硬约束 + 表达目标"（strategy-layer.md §5）。
 *
 * <p>分工边界：候选集是代码强制的**合法空间**，LLM 只能在其中取舍（§13 禁止事项第 4 条）。
 * 因此每个实现的两件事必须分开写清楚：
 * <ul>
 *   <li>{@link #hardConstraints} 是给 prompt 的 SYSTEM 分区看的清单，与工具网关的校验同源；</li>
 *   <li>{@link #generate} 是真正生效的边界——清单说得再好，候选集里出现了非法目标就是失败。</li>
 * </ul>
 */
public interface RoleStrategy {

    /** 本策略负责的角色名（字符串，见 {@link StrategyCodes}）。 */
    String role();

    /** 该角色该阶段的硬约束清单（进 SYSTEM 分区，供模型理解"为什么只有这些候选"）。 */
    List<String> hardConstraints(com.wolfmind.application.agent.model.AgentView view, String phase);

    /**
     * 生成候选集。
     *
     * <p>返回空列表是**合法结果**：表示本回合没有该角色的合法动作，由
     * {@code FallbackPlanner} 产出 {@code SKIP}（§9 第 2 条）。不要为了"看起来有事做"
     * 而补一个非法候选。
     */
    List<Candidate> generate(RoleContext context);
}
