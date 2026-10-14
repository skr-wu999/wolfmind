package com.wolfmind.infrastructure.json;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 无第三方依赖的 JSON 读取器（RFC 8259 的严格子集）。
 *
 * <p>为什么自己写而不是引入 Jackson/Gson：
 * <ul>
 *   <li>按 DEP-5，第三方 SDK 只允许出现在 {@code infrastructure}；读取器被快照与归档复用，
 *       引入 Jackson 会把它的类型（{@code JsonNode}、{@code ObjectMapper}）扩散到序列化契约里；</li>
 *   <li>本项目只需要"对象/数组/字符串/数字/布尔/null"六种形态，且**契约要求字段顺序与键的存在性
 *       完全可控**（显式 {@code null} 必须保留键），通用库的默认行为反而要额外配置去迎合。</li>
 * </ul>
 *
 * <p><b>数字语义</b>：JSON 没有整数宽度，因此整数字面量统一解析为 {@link Long}、小数统一解析为
 * {@link Double}。这个选择是**契约的一部分**：归档读回 payload 时需要按同一规则比较
 * （见 {@link Json#narrowIntegers}）。
 *
 * <p>严格性：拒绝尾随内容、重复键、前导零、孤立小数点、裸控制字符与非法转义。
 * 宽松解析会把"客户端发错了"变成"服务端存了个奇怪的值"，那类缺陷很难在事后定位。
 */
final class JsonReader {

    /** 嵌套深度上限：挡住构造出来的深层嵌套（栈溢出不是可恢复错误）。 */
    private static final int MAX_DEPTH = 64;

    /** 出错位置：只记偏移量，够定位且不必维护行列计数。 */
    record Location(int offset) {}

    private final String text;
    private int pos;

    private JsonReader(String text) {
        this.text = text;
    }

    static Object parse(String text) {
        if (text == null || text.isEmpty()) {
            throw new JsonException("JSON 文本为空");
        }
        JsonReader reader = new JsonReader(text);
        reader.skipWhitespace();
        Object value = reader.readValue(0);
        reader.skipWhitespace();
        if (reader.pos < reader.text.length()) {
            throw reader.error("根值之后还有多余内容");
        }
        return value;
    }

    private Object readValue(int depth) {
        if (depth > MAX_DEPTH) {
            throw error("嵌套层级超过上限 " + MAX_DEPTH);
        }
        char current = peek();
        return switch (current) {
            case '{' -> readObject(depth + 1);
            case '[' -> readArray(depth + 1);
            case '"' -> readString();
            case 't' -> readLiteral("true", Boolean.TRUE);
            case 'f' -> readLiteral("false", Boolean.FALSE);
            case 'n' -> readLiteral("null", null);
            default -> readNumber();
        };
    }

    private Map<String, Object> readObject(int depth) {
        expect('{');
        Map<String, Object> result = new LinkedHashMap<>();
        skipWhitespace();
        if (peek() == '}') {
            next();
            return result;
        }
        while (true) {
            skipWhitespace();
            if (peek() != '"') {
                throw error("对象的键必须是字符串");
            }
            String key = readString();
            if (result.containsKey(key)) {
                // 重复键必须拒绝：后值覆盖前值会让"同一份 JSON 解析出不同结果"成为可能。
                throw error("对象出现重复键：" + key);
            }
            skipWhitespace();
            expect(':');
            skipWhitespace();
            result.put(key, readValue(depth));
            skipWhitespace();
            char separator = next();
            if (separator == '}') {
                return result;
            }
            if (separator != ',') {
                throw error("对象成员之间必须是 ',' 或结束的 '}'");
            }
        }
    }

    private List<Object> readArray(int depth) {
        expect('[');
        List<Object> result = new ArrayList<>();
        skipWhitespace();
        if (peek() == ']') {
            next();
            return result;
        }
        while (true) {
            skipWhitespace();
            result.add(readValue(depth));
            skipWhitespace();
            char separator = next();
            if (separator == ']') {
                return result;
            }
            if (separator != ',') {
                throw error("数组元素之间必须是 ',' 或结束的 ']'");
            }
        }
    }

    private String readString() {
        expect('"');
        StringBuilder builder = new StringBuilder();
        while (true) {
            char current = next();
            if (current == '"') {
                return builder.toString();
            }
            if (current == '\\') {
                readEscape(builder);
                continue;
            }
            // 裸控制字符（U+0000..U+001F）在 JSON 字符串里非法。放行会让归档里出现
            // 无法用标准解析器读回的 payload。
            if (current < 0x20) {
                throw error("字符串里出现未转义的控制字符 U+" + String.format("%04X", (int) current));
            }
            builder.append(current);
        }
    }

    private void readEscape(StringBuilder builder) {
        char escape = next();
        switch (escape) {
            case '"' -> builder.append('"');
            case '\\' -> builder.append('\\');
            case '/' -> builder.append('/');
            case 'b' -> builder.append('\b');
            case 'f' -> builder.append('\f');
            case 'n' -> builder.append('\n');
            case 'r' -> builder.append('\r');
            case 't' -> builder.append('\t');
            case 'u' -> appendUnicodeEscape(builder);
            default -> throw error("非法转义：\\" + describe(escape));
        }
    }

    private void appendUnicodeEscape(StringBuilder builder) {
        // 直接 append char：代理对由两个 u 转义各自贡献一个 char，拼接后即为完整码点，
        // 不需要在这里配对（String 内部就是 UTF-16）。
        builder.append((char) readHex4());
    }

    private int readHex4() {
        int value = 0;
        for (int index = 0; index < 4; index++) {
            char digit = next();
            int nibble = Character.digit(digit, 16);
            if (nibble < 0) {
                throw error("\\u 转义需要 4 位十六进制，遇到 " + describe(digit));
            }
            value = (value << 4) | nibble;
        }
        return value;
    }

    private Object readNumber() {
        int start = pos;
        if (peek() == '-') {
            next();
        }
        readIntegerPart();
        boolean floating = false;
        if (pos < text.length() && peek() == '.') {
            floating = true;
            next();
            // 小数点后必须至少有一位：拒绝 "1."
            if (pos >= text.length() || !Character.isDigit(peek())) {
                throw error("小数点后必须有数字");
            }
            readDigits("");
        }
        if (pos < text.length() && (peek() == 'e' || peek() == 'E')) {
            floating = true;
            next();
            if (pos < text.length() && (peek() == '+' || peek() == '-')) {
                next();
            }
            if (pos >= text.length() || !Character.isDigit(peek())) {
                throw error("指数部分必须有数字");
            }
            readDigits("");
        }
        String literal = text.substring(start, pos);
        if (literal.isEmpty() || literal.equals("-")) {
            throw error("不是合法的数字");
        }
        try {
            return floating ? (Object) Double.valueOf(literal) : (Object) Long.valueOf(literal);
        } catch (NumberFormatException error) {
            throw error("数字超出可表示范围：" + literal);
        }
    }

    private void readIntegerPart() {
        if (pos >= text.length() || !Character.isDigit(peek())) {
            throw error("期望一个 JSON 值");
        }
        // 前导零非法（"01"），但单独的 "0" 合法。
        if (peek() == '0') {
            next();
            if (pos < text.length() && Character.isDigit(peek())) {
                throw error("数字不能有前导零");
            }
            return;
        }
        readDigits("");
    }

    private void readDigits(String ignored) {
        while (pos < text.length() && Character.isDigit(peek())) {
            next();
        }
    }

    private Object readLiteral(String literal, Object value) {
        if (!text.startsWith(literal, pos)) {
            throw error("期望字面量 " + literal);
        }
        pos += literal.length();
        return value;
    }

    private char peek() {
        if (pos >= text.length()) {
            throw error("JSON 文本意外结束");
        }
        return text.charAt(pos);
    }

    private char next() {
        char current = peek();
        pos++;
        return current;
    }

    private void expect(char expected) {
        char actual = next();
        if (actual != expected) {
            throw error("期望 '" + expected + "'，实际为 " + describe(actual));
        }
    }

    private void skipWhitespace() {
        while (pos < text.length()) {
            char current = text.charAt(pos);
            if (current == ' ' || current == '\t' || current == '\n' || current == '\r') {
                pos++;
            } else {
                return;
            }
        }
    }

    private JsonException error(String message) {
        return new JsonException(message, new Location(pos));
    }

    private static String describe(char value) {
        if (value < 0x20) {
            return String.format("U+%04X", (int) value);
        }
        return "'" + value + "'";
    }
}
