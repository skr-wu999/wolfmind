package com.wolfmind.infrastructure.agent;

import com.wolfmind.application.agent.model.AgentView;
import com.wolfmind.application.agent.model.ToolSession;

import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * G3 身份绑定：整个网关最关键的一步（tool-gateway.md §4 G3、§8"身份伪造"）。
 *
 * <p>三条不可动摇的规则：
 * <ol>
 *   <li>{@code gameId} / {@code playerId} / {@code role} / {@code seatNo} <b>只来自
 *       {@link ToolSession}（Runtime 注入）与角色可见视图</b>，从不来自模型输出；</li>
 *   <li>会话与视图必须完全一致：不一致说明会话属于别的座位、别的对局，或视图已经换了角色
 *       （INV-2 角色不可变），任何一种都必须立即拒绝；</li>
 *   <li>模型在自由文本里写的身份声明<b>不参与授权</b>；只有"声明了另一个座位/对局"这种
 *       明确的伪造才升级为拒绝，避免把"顺便提一句目标"误判成攻击。</li>
 * </ol>
 *
 * <p>为什么第 3 条要区分"忽略"与"拒绝"：{@code notes} 是模型的自由文本，把它当授权证据
 * 就是漏洞（AGENTS.md §8），把它当攻击证据又会把正常表达判成违规。所以：
 * 与本人或本回合目标一致 → 忽略（不进任何判定）；指向第三方 → 拒绝并让模型删掉这句。
 */
public final class IdentityGuard {

    /** 结构化身份声明：{@code playerId=p_9}、{@code "gameId": "g_other"} 这类写法。 */
    private static final Pattern CLAIM = Pattern.compile(
            "(?i)\\b(gameId|playerId|seatNo)\\b\\s*[:=]\\s*\"?([A-Za-z0-9_\\-.]+)\"?");

    /** 一条身份冲突。 */
    public record Conflict(String code, String message) {}

    private IdentityGuard() {}

    /**
     * 会话 ↔ 视图绑定。
     *
     * @param view 会话座位在该对局中的可见视图；{@code null} 表示该会话身份不是本局任何座位
     */
    public static Optional<Conflict> bind(ToolSession session, AgentView view) {
        if (session == null) {
            return Optional.of(new Conflict(DenialCodes.TOOL_IDENTITY_MISMATCH,
                    "缺少工具会话：身份必须由 Runtime 注入"));
        }
        if (view == null || view.me() == null) {
            return Optional.of(new Conflict(DenialCodes.TOOL_IDENTITY_MISMATCH,
                    "工具会话身份 " + session.playerId() + " 不是对局 " + session.gameId() + " 的座位"));
        }
        if (!session.gameId().equals(view.gameId())) {
            return Optional.of(new Conflict(DenialCodes.TOOL_IDENTITY_MISMATCH,
                    "工具会话的对局 " + session.gameId() + " 与视图的对局 " + view.gameId() + " 不一致"));
        }
        if (!session.playerId().equals(view.me().playerId())) {
            return Optional.of(new Conflict(DenialCodes.TOOL_IDENTITY_MISMATCH,
                    "工具会话的玩家 " + session.playerId() + " 与视图座位玩家 "
                            + view.me().playerId() + " 不一致"));
        }
        if (session.seatNo() != view.me().seatNo()) {
            return Optional.of(new Conflict(DenialCodes.TOOL_IDENTITY_MISMATCH,
                    "工具会话的座位 " + session.seatNo() + " 与视图座位 "
                            + view.me().seatNo() + " 不一致"));
        }
        if (session.role() != null && view.me().role() != null
                && !session.role().equals(view.me().role())) {
            return Optional.of(new Conflict(DenialCodes.TOOL_IDENTITY_MISMATCH,
                    "工具会话的角色 " + session.role() + " 与视图角色 " + view.me().role()
                            + " 不一致（INV-2 角色不可变，出现差异说明会话来自另一局）"));
        }
        return Optional.empty();
    }

    /**
     * 自由文本里的结构化身份声明。
     *
     * <p>只有指向"第三方"的声明才拒绝：与本人一致、或与本回合声明的目标一致，都按
     * "顺口一提"忽略。注意忽略不等于采信——这些值永远不会进入任何授权判定。
     *
     * @param targetId       本回合声明的目标玩家 id（来自工具参数，不是授权证据）
     * @param targetSeatNo   本回合声明的目标座位号
     */
    public static Optional<Conflict> claimedIdentity(
            String notes, ToolSession session, String targetId, Integer targetSeatNo) {
        if (notes == null || notes.isBlank() || session == null) {
            return Optional.empty();
        }
        Matcher matcher = CLAIM.matcher(notes);
        while (matcher.find()) {
            String key = matcher.group(1).toLowerCase(java.util.Locale.ROOT);
            String value = matcher.group(2);
            if (isConsistent(key, value, session, targetId, targetSeatNo)) {
                continue;
            }
            return Optional.of(new Conflict(DenialCodes.TOOL_IDENTITY_MISMATCH,
                    "notes 里声明了 " + key + "=" + value + "，与会话身份不一致："
                            + "模型不能指定座位或对局（tool-gateway.md §8）"));
        }
        return Optional.empty();
    }

    private static boolean isConsistent(
            String key, String value, ToolSession session, String targetId, Integer targetSeat) {
        return switch (key) {
            case "gameid" -> session.gameId().equals(value);
            case "playerid" -> session.playerId().equals(value)
                    || (targetId != null && targetId.equals(value));
            case "seatno" -> isSeatClaimConsistent(value, session, targetSeat);
            default -> true;
        };
    }

    private static boolean isSeatClaimConsistent(String value, ToolSession session, Integer targetSeat) {
        int claimed;
        try {
            claimed = Integer.parseInt(value);
        } catch (NumberFormatException ex) {
            // 不是座位号（可能是别的意思），按"不构成身份声明"处理
            return true;
        }
        return claimed == session.seatNo() || (targetSeat != null && claimed == targetSeat);
    }
}
