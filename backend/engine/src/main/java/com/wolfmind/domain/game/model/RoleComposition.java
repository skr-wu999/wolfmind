package com.wolfmind.domain.game.model;

import java.util.Collection;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 角色配置：角色数量的唯一来源（domain-model.md §5.2）。
 *
 * <p>用 {@code EnumMap} 而不是固定字段，新增角色只需加 {@link Role} 枚举值与配置项，
 * 不改本类（ddd-design.md §9 演进规则）。约束：合计等于座位数、狼数 ≥ 1、村民阵营 ≥ 1。
 */
public record RoleComposition(Map<Role, Integer> counts) {

    /** 第一阶段标准局：3 狼 3 民 1 预 1 女 1 猎。 */
    public static final RoleComposition STANDARD_NINE = new RoleComposition(
            Map.of(Role.WEREWOLF, 3, Role.VILLAGER, 3, Role.SEER, 1, Role.WITCH, 1, Role.HUNTER, 1));

    public RoleComposition {
        if (counts == null || counts.isEmpty()) {
            throw GameException.Factory.compositionInvalid("角色配置不能为空");
        }
        EnumMap<Role, Integer> copy = new EnumMap<>(Role.class);
        for (Map.Entry<Role, Integer> entry : counts.entrySet()) {
            Role role = entry.getKey();
            Integer count = entry.getValue();
            if (role == null || count == null) {
                throw GameException.Factory.compositionInvalid("角色配置不能包含空键或空值");
            }
            if (count < 0) {
                throw GameException.Factory.compositionInvalid(role + " 的数量不能为负");
            }
            if (count > 0) {
                copy.put(role, count);
            }
        }
        if (copy.isEmpty()) {
            throw GameException.Factory.compositionInvalid("角色配置至少需要一个角色");
        }
        counts = Map.copyOf(copy);
    }

    /** 便于手写标准局之外的配置。 */
    public static RoleComposition of(Map<Role, Integer> counts) {
        return new RoleComposition(counts);
    }

    public int playerCount() {
        return counts.values().stream().mapToInt(Integer::intValue).sum();
    }

    public int countOf(Role role) {
        return counts.getOrDefault(role, 0);
    }

    public int wolfCount() {
        return counts.entrySet().stream()
                .filter(e -> e.getKey().isWolfTeam())
                .mapToInt(Map.Entry::getValue)
                .sum();
    }

    public int villageCount() {
        return playerCount() - wolfCount();
    }

    /** 按角色展开成"待分配角色列表"，顺序稳定：按枚举声明顺序重复展开。 */
    public List<Role> expandedRoles() {
        return counts.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .flatMap(e -> java.util.Collections.nCopies(e.getValue(), e.getKey()).stream())
                .toList();
    }

    /** 校验"座位数恰好等于配置总数"，INV-1。 */
    public void validateAgainstSeatCount(int seatCount) {
        int expected = playerCount();
        if (seatCount != expected) {
            throw GameException.Factory.compositionInvalid(
                    "座位数 " + seatCount + " 与角色配置总数 " + expected + " 不一致");
        }
        if (seatCount > SeatNo.MAX) {
            throw GameException.Factory.compositionInvalid("座位数不能超过 " + SeatNo.MAX);
        }
        if (wolfCount() < 1) {
            throw GameException.Factory.compositionInvalid("狼人数量必须 ≥ 1");
        }
        if (villageCount() < 1) {
            throw GameException.Factory.compositionInvalid("村民阵营数量必须 ≥ 1");
        }
    }

    /** 便于配置与日志的稳定文本形式。 */
    public String describe() {
        return counts.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .map(e -> e.getKey().name() + "=" + e.getValue())
                .collect(Collectors.joining(","));
    }

    public Collection<Role> roles() {
        return counts.keySet();
    }
}
