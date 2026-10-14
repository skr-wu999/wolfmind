package com.wolfmind.application.agent.strategy;

import com.wolfmind.application.agent.model.AgentConfig;
import com.wolfmind.application.agent.model.AgentView;

/**
 * 候选生成器的输入：一个回合里角色策略需要的全部只读材料。
 *
 * <p>打包成一个记录而不是让每个角色策略各自去取，是为了让"角色策略能看到什么"这件事
 * 在类型上就写死：它拿不到仓储、拿不到事件流、拿不到工具网关，只有角色可见视图与
 * 由视图派生的事实、打分。候选集因此不可能包含视图之外的信息。
 */
public record RoleContext(
        AgentView view,
        String phase,
        AgentConfig config,
        FactStore facts,
        ThreatModel threatModel,
        SuspicionModel suspicionModel) {

    /** 候选集大小上限（§12 的 {@code strategy.candidateLimit}，默认 3）。 */
    public int candidateLimit() {
        return config == null ? 3 : config.candidateLimit();
    }

    /** 本座位角色（以调用方传入的阶段/角色为准，视图只作为兜底）。 */
    public String myRole() {
        return view != null && view.me() != null ? view.me().role() : null;
    }

    /** 本座位阵营。 */
    public String myCamp() {
        return view != null && view.me() != null ? view.me().camp() : null;
    }
}
