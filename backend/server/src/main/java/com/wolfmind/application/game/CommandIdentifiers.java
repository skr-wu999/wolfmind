package com.wolfmind.application.game;

/**
 * 应用层生成的标识与种子（ddd-design.md §6.1 第 4 步：事件 id、随机种子都由应用层注入领域）。
 *
 * <p>全部取值都是"入参的确定性函数"，不读时钟、不读随机源：同一条命令重试得到同一个事件 id 前缀，
 * 便于事件存储按版本 id 去重；随机种子只影响开局洗牌，且洗牌结果会写进 {@code ROLE_ASSIGNED}，
 * 因此重放不依赖种子（INV-16）。
 */
final class CommandIdentifiers {

    private CommandIdentifiers() {}

    /**
     * 事件 id 基串 {@code gameId:下一版本}。
     *
     * <p>领域在 {@code EventMeta} 里以 {@code 基串-version} 逐条派生（{@code ActionContext.eventIdFor}），
     * 因此一次命令内的多条事件天然唯一，且同一 {@code (gameId, version)} 重试时 id 不变。
     */
    static String eventIdBase(String gameId, int nextVersion) {
        return gameId + ":" + nextVersion;
    }

    /** {@code correlationId} 缺省回落到 {@code commandId}：api-spec.md §1.1 要求 traceId 可直接用于排查。 */
    static String correlationId(String requested, String commandId) {
        return requested == null || requested.isBlank() ? commandId : requested;
    }

    /**
     * 随机种子：由 {@code (gameId, commandId, occurredAt)} 稳定派生（FNV-1a 64 位）。
     *
     * <p>不引入新的随机端口，也不把非确定性带进领域：种子随命令确定，掷出的结果由事件承载。
     */
    static long randomSeed(String gameId, String commandId, long occurredAt) {
        long hash = 0xcbf29ce484222325L;
        for (String part : new String[] {gameId, commandId}) {
            for (int index = 0; index < part.length(); index++) {
                hash ^= part.charAt(index);
                hash *= 0x100000001b3L;
            }
            hash ^= '|';
            hash *= 0x100000001b3L;
        }
        hash ^= occurredAt;
        return hash * 0x100000001b3L;
    }
}
