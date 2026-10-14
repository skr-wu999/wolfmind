package com.wolfmind.infrastructure.agent;

import com.wolfmind.domain.game.model.GameException;

/**
 * 应用层/领域拒绝 → 工具拒绝的翻译点。
 *
 * <p><b>这是本包唯一引用 {@code domain.game.model} 的第二个文件</b>（另一个是
 * {@link ViewerContexts}）。为什么无法避免：{@code SubmitActionApplicationService} 用
 * {@code GameException} 表达拒绝（{@code GAME_BUSY}、{@code TURN_NOT_YOURS}、
 * {@code GAME_VERSION_CONFLICT}、{@code ACTION_INVALID_TARGET}…），网关要把它们变成结构化拒绝。
 * 只 import 异常类型与错误码枚举，不 import 任何规则类型（{@code Game} / {@code Action} /
 * {@code GameRules} 等一个都不用）；拒绝码经 {@link DenialCodes#fromDomainCode(String)}
 * 归一化成字符串后，Agent 侧只认字符串。
 *
 * <p>非 {@code GameException} 的运行期异常<b>不翻译</b>：那些是真实故障（NPE、存储损坏），
 * 伪装成一次"结构化拒绝"会让模型以为换个动作就行（tool-gateway.md §5 的语义边界）。
 */
final class DomainRejectionTranslator {

    private DomainRejectionTranslator() {}

    /**
     * 把应用层拒绝翻译成结构化拒绝。
     *
     * @return 翻译结果；不是 {@code GameException} 时返回 {@code null}（调用方按故障处理）
     */
    static Denial translate(RuntimeException failure) {
        if (!(failure instanceof GameException gameException)) {
            return null;
        }
        String code = DenialCodes.fromDomainCode(gameException.code().name());
        String message = gameException.getMessage() == null
                ? "应用层拒绝了该动作（" + gameException.code().name() + "）"
                : gameException.getMessage();
        return Denial.of(code, message);
    }

    /** 该拒绝是否属于"版本冲突"（G13 刷新版本后重试）。 */
    static boolean isVersionConflict(RuntimeException failure) {
        return failure instanceof GameException gameException
                && "GAME_VERSION_CONFLICT".equals(gameException.code().name());
    }

    /** 单写者锁被占用（G13 短暂退避后以同一幂等键重试）。 */
    static boolean isBusy(RuntimeException failure) {
        return failure instanceof GameException gameException
                && "GAME_BUSY".equals(gameException.code().name());
    }
}
