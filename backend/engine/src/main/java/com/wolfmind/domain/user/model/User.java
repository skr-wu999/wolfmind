package com.wolfmind.domain.user.model;

import com.wolfmind.domain.game.model.GameException;

/**
 * 账号聚合（ddd-design.md §2.1、§4.3）。
 *
 * <p>不变量：
 * <ul>
 *   <li><b>INV-U1</b> 用户名唯一；口令**只以散列与盐**形式存储；</li>
 *   <li><b>INV-U2</b> 账号状态变化（注册、登录、登出）产生事件。</li>
 * </ul>
 *
 * <p><b>口令散列与盐不是本聚合的字段</b>：它们属于凭据存储（{@code UserRepository} 的实现
 * 与将来 SQLite 的 {@code users} 表）。理由有两条：一是**口令不得进入事件流**
 * （event-design.md §7.3 尾注、redis-schema.md §5.9），聚合的每次状态变化都会产出事件，
 * 把散列放进聚合就迟早会被顺手写进 payload；二是聚合应当能被事件流完全重建，
 * 而散列不能从事件重建（如果它进了事件，那它就已经泄漏了）。
 *
 * <p>因此本聚合只承载"账号存在、状态如何、何时登录过"这些**事实**，凭据单独走
 * {@code UserRepository} 的凭据方法。
 */
public final class User {

    /** 账号状态。 */
    public enum Status {
        ACTIVE,
        DISABLED
    }

    private final UserId userId;
    private String username;
    private String displayName;
    private Status status = Status.ACTIVE;
    private Long createdAt;
    private Long lastLoginAt;
    private Long lastLogoutAt;

    private User(UserId userId) {
        this.userId = userId;
    }

    public static User create(UserId userId) {
        return new User(userId);
    }

    public UserId userId() {
        return userId;
    }

    public String username() {
        return username;
    }

    public String displayName() {
        return displayName;
    }

    public Status status() {
        return status;
    }

    public Long createdAt() {
        return createdAt;
    }

    public Long lastLoginAt() {
        return lastLoginAt;
    }

    public Long lastLogoutAt() {
        return lastLogoutAt;
    }

    /** 是否允许登录：只有 ACTIVE 的账号可以。 */
    public boolean canLogin() {
        return status == Status.ACTIVE;
    }

    /** 账号是否已注册（已应用过 USER_REGISTERED）。 */
    public boolean registered() {
        return username != null;
    }

    /**
     * 注册。
     *
     * <p>用户名唯一性**不在这里判断**：它需要"全库查重"，属于仓储职责
     * （SQLite 的 {@code ux_users_username} 唯一索引是最终保证）。聚合只保证
     * "同一账号不能注册两次"。
     */
    public com.wolfmind.domain.user.event.UserRegisteredEvent register(
            String username, String displayName,
            com.wolfmind.domain.game.model.ActionContext ctx) {
        if (registered()) {
            throw GameException.Factory.validation("账号已注册，不能重复注册");
        }
        if (username == null || username.isBlank()) {
            throw GameException.Factory.validation("用户名不能为空");
        }
        com.wolfmind.domain.user.event.UserRegisteredEvent event =
                new com.wolfmind.domain.user.event.UserRegisteredEvent(
                        meta(ctx), userId, username);
        apply(event);
        return event;
    }

    /** 登录。{@code sessionId} 进事件（可审计），但**令牌本身不进**（它是凭据）。 */
    public com.wolfmind.domain.user.event.UserLoggedInEvent login(
            String sessionId, long at,
            com.wolfmind.domain.game.model.ActionContext ctx) {
        if (!registered()) {
            throw GameException.Factory.userNotFound(userId.value());
        }
        if (!canLogin()) {
            throw GameException.Factory.forbidden("账号状态为 " + status + "，不能登录");
        }
        com.wolfmind.domain.user.event.UserLoggedInEvent event =
                new com.wolfmind.domain.user.event.UserLoggedInEvent(
                        meta(ctx), userId, sessionId, at);
        apply(event);
        return event;
    }

    /** 登出。 */
    public com.wolfmind.domain.user.event.UserLoggedOutEvent logout(
            String sessionId, long at,
            com.wolfmind.domain.game.model.ActionContext ctx) {
        com.wolfmind.domain.user.event.UserLoggedOutEvent event =
                new com.wolfmind.domain.user.event.UserLoggedOutEvent(
                        meta(ctx), userId, sessionId, at);
        apply(event);
        return event;
    }

    /** 按提交顺序重放事件（恢复路径）。 */
    public void replay(com.wolfmind.domain.game.event.DomainEvent event) {
        apply(event);
    }

    /** 账号事件的元数据；无对局阶段，用 GAME_INIT 与 round=1（与房间事件同一约定）。 */
    private static com.wolfmind.domain.game.event.EventMeta meta(
            com.wolfmind.domain.game.model.ActionContext ctx) {
        return new com.wolfmind.domain.game.event.EventMeta(
                ctx.eventIdFor(1),
                1,
                ctx.occurredAt(),
                com.wolfmind.domain.game.model.GamePhase.GAME_INIT,
                1,
                ctx.actorId(),
                ctx.commandId(),
                ctx.correlationId());
    }

    private void apply(com.wolfmind.domain.game.event.DomainEvent event) {
        switch (event) {
            case com.wolfmind.domain.user.event.UserRegisteredEvent registered ->
                    applyRegistered(registered);
            case com.wolfmind.domain.user.event.UserLoggedInEvent loggedIn -> applyLoggedIn(loggedIn);
            case com.wolfmind.domain.user.event.UserLoggedOutEvent loggedOut -> applyLoggedOut(loggedOut);
            default -> throw new IllegalStateException(
                    "User 聚合不认识事件 " + event.type() + "（回放路径不得静默忽略）");
        }
    }

    private void applyRegistered(com.wolfmind.domain.user.event.UserRegisteredEvent event) {
        this.username = event.username();
        this.displayName = event.username();
        this.createdAt = event.meta().occurredAt();
    }

    private void applyLoggedIn(com.wolfmind.domain.user.event.UserLoggedInEvent event) {
        this.lastLoginAt = event.at();
    }

    private void applyLoggedOut(com.wolfmind.domain.user.event.UserLoggedOutEvent event) {
        this.lastLogoutAt = event.at();
    }
}