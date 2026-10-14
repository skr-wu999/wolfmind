package com.wolfmind.domain.game.model;

/**
 * 领域拒绝原因。
 *
 * <p>取值与 docs/backend/api-spec.md §1.2 的错误码表一一对应：接口层把领域拒绝码直接映射为 HTTP 状态，
 * 不做二次翻译，避免出现两套原因词汇。
 *
 * <p>凡是 docs/backend/domain-model.md §6.1 与 ddd-design.md §4.1 指明"拒绝"的路径都必须抛出
 * {@link GameException}，且被拒绝的命令不得留下任何事件（INV-11）。
 */
public enum DomainErrorCode {

    // ---- 请求形态 ----
    /** 参数缺失或格式错误。 */
    VALIDATION_ERROR(ErrorCategory.INVALID_COMMAND),
    /** 目标非法：不存在、已死亡、指向自身或违反角色约束（domain-model.md V7）。 */
    ACTION_INVALID_TARGET(ErrorCategory.INVALID_COMMAND),

    // ---- 对局生命周期 ----
    /** 对局不在 RUNNING 状态（INV-12）。 */
    GAME_ALREADY_FINISHED(ErrorCategory.LIFECYCLE),
    /** 对局尚未开局，或已中止。 */
    GAME_NOT_RUNNING(ErrorCategory.LIFECYCLE),
    /** 对局已经开局，不能重复开局（INV-2：角色不可变）。 */
    GAME_ALREADY_STARTED(ErrorCategory.LIFECYCLE),
    /** 对局正在恢复中。 */
    GAME_RECOVERING(ErrorCategory.LIFECYCLE),

    // ---- 行动合法性（domain-model.md V3/V4/V5/V8） ----
    /** 阶段或角色不允许该行动。 */
    ACTION_NOT_ALLOWED(ErrorCategory.ACTION_ILLEGAL),
    /** 行动者不是当前回合持有者。 */
    TURN_NOT_YOURS(ErrorCategory.ACTION_ILLEGAL),
    /** 行动者不是该座位的控制者。 */
    SEAT_NOT_YOURS(ErrorCategory.ACTION_ILLEGAL),
    /** 幂等：该座位在本阶段已经提交过，返回首次结果（V6）。 */
    ACTION_ALREADY_SUBMITTED(ErrorCategory.IDEMPOTENT),
    /** 资源不可用（解药/毒药/开枪次数已耗尽）。 */
    SKILL_UNAVAILABLE(ErrorCategory.ACTION_ILLEGAL),
    /** 发言内容超长或不合法。 */
    SPEECH_INVALID(ErrorCategory.INVALID_COMMAND),

    // ---- 开局配置（INV-1 与 RoleComposition 约束） ----
    /** 座位数与角色配置不一致。 */
    ROLE_COMPOSITION_INVALID(ErrorCategory.CONFIGURATION),
    /** 规则参数不合法（时限、长度上限等自相矛盾）。 */
    RULES_INVALID(ErrorCategory.CONFIGURATION),

    // ---- 事件与重放 ----
    /** 聚合版本与期望版本不一致。 */
    GAME_VERSION_CONFLICT(ErrorCategory.CONFLICT),
    /** 重放的事件版本不连续或不是下一条（INV-16）。 */
    REPLAY_OUT_OF_ORDER(ErrorCategory.REPLAY),
    /** 重放遇到未知事件类型。 */
    REPLAY_UNKNOWN_EVENT_TYPE(ErrorCategory.REPLAY),
    /** 快照校验和不匹配。 */
    SNAPSHOT_CHECKSUM_MISMATCH(ErrorCategory.REPLAY),

    // ---- 基础设施（由基础设施层抛出，接口层映射为 5xx） ----
    /** 单写者锁被占用。 */
    GAME_BUSY(ErrorCategory.INFRASTRUCTURE),
    /** 依赖不可用。 */
    SERVICE_DEGRADED(ErrorCategory.INFRASTRUCTURE),
    /** 未预期异常。 */
    INTERNAL_ERROR(ErrorCategory.INFRASTRUCTURE),

    /*
     * 房间与用户（Phase 4）。api-spec.md §1.2 早已列出这些码，但领域侧此前没有定义——
     * 而 Errors.statusOf 对未登记的码会抛 IllegalStateException（宁可 500 显形），
     * 所以"文档里有、代码里没有"会直接变成线上 500。这里补齐。
     */
    AUTH_REQUIRED(ErrorCategory.ACTION_ILLEGAL),
    ROOM_NOT_FOUND(ErrorCategory.LIFECYCLE),
    ROOM_FULL(ErrorCategory.CONFLICT),
    ALREADY_SEATED(ErrorCategory.CONFLICT),
    NOT_SEATED(ErrorCategory.ACTION_ILLEGAL),
    NOT_READY(ErrorCategory.CONFLICT),
    ROOM_CLOSED(ErrorCategory.LIFECYCLE),
    USER_NOT_FOUND(ErrorCategory.LIFECYCLE),
    USERNAME_TAKEN(ErrorCategory.CONFLICT);



    /** 错误大类，供接口层决定 HTTP 状态与"是否可重试"。 */
    public enum ErrorCategory {
        INVALID_COMMAND,
        LIFECYCLE,
        ACTION_ILLEGAL,
        IDEMPOTENT,
        CONFIGURATION,
        CONFLICT,
        REPLAY,
        INFRASTRUCTURE
    }

    private final ErrorCategory category;

    DomainErrorCode(ErrorCategory category) {
        this.category = category;
    }

    public ErrorCategory category() {
        return category;
    }

    /** 客户端是否应当自动重试（api-spec.md §1.2 的可重试/不可重试区分）。 */
    public boolean retryable() {
        return switch (category) {
            case CONFLICT, INFRASTRUCTURE -> true;
            default -> false;
        };
    }
}
