package com.wolfmind.application.port;

import com.wolfmind.domain.game.event.EventEnvelope;
import com.wolfmind.domain.game.model.ViewerContext;
import com.wolfmind.domain.game.view.GameViewSnapshot;

import java.util.List;
import java.util.Map;

/**
 * 视图查询端口（只读）。
 *
 * <p>只读用例不产生事件：不变量约束的是"状态变化"，读模型投影的推进由消费组位点记录，
 * 不进入领域事件流（ddd-design.md §6.2）。
 *
 * <p>返回类型是 {@link GameViewSnapshot}：脱敏（谁能看到谁的角色、哪些死亡已公布）已由领域的
 * {@code GameViewProjector} 完成，接口层只做 JSON 映射。**不要在这里再写一份可见性判断**——
 * 那是 AP-6，也是隐藏信息泄漏的典型成因。
 */
public interface GameViewQueryPort {

    /**
     * 读取角色可见的视图。
     *
     * @param viewer 读取者身份；决定 {@code me} 与 {@code players[].role} 的可见性
     */
    GameViewSnapshot view(String gameId, ViewerContext viewer);

    /**
     * 读取事件回放区间。
     *
     * <p>返回信封形态；可见性过滤使用 {@code VisibilityFilter.filter(...)}，不要自行判断。
     *
     * @param fromVersion 起始版本（含）
     * @param toVersion   结束版本（含）
     * @param maxEvents   单次上限（api-spec.md §6 规定 500）
     */
    List<EventEnvelope> replay(String gameId, ViewerContext viewer, int fromVersion, int toVersion, int maxEvents);

    /**
     * 与 {@link #replay} 同源，但额外回答两件回放接口必须知道的事：
     * <b>真实水位</b>与<b>数据是不是来自归档</b>。
     *
     * <p>为什么不能让调用方自己拼：判定需要读一次存储才知道，而分开问会读两次
     * ——两次之间事件流还可能变化，于是 {@code to} 与实际返回的区间对不上。
     *
     * @return 可见事件、该对局的真实水位、以及本次是否读自 SQLite 归档
     */
    default ReplayPage replayPage(
            String gameId, ViewerContext viewer, int fromVersion, int toVersion, int maxEvents) {
        return new ReplayPage(
                replay(gameId, viewer, fromVersion, toVersion, maxEvents), currentVersion(gameId), false);
    }

    /**
     * 回放读取结果。
     *
     * @param availableVersion 真实水位：Redis 流尾，或归档到的最大版本
     * @param fromArchive      {@code true} 表示事件流已不可用，本次读自 SQLite 归档
     *                         （api-spec.md §6 的 {@code truncated} 字段）
     */
    record ReplayPage(List<EventEnvelope> events, int availableVersion, boolean fromArchive) {

        public ReplayPage {
            events = List.copyOf(events);
        }
    }

    /**
     * 读取**指定版本**的对局视图（api-spec.md §6 的 {@code ?atVersion=}）。
     *
     * <p>回放页要回答"第 N 步的棋盘是什么样"，而不是"现在是什么样"。它必须由服务端重建：
     * 交给客户端按事件增量演进，等于让前端再实现一遍领域投影，而那份实现必然会与
     * {@code GameViewProjector} 的脱敏规则漂移（AP-6）。
     */
    GameViewSnapshot viewAt(String gameId, ViewerContext viewer, int atVersion);

    /** 对局列表（观战页与分析页入口），字段见 api-spec.md §5.4。 */
    List<Map<String, Object>> listGames(String status, String roomId, int page, int pageSize);

    /**
     * 服务端当前持有的最大事件版本（流尾）；对局不存在时为 0。
     *
     * <p><b>不能用 {@link #replay} 的长度代替它</b>：{@code replay} 有 500 条上限（api-spec.md §6），
     * 于是"可见事件少于上限"与"流尾就是这个长度"无法区分。WebSocket 的 {@code SUBSCRIBED.to}
     * 与 {@code truncated} 判定都需要**真实的流尾**，用长度会让一个已有 600 版的对局
     * 把 {@code to} 报成 500，客户端据此以为自己同步到了 500 而实际上后面还有 100 版。
     */
    int currentVersion(String gameId);

    /**
     * 构造读取者上下文：按账号在对局里反查它控制的座位。
     *
     * <p><b>为什么必须由服务端反查，而不能让调用方传座位集合</b>：座位集合是可见性判定的
     * 输入之一（决定了能不能看到自己的角色、能不能看到狼队）。让调用方传等于把"我是谁"
     * 的声明权交给客户端——一个村民只要声称自己是某个狼人座位就能拿到狼队信息
     * （AGENTS.md §8、event-design.md §4 硬规则 2）。
     *
     * <p>它是 WebSocket 订阅的第一步：没有它，每个订阅者都只能看到 PUBLIC 事件，
     * 连自己的角色都看不到。
     *
     * @param viewerUserId 已认证账号；匿名时传 {@code null}
     * @return 含正确 {@code myPlayerIds} 与狼队集合的上下文；账号不是参与者时为匿名或只读参与者
     */
    ViewerContext viewerContextFor(String gameId, String viewerUserId);
}
