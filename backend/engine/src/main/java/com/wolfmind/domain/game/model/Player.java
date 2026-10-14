package com.wolfmind.domain.game.model;

import java.util.Objects;

/**
 * 对局内的玩家实体（domain-model.md §5.1）。
 *
 * <p>不含 {@code Agent}、{@code LlmClient}、{@code ToolGateway} 等任何 AI 类型（C-5）；
 * {@code controller.type == AI} 只说明"这个座位由 Agent Runtime 驱动"，驱动方式在领域之外。
 *
 * <p>字段全部通过 {@code Game} 的 {@code apply(...)} 方法修改，本类不对外提供 setter
 * （INV-13：状态只能由事件修改）。{@code role} 只在 {@code ROLE_ASSIGNED} 的 apply 中写入一次（INV-2）。
 */
public final class Player {

    private final PlayerId playerId;
    private final SeatNo seatNo;
    private Role role;
    private boolean alive;
    private PlayerController controller;
    private boolean ready;
    private SkillState skillState;

    /** 由 {@code Game} 构造：开局、重放与快照恢复都走这个入口。 */
    public Player(PlayerId playerId, SeatNo seatNo, PlayerController controller, boolean ready) {
        this.playerId = Objects.requireNonNull(playerId, "playerId");
        this.seatNo = Objects.requireNonNull(seatNo, "seatNo");
        this.controller = Objects.requireNonNull(controller, "controller");
        this.ready = ready;
        this.alive = true;
        this.role = null;
        this.skillState = SkillState.NONE;
    }

    public PlayerId playerId() {
        return playerId;
    }

    public SeatNo seatNo() {
        return seatNo;
    }

    /** 开局固化后不可变（INV-2）。开局前为 {@code null}。 */
    public Role role() {
        return role;
    }

    /** 由 {@code role} 派生，不单独存储。开局前为 {@code null}。 */
    public Camp camp() {
        return role == null ? null : role.camp();
    }

    public boolean alive() {
        return alive;
    }

    public PlayerController controller() {
        return controller;
    }

    public boolean ready() {
        return ready;
    }

    public SkillState skillState() {
        return skillState;
    }

    /** 是否有夜间行动能力且仍然存活。 */
    public boolean aliveWithRole(Role expected) {
        return alive && role == expected;
    }

    /**
     * 该座位是否由给定 actor 控制。
     *
     * <p>真人比对 {@code userId}，AI/Bot 比对 {@code agentId} / {@code strategyKey}。
     * 领域只做引用标识比对，不解析其结构。
     */
    public boolean controlledBy(String actorId) {
        return actorId != null && controller.refId().equals(actorId);
    }

    // ---- 以下方法只允许 Game 的 apply(...) 调用（INV-13） ----

    void applyRole(Role role) {
        this.role = Objects.requireNonNull(role, "role");
        this.skillState = SkillState.forRole(role);
    }

    void kill() {
        this.alive = false;
    }

    void changeController(PlayerController newController) {
        this.controller = Objects.requireNonNull(newController, "newController");
    }

    void markReady(boolean ready) {
        this.ready = ready;
    }

    void applySkillState(SkillState newSkillState) {
        this.skillState = Objects.requireNonNull(newSkillState, "skillState");
    }

    PlayerSnapshot snapshot() {
        return new PlayerSnapshot(playerId, seatNo, role, camp(), alive, controller, ready, skillState);
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof Player player && playerId.equals(player.playerId);
    }

    @Override
    public int hashCode() {
        return playerId.hashCode();
    }

    @Override
    public String toString() {
        return "Player{" + playerId + ", seat=" + seatNo + ", role=" + role + ", alive=" + alive + '}';
    }
}
