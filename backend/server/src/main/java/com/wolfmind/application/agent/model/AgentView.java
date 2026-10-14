package com.wolfmind.application.agent.model;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * AI 侧的对局视图（agent-runtime.md §1、tool-gateway.md §2.1）。
 *
 * <p><b>它是 Agent 子系统里唯一的对局数据形状</b>，与领域无关：这里用字符串表达角色、阶段、
 * 阵营，因为 Agent 侧禁止引用 {@code domain.game.model}（DEP-7、C-5）。字段集合与
 * {@code GET /games/{gameId}} 的 {@code GameView} 一一对应，区别只是不携带领域枚举类型。
 *
 * <p>脱敏已经由领域完成：{@code PRIVATE} / {@code WOLF} / {@code JUDGE} 事件按可见性矩阵过滤后
 * 才进入本对象。因此：
 * <ul>
 *   <li>{@code other} 角色的 {@code role} 只在可见时才非空；</li>
 *   <li>不存在 {@code GOD} 形状——没有"上帝视角"这个可选值（tool-gateway.md §8）；</li>
 *   <li>{@code privateEvents} 只含本座位可见的私有事实摘要（查验结果、用药记录、狼队友）。</li>
 * </ul>
 *
 * @param gameId     对局标识
 * @param version    视图版本；工具网关的 {@code expectedVersion} 取自这里
 * @param status     对局状态：{@code INIT} / {@code RUNNING} / {@code FINISHED} / {@code ABORTED}
 * @param phase      当前阶段（字符串形式，例如 {@code DAY_DISCUSS}）
 * @param round      第几轮
 * @param turnIndex  当前发言序号
 * @param turnOrder  本轮发言顺序（座位号）
 * @param currentTurn 当前回合窗口；无回合阶段为 {@code null}
 * @param rules      本局规则参数（来自 {@code GAME_STARTED.rules}）
 * @param me         本座位视角；观战者视角为 {@code null}，AI 座位永不为 {@code null}
 * @param other      其他座位，按座位号升序
 * @param votes      当前投票轮的公开票
 * @param deaths     已公布死亡（不含死因，只带公开的出局方式）
 * @param speeches   公开与本人可见的发言时间线，按发生顺序
 * @param privateEvents 本座位可见的私有事实摘要（不含他人私有信息、不含 JUDGE 事实）
 * @param winner     终局后的获胜阵营；未结束为 {@code null}
 * @param wolfChat   本轮狼队夜聊（{@code WOLF_CHAT_SENT}），只有狼人视角非空，按发生顺序
 */
public record AgentView(
        String gameId,
        int version,
        String status,
        String phase,
        int round,
        int turnIndex,
        List<Integer> turnOrder,
        CurrentTurn currentTurn,
        Map<String, Object> rules,
        Self me,
        List<SeatView> other,
        List<VoteView> votes,
        List<DeathView> deaths,
        List<SpeechView> speeches,
        List<PrivateFact> privateEvents,
        String winner,
        List<WolfChatView> wolfChat) {

    public AgentView {
        turnOrder = List.copyOf(turnOrder);
        rules = Map.copyOf(rules);
        other = List.copyOf(other);
        votes = List.copyOf(votes);
        deaths = List.copyOf(deaths);
        speeches = List.copyOf(speeches);
        privateEvents = List.copyOf(privateEvents);
        wolfChat = List.copyOf(wolfChat);
    }

    /** 不带夜聊的视图（村民阵营、公开裁剪）。 */
    public AgentView(
            String gameId,
            int version,
            String status,
            String phase,
            int round,
            int turnIndex,
            List<Integer> turnOrder,
            CurrentTurn currentTurn,
            Map<String, Object> rules,
            Self me,
            List<SeatView> other,
            List<VoteView> votes,
            List<DeathView> deaths,
            List<SpeechView> speeches,
            List<PrivateFact> privateEvents,
            String winner) {
        this(gameId, version, status, phase, round, turnIndex, turnOrder, currentTurn, rules, me, other,
                votes, deaths, speeches, privateEvents, winner, List.of());
    }

    /** 一条狼队夜聊消息。 */
    public record WolfChatView(int seatNo, int round, String content) {}

    /** 当前回合窗口（tool-gateway.md G6 用它判定"是否轮到本人"）。 */
    public record CurrentTurn(
            String playerId, int seatNo, String phase, int turnIndex, long deadline) {}

    /** 本座位视角。 */
    public record Self(
            String playerId,
            int seatNo,
            String role,
            String camp,
            boolean alive,
            String controllerType,
            SkillView skills) {}

    /** 一次性技能可用性。 */
    public record SkillView(boolean antidoteAvailable, boolean poisonAvailable, boolean shotAvailable) {}

    /**
     * 其他座位在视图中的样子。
     *
     * <p>{@code role} 与 {@code camp} 只在读取者有权看到时才非空（自己的队友、或赛后公开）。
     */
    public record SeatView(
            String playerId, int seatNo, boolean alive, String role, String camp, String controllerType) {}

    /** 公开票。 */
    public record VoteView(int voterSeat, Integer targetSeatNo, boolean abstain) {}

    /**
     * 已公布的死亡，不含死因。
     *
     * <p>{@code way} 只取公开事件已经说明的出局方式：{@link #EXILE}（计票放逐）、
     * {@link #HUNTER_SHOT}（猎人开枪）、{@link #NIGHT}（夜里死亡，狼刀还是毒药不公布）；
     * 公开事件不在可读窗口内时为 {@link #UNKNOWN}，不能据此推断成夜里死亡。
     */
    public record DeathView(int seatNo, int round, String way) {
        public static final String EXILE = "EXILE";
        public static final String HUNTER_SHOT = "HUNTER_SHOT";
        public static final String NIGHT = "NIGHT";
        public static final String UNKNOWN = "UNKNOWN";

        public DeathView {
            way = way == null ? UNKNOWN : way;
        }

        public DeathView(int seatNo, int round) {
            this(seatNo, round, UNKNOWN);
        }
    }

    /** 一条公开发言（或本人遗言）。 */
    public record SpeechView(int seatNo, int round, String phase, int turnIndex, String content) {}

    /**
     * 本座位可见的私有事实摘要。
     *
     * <p>{@code kind} 是**封闭词汇表**（{@link FactKind}），不是自由字符串：策略层要按它归类
     * （女巫的解药候选只能是当夜刀口、预言家的嫌疑模型要用自己的查验结果当真值），
     * 如果允许自由文本，归类就只能靠关键词猜测，两边一定会漂移。
     *
     * <p>例如：预言家视角 {@code {kind:"SEER_INSPECT", targetSeat:5, value:"WOLF"}}；
     * 女巫视角 {@code {kind:"NIGHT_KILL", targetSeat:5, value:"KILLED"}}、
     * {@code {kind:"WITCH_SAVED", ...}}；狼人视角 {@code {kind:"WOLF_TEAMMATE", targetSeat:6,
     * value:"WEREWOLF"}}。
     *
     * <p>它是 {@code FactStore} 的 {@code PRIVATE_FACT} 来源。**只有本座位可见的事实才会出现**：
     * {@code NIGHT_KILL} 只发给女巫（她需要知道该救谁），{@code SEER_INSPECT} 只发给预言家。
     */
    public record PrivateFact(String kind, int targetSeat, String value, int round) {

        /** 私有事实的封闭词汇表。 */
        public enum FactKind {
            /** 预言家自己的查验结果；{@code value} 为 {@code WOLF} 或 {@code VILLAGE}。 */
            SEER_INSPECT,
            /** 当夜狼刀定案目标；只发给女巫，{@code value} 为 {@code KILLED}。 */
            NIGHT_KILL,
            /** 女巫本局用过解药；{@code value} 为 {@code SAVED}。 */
            WITCH_SAVED,
            /** 女巫本局用过毒药；{@code value} 为 {@code POISONED}。 */
            WITCH_POISONED,
            /** 狼队友；{@code value} 为 {@code WEREWOLF}。只发给狼人阵营。 */
            WOLF_TEAMMATE
        }

        public PrivateFact {
            if (kind == null || kind.isBlank()) {
                throw new IllegalArgumentException("私有事实必须有 kind");
            }
            if (targetSeat < 1) {
                throw new IllegalArgumentException("私有事实的 targetSeat 必须 ≥ 1");
            }
        }

        public static PrivateFact of(FactKind kind, int targetSeat, String value, int round) {
            return new PrivateFact(kind.name(), targetSeat, value, round);
        }

        /** 解析为词表值；未知 kind 返回 {@code null}，由调用方按"没有该事实"处理。 */
        public FactKind kindOrNull() {
            try {
                return FactKind.valueOf(kind);
            } catch (IllegalArgumentException exception) {
                return null;
            }
        }

        public boolean is(FactKind expected) {
            return kindOrNull() == expected;
        }
    }

    /** 当前是否轮到本人行动（无回合阶段返回 true，由工具网关按阶段另判）。 */
    public boolean isMyTurn() {
        return currentTurn == null || (me != null && me.playerId().equals(currentTurn.playerId()));
    }

    /** 本座位是否仍在局中。 */
    public boolean iAmAlive() {
        return me != null && me.alive();
    }

    /** 按座位号取其他座位视图。 */
    public Optional<SeatView> seat(int seatNo) {
        return other.stream().filter(view -> view.seatNo() == seatNo).findFirst();
    }

    /** 存活的其他座位，按座位号升序。 */
    public List<SeatView> aliveOthers() {
        return other.stream().filter(SeatView::alive).toList();
    }

    /** 存活且非本阵营的座位（狼队视角的刀口候选基础）。 */
    public List<SeatView> aliveOpponents(String myCamp) {
        return aliveOthers().stream()
                .filter(view -> view.camp() == null || !view.camp().equals(myCamp))
                .toList();
    }

    /** 取某条规则参数的整数值。 */
    public int ruleInt(String key, int fallback) {
        Object value = rules.get(key);
        if (value instanceof Number number) {
            return number.intValue();
        }
        return fallback;
    }

    /** 取某条规则参数的布尔值。 */
    public boolean ruleBool(String key, boolean fallback) {
        Object value = rules.get(key);
        if (value instanceof Boolean flag) {
            return flag;
        }
        return fallback;
    }

    /**
     * 本轮女巫能否对自己使用解药，与领域 {@code GameRules.witchCanSelfSave(round)} 同一口径。
     *
     * <p>两个键缺省都按"不放宽"处理：规则摘要里没有 {@code witchSelfSave} 时视为不可自救。
     */
    public boolean witchCanSelfSave() {
        return ruleBool("witchSelfSave", false)
                && (!ruleBool("witchSelfSaveFirstNightOnly", false) || round <= 1);
    }

    /** 本局胜负条件；缺省按领域默认值 {@code SIDE_KILL}。 */
    public String winCondition() {
        Object value = rules.get("winCondition");
        return value instanceof String text && !text.isBlank() ? text : "SIDE_KILL";
    }
}
