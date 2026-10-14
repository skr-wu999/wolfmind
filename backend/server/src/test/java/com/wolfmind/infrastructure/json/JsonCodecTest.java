package com.wolfmind.infrastructure.json;

import com.wolfmind.infrastructure.json.Json;
import com.wolfmind.infrastructure.json.JsonException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** JSON 编解码往返与转义规则。 */
class JsonCodecTest {

    @Test
    @DisplayName("嵌套结构往返：object / array / string / number / boolean / null")
    void roundTripsNestedStructure() {
        Map<String, Object> source = new LinkedHashMap<>();
        source.put("eventId", "01H8X2-000123");
        source.put("version", 123);
        source.put("ratio", 0.5);
        source.put("accepted", true);
        source.put("targetSeatNo", null);
        source.put("tags", new ArrayList<>(List.of("wolf", "vote")));

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("playerId", "p_3");
        payload.put("seatNo", 4);
        source.put("payload", payload);

        String encoded = Json.write(source);
        @SuppressWarnings("unchecked")
        Map<String, Object> decoded = (Map<String, Object>) Json.read(encoded);

        // 逐字段比较，而不是整张 map 的 equals：JSON 没有整数宽度，读取器统一给 Long，
        // 而 Java 侧写字面量 123 是 Integer。这是 JSON 的固有性质，不是缺陷；
        // 真正要断言的是"键、结构、数值、文本都一致"。
        assertEquals(source.keySet(), decoded.keySet(), "键集合与顺序必须一致");
        assertEquals("01H8X2-000123", decoded.get("eventId"));
        assertEquals(123L, decoded.get("version"));
        assertEquals(0.5d, decoded.get("ratio"));
        assertEquals(true, decoded.get("accepted"));
        assertTrue(decoded.containsKey("targetSeatNo"), "显式 null 必须保留键");
        assertNull(decoded.get("targetSeatNo"));
        assertEquals(List.of("wolf", "vote"), decoded.get("tags"));
        assertEquals(Map.of("playerId", "p_3", "seatNo", 4L), decoded.get("payload"));
    }

    @Test
    @DisplayName("中文、引号、反斜杠、换行与 emoji 往返不变")
    void roundTripsEscapedAndNonAsciiText() {
        String text = "狼人说：\"我是村民\"，\n换行\t制表\\反斜杠，emoji \uD83D\uDC3A，控制符 \u0001";

        String encoded = Json.write(text);
        Object decoded = Json.read(encoded);

        assertEquals(text, decoded);
        assertTrue(encoded.contains("狼人"), "中文应按原字符输出，不转成 \\u 转义");
        assertTrue(encoded.contains("\\n"), "换行必须转义，保证单行 JSON");
        assertTrue(encoded.contains("\\\""), "引号必须转义");
    }

    @Test
    @DisplayName("读取 \\uXXXX 转义，含非 BMP 代理对")
    void readsUnicodeEscapes() {
        assertEquals("狼", Json.read("\"\\u72fc\""));
        assertEquals("a\"b", Json.read("\"a\\u0022b\""));
        assertEquals("\uD83D\uDC3A", Json.read("\"\\uD83D\\uDC3A\""));
    }

    @Test
    @DisplayName("整数字面量解析为 Long，小数解析为 Double")
    void parsesNumbers() {
        assertInstanceOf(Long.class, Json.read("42"));
        assertEquals(42L, Json.read("42"));
        assertEquals(-7L, Json.read("-7"));
        assertEquals(0L, Json.read("0"));
        assertEquals(1.5d, Json.read("1.5"));
        assertEquals(100.0d, Json.read("1e2"));
        assertEquals(0.01d, Json.read("1E-2"));
    }

    @Test
    @DisplayName("空对象与空数组往返")
    void roundTripsEmptyContainers() {
        assertEquals(Map.of(), Json.read("{}"));
        assertEquals(List.of(), Json.read("[]"));
        assertEquals("{}", Json.write(new LinkedHashMap<>()));
        assertEquals("[]", Json.write(new ArrayList<>()));
    }

    @Test
    @DisplayName("根节点非对象时 readObject 明确失败")
    void readObjectRejectsNonObjectRoot() {
        JsonException error = assertThrows(JsonException.class, () -> Json.readObject("[1,2]"));
        assertTrue(error.getMessage().contains("array"), "错误信息应指出实际类型");
        assertThrows(JsonException.class, () -> Json.readObject("\"text\""));
    }

    @Test
    @DisplayName("语法错误、尾随内容与重复键都被拒绝")
    void rejectsMalformedInput() {
        assertThrows(JsonException.class, () -> Json.read(""));
        assertThrows(JsonException.class, () -> Json.read("{"));
        assertThrows(JsonException.class, () -> Json.read("{\"a\":1,}"));
        assertThrows(JsonException.class, () -> Json.read("{\"a\":1} extra"));
        assertThrows(JsonException.class, () -> Json.read("{\"a\":1,\"a\":2}"), "重复键必须拒绝");
        assertThrows(JsonException.class, () -> Json.read("nul"));
        assertThrows(JsonException.class, () -> Json.read("01"));
        assertThrows(JsonException.class, () -> Json.read("1."));
        assertThrows(JsonException.class, () -> Json.read("\"\\x\""), "非法转义必须拒绝");
        assertThrows(JsonException.class, () -> Json.read("\"" + "a\n" + "\""), "裸控制字符必须拒绝");
    }

    @Test
    @DisplayName("无法序列化的值抛出 JsonException，而不是静默写错类型")
    void rejectsUnsupportedValueType() {
        assertThrows(JsonException.class, () -> Json.write(new Object()));
        Map<Object, Object> badKey = new LinkedHashMap<>();
        badKey.put(1, "x");
        assertThrows(JsonException.class, () -> Json.write(badKey));
    }

    @Test
    @DisplayName("record 按其声明的组件顺序序列化，null 组件写成显式 null")
    void writesRecordsByDeclaredComponents() {
        // 接口层的响应体直接是领域投影（record），字段名与顺序就是契约本身。
        assertEquals("{\"name\":\"狼人\",\"count\":3,\"target\":null}",
                Json.write(new SampleView("狼人", 3, null)));
        assertEquals("{\"name\":null,\"count\":0,\"target\":null}",
                Json.write(new SampleView(null, 0, null)));

        // 嵌套 record 与列表同样按组件展开
        assertEquals("{\"name\":\"a\",\"count\":1,\"target\":{\"name\":\"b\",\"count\":2,\"target\":null}}",
                Json.write(new SampleView("a", 1, new SampleView("b", 2, null))));
        assertEquals("[{\"name\":\"a\",\"count\":1,\"target\":null}]",
                Json.write(List.of(new SampleView("a", 1, null))));

        // record 的枚举与 map 组件分别写成名称字符串与对象
        assertEquals("{\"name\":\"WOLF\",\"count\":9,\"target\":null}",
                Json.write(new EnumView(com.wolfmind.domain.game.model.Camp.WOLF, 9, null)));
    }

    @Test
    @DisplayName("含中文与转义的 record 组件往返一致")
    void recordComponentsRoundTrip() {
        String encoded = Json.write(new SampleView("他说：\"我是村民\"\n", 7, null));
        Object decoded = Json.read(encoded);
        @SuppressWarnings("unchecked")
        Map<String, Object> map = (Map<String, Object>) decoded;
        assertEquals("他说：\"我是村民\"\n", map.get("name"));
        assertEquals(7L, map.get("count"));
        assertTrue(map.containsKey("target"), "null 组件必须保留键");
    }

    @Test
    @DisplayName("null 与缺失字段是不同的事实：write(null) 产生 JSON null")
    void writesExplicitNull() {
        assertEquals("null", Json.write(null));
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("targetSeatNo", null);
        assertEquals("{\"targetSeatNo\":null}", Json.write(map));
        Object decoded = Json.read("{\"targetSeatNo\":null}");
        assertInstanceOf(Map.class, decoded);
        @SuppressWarnings("unchecked")
        Map<String, Object> asMap = (Map<String, Object>) decoded;
        assertTrue(asMap.containsKey("targetSeatNo"), "显式 null 必须保留键");
        assertNull(asMap.get("targetSeatNo"));
    }

    @Test
    @DisplayName("浮点 NaN / Infinity 不产生非法 JSON")
    void writesNonFiniteAsString() {
        assertFalse(Json.write(Double.NaN).contains("NaN\","), "NaN 必须写成 JSON 字符串");
        assertEquals("\"NaN\"", Json.write(Double.NaN));
        assertEquals("\"Infinity\"", Json.write(Double.POSITIVE_INFINITY));
    }

    @Test
    @DisplayName("UTF-8 字节往返：编码器输出可被标准 UTF-8 重读")
    void survivesUtf8Bytes() throws Exception {
        String text = "{\"content\":\"昨夜是平安夜，没有人死亡。\"}";
        byte[] bytes = text.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        String rebuilt = new String(bytes, java.nio.charset.StandardCharsets.UTF_8);
        Object decoded = Json.read(rebuilt);
        assertInstanceOf(Map.class, decoded);
        assertEquals("昨夜是平安夜，没有人死亡。",
                ((Map<?, ?>) decoded).get("content"));
    }

    /** 模拟领域投影：组件名与声明顺序即线上契约。 */
    private record SampleView(String name, int count, SampleView target) {}

    /** 含枚举组件的投影。 */
    private record EnumView(com.wolfmind.domain.game.model.Camp name, int count, String target) {}
}
