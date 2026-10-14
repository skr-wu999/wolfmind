package com.wolfmind.domain.game.model;

import java.util.List;
import java.util.Objects;

/**
 * 开始对局命令（ddd-design.md §1.3 的 Command）。
 *
 * @param gameId         新对局标识
 * @param roomId         来源房间标识；实验室批量对局同样有来源标识，不引入特殊通道
 * @param seats          座位分配，恰好 {@code roleComposition.playerCount()} 个且座位号不重复（INV-1）
 * @param roleComposition 角色配置，开局固化并写入 {@code GAME_STARTED}
 * @param rules          规则参数，开局固化并写入 {@code GAME_STARTED}（重放必须用事件里的规则）
 */
public record StartGameCommand(
        GameId gameId,
        String roomId,
        List<SeatAssignment> seats,
        RoleComposition roleComposition,
        GameRules rules) {

    public StartGameCommand {
        Objects.requireNonNull(gameId, "gameId");
        if (roomId == null || roomId.isBlank()) {
            throw GameException.Factory.validation("roomId 不能为空");
        }
        if (seats == null || seats.isEmpty()) {
            throw GameException.Factory.validation("seats 不能为空");
        }
        seats = List.copyOf(seats);
        Objects.requireNonNull(roleComposition, "roleComposition");
        Objects.requireNonNull(rules, "rules");

        long distinctSeats = seats.stream().map(SeatAssignment::seatNo).distinct().count();
        if (distinctSeats != seats.size()) {
            throw GameException.Factory.compositionInvalid("座位号重复（INV-1）");
        }
        roleComposition.validateAgainstSeatCount(seats.size());
    }

    /** 是否全部座位就绪（INV-R2）。 */
    public boolean allReady() {
        return seats.stream().allMatch(SeatAssignment::ready);
    }
}
