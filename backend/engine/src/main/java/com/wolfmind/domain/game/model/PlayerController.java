package com.wolfmind.domain.game.model;

/**
 * 玩家控制器：{@code {type, refId}}（ddd-design.md §3.3）。
 *
 * <p>{@code HUMAN} 时 {@code refId = userId}；{@code AI} 时 {@code refId = agentId}；
 * {@code BOT} 时 {@code refId = strategyKey}。领域只保存引用标识，不引用驱动实现。
 */
public record PlayerController(ControllerType type, String refId) {

    public PlayerController {
        if (type == null) {
            throw new IllegalArgumentException("controller.type 不能为空");
        }
        if (refId == null || refId.isBlank()) {
            throw new IllegalArgumentException("controller.refId 不能为空");
        }
    }

    public static PlayerController human(String userId) {
        return new PlayerController(ControllerType.HUMAN, userId);
    }

    public static PlayerController ai(String agentId) {
        return new PlayerController(ControllerType.AI, agentId);
    }

    public static PlayerController bot(String strategyKey) {
        return new PlayerController(ControllerType.BOT, strategyKey);
    }

    public boolean isHuman() {
        return type == ControllerType.HUMAN;
    }

    public boolean isAi() {
        return type == ControllerType.AI;
    }
}
