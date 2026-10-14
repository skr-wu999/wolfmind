package com.wolfmind.application.user;

import com.wolfmind.domain.user.model.User;
import com.wolfmind.domain.user.model.UserId;

import java.util.Optional;

/**
 * 账号仓储端口。
 *
 * <p><b>凭据（口令散列与盐）与账号事实分开存</b>：事实在 {@code user:{id}:events} 事件流里，
 * 凭据在仓储的实现里（将来是 SQLite 的 {@code users.password_hash/password_salt} 列）。
 * 分开的理由是 event-design.md §7.3 尾注与 redis-schema.md §5.9 的硬约束：
 * **口令与令牌不得进入事件流**。把它们放进聚合，迟早会被顺手写进事件 payload，
 * 而事件流是长期归档、可回放、可导出的。
 */
public interface UserRepositoryPort {

    /** 按用户名查账号；用户名唯一（INV-U1）。 */
    Optional<User> findByUsername(String username);

    Optional<User> findById(UserId userId);

    /** 保存账号事实（事件）与凭据。两者必须在一次调用内同时成功或同时失败。 */
    void save(User user, java.util.List<com.wolfmind.domain.game.event.DomainEvent> events, PasswordCredential credential);

    /**
     * 取凭据以校验口令。
     *
     * <p>单独一个方法而不是混进 {@code findById}：调用方按需取，避免"只是查询展示名却把
     * 散列读进内存"这种顺手做法。
     */
    Optional<PasswordCredential> credentialOf(UserId userId);

    /** 口令散列与盐。{@code algorithm} 进记录是为了将来换算法时能识别旧凭据。 */
    record PasswordCredential(String algorithm, String salt, String hash) {}
}