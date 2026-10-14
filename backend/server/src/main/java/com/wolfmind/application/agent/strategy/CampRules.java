package com.wolfmind.application.agent.strategy;

import com.wolfmind.application.agent.model.AgentView;

import java.util.Locale;

/**
 * 角色与阵营的字符串判定（strategy-layer.md §5 的硬约束需要一个统一的判定点）。
 *
 * <p>为什么不用领域枚举：DEP-7 禁止 Agent 侧引用领域层的 game model 包（C-5）。
 * 字符串比较的代价是拼写风险，因此这里只认一种规范化方式（去空白 + 大写）。
 *
 * <p>未知取值按"不是本阵营"处理：这在硬约束上是更安全的一侧——宁可少给一个候选，
 * 也不放过一个可能违法的目标（INV-6 的越界代价远高于候选集少一个）。
 */
public final class CampRules {

    private CampRules() {}

    public static final String CAMP_WOLF = "WOLF";
    public static final String CAMP_VILLAGE = "VILLAGE";

    /** 规范化阵营/角色名；{@code null} 归一为空串，避免到处写判空。 */
    public static String normalize(String value) {
        return value == null ? "" : value.strip().toUpperCase(Locale.ROOT);
    }

    public static boolean isWolfCamp(String camp) {
        return CAMP_WOLF.equals(normalize(camp));
    }

    public static boolean isWolfRole(String role) {
        return StrategyCodes.WEREWOLF.equals(normalize(role));
    }

    /** 角色到阵营：只有狼人是狼队，其余都是村民阵营（domain-model.md §5.1 的派生规则）。 */
    public static String campOfRole(String role) {
        return isWolfRole(role) ? CAMP_WOLF : CAMP_VILLAGE;
    }

    /**
     * 该座位是否确定属于狼人阵营。
     *
     * <p>三处独立证据任一命中即算：视图里的阵营字段、视图里的角色字段（狼人可见队友角色）、
     * 本座位可见的私有队友事实。三条都读是因为视图的脱敏由领域负责，而 INV-6 是硬约束，
     * 不能依赖单一字段恰好被填充。任何一处缺失都不会让狼人队友变成合法刀口。
     */
    public static boolean isWolfSeat(AgentView view, FactStore facts, int seatNo) {
        if (view == null) {
            return false;
        }
        if (view.me() != null && view.me().seatNo() == seatNo) {
            return isWolfRole(view.me().role());
        }
        if (facts != null && facts.knownTeammates().contains(seatNo)) {
            return true;
        }
        return view.seat(seatNo)
                .map(seat -> isWolfCamp(seat.camp()) || isWolfRole(seat.role()))
                .orElse(false);
    }
}
