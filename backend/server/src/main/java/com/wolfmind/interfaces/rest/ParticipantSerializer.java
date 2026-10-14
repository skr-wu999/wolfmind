package com.wolfmind.interfaces.rest;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.JsonSerializer;
import com.fasterxml.jackson.databind.SerializerProvider;
import com.wolfmind.domain.game.view.GameViewSnapshot;

import java.io.IOException;
import java.util.Map;

/**
 * 把 {@link GameViewSnapshot.Participant} 的控制器写成嵌套对象。
 *
 * <p>为什么需要它：领域 record 的组件是**扁平**的 {@code controllerType} / {@code controllerRefId}，
 * 而 api-spec.md 5.2 规定该字段是 {@code controller: {type, refId}}（范例 JSON 同样是嵌套）。
 * 按 record 组件直接序列化会产出扁平键，前端按契约取 {@code controller.type} 会拿到 undefined。
 *
 * <p>为什么用序列化器而不是改领域 record：engine 模块禁止引入 Jackson（DEP-2/DEP-5），
 * "线上 JSON 长什么样"属于接口层职责（AP-6）。这条转换规则放在
 * {@code interfaces.rest} 正好落在它该在的层。
 *
 * <p>为什么不是"在控制器里手写一遍 Map"：那样每加一个字段都要两处同步，
 * 迟早漏掉一个；序列化器只重排控制器字段，其余字段仍由 Jackson 从 record 直接读取。
 */
public final class ParticipantSerializer extends JsonSerializer<GameViewSnapshot.Participant> {

    /** api-spec.md 1：枚举一律为大写下划线或大写字面量，与领域枚举逐字一致。 */
    private static final String FIELD_PLAYER_ID = "playerId";
    private static final String FIELD_SEAT_NO = "seatNo";
    private static final String FIELD_ALIVE = "alive";
    private static final String FIELD_READY = "ready";
    private static final String FIELD_CONTROLLER = "controller";
    private static final String FIELD_ROLE = "role";
    private static final String FIELD_CAMP = "camp";
    private static final String FIELD_SKILL_STATE = "skillState";

    @Override
    public void serialize(
            GameViewSnapshot.Participant participant,
            JsonGenerator generator,
            SerializerProvider provider) throws IOException {
        if (participant == null) {
            generator.writeNull();
            return;
        }
        generator.writeStartObject();
        // 字段顺序沿用 api-spec 5.2 的表述顺序，便于人工比对响应与文档。
        writeText(generator, FIELD_PLAYER_ID,
                participant.playerId() == null ? null : participant.playerId().value());
        generator.writeNumberField(FIELD_SEAT_NO, participant.seatNo());
        generator.writeBooleanField(FIELD_ALIVE, participant.alive());
        generator.writeBooleanField(FIELD_READY, participant.ready());

        generator.writeFieldName(FIELD_CONTROLLER);
        if (participant.controllerType() == null) {
            // 居留位为空是合法状态（尚未分配控制者），写成 null 而不是省略键：
            // "键存在但为 null"与"键不存在"是不同的事实。
            generator.writeNull();
        } else {
            generator.writeObject(new ControllerView(
                    participant.controllerType().name(), participant.controllerRefId()));
        }

        writeText(generator, FIELD_ROLE,
                participant.role() == null ? null : participant.role().name());
        writeText(generator, FIELD_CAMP,
                participant.camp() == null ? null : participant.camp().name());
        generator.writeObjectField(FIELD_SKILL_STATE, participant.skillState());
        generator.writeEndObject();
    }

    private static void writeText(JsonGenerator generator, String field, String value) throws IOException {
        if (value == null) {
            generator.writeNullField(field);
        } else {
            generator.writeStringField(field, value);
        }
    }

    /** {@code controller: {type, refId}}（api-spec.md 5.2）。 */
    private record ControllerView(String type, String refId) {}
}