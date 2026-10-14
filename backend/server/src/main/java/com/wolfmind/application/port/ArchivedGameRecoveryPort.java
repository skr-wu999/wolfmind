package com.wolfmind.application.port;

/** 在事件流缺失时恢复完整终局归档；没有归档返回 false，损坏归档必须拒绝。 */
@FunctionalInterface
public interface ArchivedGameRecoveryPort {
    boolean restoreMissing(String gameId);
}
