package com.wolfmind.application.port;

import java.util.Optional;

/**
 * 单写者锁端口（system-design.md §4）。
 *
 * <p>三秒锁 TTL 与"单次命令处理不含 LLM 调用"是配套的：命令路径不得调用 LLM，
 * 否则锁会被拖到超时。AI 推理发生在锁之外，推理完成后才提交命令。
 */
public interface GameLockPort {

    /**
     * 尝试获取锁，不阻塞。
     *
     * @param gameId 按 {@code gameId} 加锁，天然支持多实例
     * @return 持有者令牌；获取失败返回空（调用方返回 {@code GAME_BUSY}，客户端退避重试）
     */
    Optional<LockToken> tryLock(String gameId);

    /** 释放锁；释放非本次持有的锁必须是安全操作（例如 TTL 已到期）。 */
    void unlock(LockToken token);

    /** 锁持有者令牌，释放时需要原样传回。 */
    record LockToken(String gameId, String ownerId, long acquiredAt, long expiresAt) {

        public boolean expiredAt(long now) {
            return now >= expiresAt;
        }
    }
}
