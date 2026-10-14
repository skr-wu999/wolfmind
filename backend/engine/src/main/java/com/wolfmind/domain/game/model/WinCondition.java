package com.wolfmind.domain.game.model;

/** 胜负条件（domain-model.md §3、ddd-design.md §2.4）。 */
public enum WinCondition {
    /**
     * 狼人胜当且仅当存活狼数 ≥ 村民阵营存活数；狼全灭则村民胜。
     *
     * <p>判定顺序固定：先判"狼全灭"，再判"狼数 ≥ 村人数"。因此最后一狼与最后一民同时死亡时
     * 判村民胜（domain-model.md §6.2）。
     */
    WOLVES_OUTNUMBER,

    /**
     * 屠边：狼全灭则村民胜；平民全灭或神职全灭则狼人胜，人数对比不参与判定。
     *
     * <p>判定顺序固定：先判"狼全灭"，再判"平民全灭"，最后判"神职全灭"。本局开局时没有
     * 某一边（例如实验室里没有神职的配置）时，该边不参与判定，否则开局即判狼人胜。
     */
    SIDE_KILL
}
