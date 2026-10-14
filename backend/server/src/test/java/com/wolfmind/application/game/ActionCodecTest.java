package com.wolfmind.application.game;

import com.wolfmind.domain.game.model.Action;
import com.wolfmind.domain.game.model.ActionType;
import com.wolfmind.domain.game.model.DomainErrorCode;
import com.wolfmind.domain.game.model.GameException;
import com.wolfmind.domain.game.model.HunterShotAction;
import com.wolfmind.domain.game.model.SeatNo;
import com.wolfmind.domain.game.model.SeerInspectAction;
import com.wolfmind.domain.game.model.SkipAction;
import com.wolfmind.domain.game.model.SkipReason;
import com.wolfmind.domain.game.model.SpeechAction;
import com.wolfmind.domain.game.model.VoteAction;
import com.wolfmind.domain.game.model.WitchAction;
import com.wolfmind.domain.game.model.WitchMode;
import com.wolfmind.domain.game.model.WolfChatAction;
import com.wolfmind.domain.game.model.WolfKillAction;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** {@link ActionCodec}：接口契约与领域 Action 之间的双向映射，以及参数形态校验。 */
class ActionCodecTest {

    private static final SeatNo SEAT = SeatNo.of(3);

    private final ActionCodec codec = new ActionCodec();

    @Test
    @DisplayName("SPEAK：content 还原为 SpeechAction，行动者取自提交座位")
    void decodeSpeak() {
        Action action = codec.decode(ActionType.SPEAK, Map.of("content", "我是预言家"), SEAT);

        SpeechAction speech = (SpeechAction) action;
        assertEquals(SEAT, speech.actor());
        assertEquals("我是预言家", speech.content());
        assertEquals(ActionType.SPEAK, action.type());
    }

    @Test
    @DisplayName("VOTE：targetSeatNo 与 abstain 两种形态")
    void decodeVote() {
        VoteAction vote = (VoteAction) codec.decode(ActionType.VOTE, Map.of("targetSeatNo", 6), SEAT);
        assertEquals(SEAT, vote.actor());
        assertEquals(SeatNo.of(6), vote.target());
        assertFalse(vote.abstain());

        VoteAction abstain = (VoteAction) codec.decode(ActionType.VOTE, Map.of("abstain", true), SEAT);
        assertTrue(abstain.abstain());
        assertNull(abstain.target());
    }

    @Test
    @DisplayName("WOLF_KILL：targetSeatNo 与 empty 两种形态")
    void decodeWolfKill() {
        WolfKillAction kill = (WolfKillAction) codec.decode(ActionType.WOLF_KILL, Map.of("targetSeatNo", 7), SEAT);
        assertEquals(SeatNo.of(7), kill.target());
        assertFalse(kill.isEmptyKill());

        WolfKillAction empty = (WolfKillAction) codec.decode(ActionType.WOLF_KILL, Map.of("empty", true), SEAT);
        assertTrue(empty.isEmptyKill());
    }

    @Test
    @DisplayName("WOLF_CHAT：content 必填且为非空字符串")
    void decodeWolfChat() {
        WolfChatAction chat = (WolfChatAction) codec.decode(ActionType.WOLF_CHAT, Map.of("content", "刀 7 号"), SEAT);
        assertEquals(SEAT, chat.actor());
        assertEquals("刀 7 号", chat.content());
        assertEquals(ActionType.WOLF_CHAT, codec.typeOf(chat));

        GameException missing = assertThrows(GameException.class,
                () -> codec.decode(ActionType.WOLF_CHAT, Map.of(), SEAT));
        assertEquals(DomainErrorCode.VALIDATION_ERROR, missing.code());
    }

    @Test
    @DisplayName("SEER_INSPECT：目标必填")
    void decodeSeerInspect() {
        SeerInspectAction inspect =
                (SeerInspectAction) codec.decode(ActionType.SEER_INSPECT, Map.of("targetSeatNo", 2), SEAT);
        assertEquals(SeatNo.of(2), inspect.target());
    }

    @Test
    @DisplayName("WITCH：SAVE / POISON / PASS 三种模式映射到同一个 Action 类型")
    void decodeWitch() {
        WitchAction save = (WitchAction) codec.decode(ActionType.WITCH_SAVE, Map.of("targetSeatNo", 4), SEAT);
        assertEquals(WitchMode.SAVE, save.mode());
        assertEquals(SeatNo.of(4), save.target());

        WitchAction poison = (WitchAction) codec.decode(ActionType.WITCH_POISON, Map.of("targetSeatNo", 5), SEAT);
        assertEquals(WitchMode.POISON, poison.mode());

        WitchAction pass = (WitchAction) codec.decode(ActionType.WITCH_PASS, Map.of(), SEAT);
        assertEquals(WitchMode.PASS, pass.mode());
        assertNull(pass.target());
    }

    @Test
    @DisplayName("HUNTER_SHOOT：targetSeatNo 与 giveUp 两种形态")
    void decodeHunterShot() {
        HunterShotAction shot =
                (HunterShotAction) codec.decode(ActionType.HUNTER_SHOOT, Map.of("targetSeatNo", 8), SEAT);
        assertEquals(SeatNo.of(8), shot.target());
        assertFalse(shot.isGiveUp());

        HunterShotAction giveUp =
                (HunterShotAction) codec.decode(ActionType.HUNTER_SHOOT, Map.of("giveUp", true), SEAT);
        assertTrue(giveUp.isGiveUp());
    }

    @Test
    @DisplayName("SKIP：reason 缺省为主动跳过，显式给值则按值解析")
    void decodeSkip() {
        SkipAction defaulted = (SkipAction) codec.decode(ActionType.SKIP, Map.of(), SEAT);
        assertEquals(SkipReason.VOLUNTARY, defaulted.reason());

        SkipAction timeout = (SkipAction) codec.decode(ActionType.SKIP, Map.of("reason", "TIMEOUT"), SEAT);
        assertEquals(SkipReason.TIMEOUT, timeout.reason());
    }

    @Test
    @DisplayName("JSON 里的显式 null 与未提供同义，不应抛 NPE")
    void explicitNullIsTreatedAsMissing() {
        Map<String, Object> params = new HashMap<>();
        params.put("targetSeatNo", null);
        GameException error = assertThrows(
                GameException.class, () -> codec.decode(ActionType.SEER_INSPECT, params, SEAT));
        assertEquals(DomainErrorCode.VALIDATION_ERROR, error.code());
    }

    @ParameterizedTest
    @EnumSource(ActionType.class)
    @DisplayName("typeOf：反查结果与 Action 自身声明的类型一致")
    void typeOfMatchesActionType(ActionType type) {
        Action action = sampleOf(type);
        assertEquals(type, codec.typeOf(action));
        assertEquals(type, action.type());
    }

    @Test
    @DisplayName("parseType：未知枚举必须显式失败，不做兜底猜测")
    void parseTypeRejectsUnknownValue() {
        GameException error = assertThrows(GameException.class, () -> codec.parseType("MAGIC"));
        assertEquals(DomainErrorCode.VALIDATION_ERROR, error.code());
        assertEquals("actionType", error.details().get(0).field());

        assertThrows(GameException.class, () -> codec.parseType(" "));
        assertThrows(GameException.class, () -> codec.parseType("vote"));
    }

    @Test
    @DisplayName("parseType：合法取值原样解析，允许两侧空白")
    void parseTypeAcceptsContractValues() {
        assertEquals(ActionType.VOTE, codec.parseType("VOTE"));
        assertEquals(ActionType.WITCH_PASS, codec.parseType(" WITCH_PASS "));
    }

    @ParameterizedTest
    @ValueSource(ints = {0, -1, 10, 99})
    @DisplayName("座位号越界是 VALIDATION_ERROR：SeatNo 构造不出来的值不进入领域")
    void seatOutOfRange(int seatNo) {
        GameException error = assertThrows(
                GameException.class,
                () -> codec.decode(ActionType.SEER_INSPECT, Map.of("targetSeatNo", seatNo), SEAT));
        assertEquals(DomainErrorCode.VALIDATION_ERROR, error.code());
        assertEquals("targetSeatNo", error.details().get(0).field());
    }

    @Test
    @DisplayName("座位号类型错误或缺失是 VALIDATION_ERROR")
    void seatTypeErrors() {
        assertEquals(
                DomainErrorCode.VALIDATION_ERROR,
                assertThrows(
                                GameException.class,
                                () -> codec.decode(ActionType.VOTE, Map.of("targetSeatNo", "6"), SEAT))
                        .code());
        assertEquals(
                DomainErrorCode.VALIDATION_ERROR,
                assertThrows(GameException.class, () -> codec.decode(ActionType.VOTE, Map.of(), SEAT)).code());
        assertEquals(
                DomainErrorCode.VALIDATION_ERROR,
                assertThrows(
                                GameException.class,
                                () -> codec.decode(ActionType.VOTE, Map.of("targetSeatNo", 6.5), SEAT))
                        .code());
    }

    @Test
    @DisplayName("互斥参数同时出现必须被拒绝，不留到领域去猜")
    void mutuallyExclusiveParams() {
        assertEquals(
                DomainErrorCode.VALIDATION_ERROR,
                assertThrows(
                                GameException.class,
                                () -> codec.decode(
                                        ActionType.VOTE, Map.of("abstain", true, "targetSeatNo", 6), SEAT))
                        .code());
        assertEquals(
                DomainErrorCode.VALIDATION_ERROR,
                assertThrows(
                                GameException.class,
                                () -> codec.decode(
                                        ActionType.WOLF_KILL, Map.of("empty", true, "targetSeatNo", 6), SEAT))
                        .code());
        assertEquals(
                DomainErrorCode.VALIDATION_ERROR,
                assertThrows(
                                GameException.class,
                                () -> codec.decode(ActionType.WITCH_PASS, Map.of("targetSeatNo", 6), SEAT))
                        .code());
        assertEquals(
                DomainErrorCode.VALIDATION_ERROR,
                assertThrows(
                                GameException.class,
                                () -> codec.decode(
                                        ActionType.HUNTER_SHOOT, Map.of("giveUp", true, "targetSeatNo", 6), SEAT))
                        .code());
    }

    @Test
    @DisplayName("布尔与字符串参数的形态错误是 VALIDATION_ERROR")
    void flagAndTextTypeErrors() {
        assertEquals(
                DomainErrorCode.VALIDATION_ERROR,
                assertThrows(
                                GameException.class,
                                () -> codec.decode(ActionType.VOTE, Map.of("abstain", "true"), SEAT))
                        .code());
        assertEquals(
                DomainErrorCode.VALIDATION_ERROR,
                assertThrows(
                                GameException.class,
                                () -> codec.decode(ActionType.SPEAK, Map.of("content", "  "), SEAT))
                        .code());
        assertEquals(
                DomainErrorCode.VALIDATION_ERROR,
                assertThrows(
                                GameException.class,
                                () -> codec.decode(ActionType.SKIP, Map.of("reason", "LATER"), SEAT))
                        .code());
        assertEquals(
                DomainErrorCode.VALIDATION_ERROR,
                assertThrows(
                                GameException.class, () -> codec.decode(ActionType.SPEAK, Map.of("content", 7), SEAT))
                        .code());
    }

    @Test
    @DisplayName("缺少提交座位无法构造行动")
    void missingActorSeat() {
        GameException error = assertThrows(
                GameException.class, () -> codec.decode(ActionType.SKIP, Map.of(), null));
        assertEquals(DomainErrorCode.VALIDATION_ERROR, error.code());
    }

    @Test
    @DisplayName("未知参数被忽略：契约允许后续新增可选参数而不破坏旧客户端")
    void unknownParamsAreIgnored() {
        Action action = codec.decode(ActionType.VOTE, Map.of("targetSeatNo", 6, "note", "hi"), SEAT);
        assertEquals(ActionType.VOTE, action.type());
    }

    private static Action sampleOf(ActionType type) {
        return switch (type) {
            case SPEAK -> SpeechAction.of(1, "hi");
            case VOTE -> VoteAction.to(1, 2);
            case WOLF_KILL -> WolfKillAction.at(1, 3);
            case SEER_INSPECT -> SeerInspectAction.of(1, 4);
            case WITCH_SAVE -> WitchAction.save(1, 5);
            case WITCH_POISON -> WitchAction.poison(1, 6);
            case WITCH_PASS -> WitchAction.pass(1);
            case HUNTER_SHOOT -> HunterShotAction.at(1, 7);
            case SKIP -> SkipAction.voluntary(1);
            case WOLF_CHAT -> WolfChatAction.of(1, "刀 3 号");
        };
    }
}
