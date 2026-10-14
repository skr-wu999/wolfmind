package com.wolfmind.domain.game.model;

/** 对局生命周期（domain-model.md §2.3）。 */
public enum GameStatus {
    /** 聚合已创建、尚未开局。 */
    INIT,
    /** 进行中。 */
    RUNNING,
    /** 已分出胜负，终态。 */
    FINISHED,
    /** 已中止，终态。 */
    ABORTED
}
