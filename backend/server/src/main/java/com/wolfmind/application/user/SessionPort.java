package com.wolfmind.application.user;

import java.util.Optional;

/**
 * 会话端口：签发、校验、撤销会话令牌。
 *
 * <p>令牌形态是 **{@code {userId}.{sessionId}.{secret}} 的不透明串**（redis-schema.md §5.9：
 * "不使用自包含签名令牌"）。校验 = 拆出三段 → 按 userId+sessionId 查记录 → 比对 secret 散列。
 *
 * <p><b>为什么不用 JWT</b>：登出必须立即可撤销。自包含令牌在过期前无法撤销，
 * 除非再维护一张黑名单——那还不如直接用不透明令牌加一张会话表。
 *
 * <p>令牌存在哪里（内存 / Redis / SQLite）由实现决定；端口只表达语义。
 */
public interface SessionPort {

    /**
     * 签发会话。
     *
     * @param userId    账号
     * @param sessionId 会话标识（进事件流用于审计）
     * @param secret    随机密钥（**不进事件流**，只以散列存进会话记录）
     * @param issuedAt  签发时刻（毫秒）
     * @param ttlMillis 有效期
     */
    void issue(String userId, String sessionId, String secret, long issuedAt, long ttlMillis);

    /** 校验令牌；成功返回账号与会话标识，失败返回空（不区分"格式错"与"已过期"）。 */
    Optional<Verified> verify(String token);

    /** 撤销单个会话；返回是否确实撤销了一个存在的会话。 */
    boolean revoke(String token);

    /** 撤销某账号的全部会话（改口令、禁用账号时用）。返回撤销数量。 */
    int revokeAll(String userId);

    record Verified(String userId, String sessionId, String token) {}
}