package com.wolfmind.application.agent.strategy;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 极简 JSON 对象解析：只服务于"解析模型输出"这一件事（strategy-layer.md §7.1、§8.1）。
 *
 * <p>为什么不复用 {@code interfaces/json} 的 {@code Json}：应用层依赖接口层会把
 * AGENTS.md §5 的依赖方向倒过来（interface → application 而不是反向）。为了一个
 * "读一个 JSON 对象"的需求引入反向依赖，代价远大于这 150 行代码。
 *
 * <p>为什么不用 Jackson：server 模块的第三方依赖清单是冻结的（DEP-5），而模型输出的契约
 * 只有六个字段（{@code actionType/targetId/speech/confidence/reasonCode/notes}）。
 *
 * <p>容错边界：只容忍"外面包了 ``` 或解释性文字"这种常见包装，不容忍语法错误——
 * 真正的坏 JSON 必须走到 {@code repair}（§8.1 第一行）。
 */
public final class JsonLite {

    private JsonLite() {}

    /**
     * 解析根为对象的 JSON 文本；失败返回 {@code null}（调用方转成 {@code BAD_JSON}）。
     *
     * <p>实现上先截取第一个平衡的 {@code {...}} 片段：模型经常把 JSON 放在 markdown 代码块
     * 或一句解释之后，这属于表达包装而不是结构错误，为此触发一次 repair 是浪费预算。
     */
    public static Map<String, Object> tryReadObject(String raw) {
        if (raw == null) {
            return null;
        }
        String candidate = extractObject(raw);
        if (candidate == null) {
            return null;
        }
        try {
            Parser parser = new Parser(candidate);
            Object value = parser.parseValue();
            parser.skipWhitespace();
            if (parser.hasRemaining()) {
                return null;
            }
            if (!(value instanceof Map<?, ?> map)) {
                return null;
            }
            Map<String, Object> result = new LinkedHashMap<>();
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                result.put(String.valueOf(entry.getKey()), entry.getValue());
            }
            return result;
        } catch (RuntimeException ex) {
            return null;
        }
    }

    /** 取第一个平衡的对象片段；字符串字面量里的花括号不参与配对。 */
    static String extractObject(String raw) {
        int start = raw.indexOf('{');
        if (start < 0) {
            return null;
        }
        int depth = 0;
        boolean inString = false;
        boolean escaped = false;
        for (int i = start; i < raw.length(); i++) {
            char ch = raw.charAt(i);
            if (inString) {
                if (escaped) {
                    escaped = false;
                } else if (ch == '\\') {
                    escaped = true;
                } else if (ch == '"') {
                    inString = false;
                }
                continue;
            }
            if (ch == '"') {
                inString = true;
            } else if (ch == '{') {
                depth++;
            } else if (ch == '}') {
                depth--;
                if (depth == 0) {
                    return raw.substring(start, i + 1);
                }
            }
        }
        return null;
    }

    /** 读取字符串字段；非字符串返回 {@code null}（调用方决定是默认值还是失败）。 */
    public static String string(Map<String, Object> object, String key) {
        Object value = object.get(key);
        if (value == null) {
            return null;
        }
        if (value instanceof String text) {
            return text;
        }
        if (value instanceof Number || value instanceof Boolean) {
            // 模型有时把座位号写成数字：把它当字符串读取，比直接判非法更接近其意图
            return String.valueOf(value);
        }
        return null;
    }

    /** 读取数值字段；缺失或非数值返回 {@code null}。 */
    public static Double number(Map<String, Object> object, String key) {
        Object value = object.get(key);
        if (value instanceof Number number) {
            double result = number.doubleValue();
            return Double.isFinite(result) ? result : null;
        }
        if (value instanceof String text) {
            try {
                double result = Double.parseDouble(text.strip());
                return Double.isFinite(result) ? result : null;
            } catch (NumberFormatException ex) {
                return null;
            }
        }
        return null;
    }

    /** 递归下降解析器：只实现 JSON 的标准产生式，不做任何"猜"的容错。 */
    private static final class Parser {

        private final String text;
        private int index;

        Parser(String text) {
            this.text = text;
        }

        boolean hasRemaining() {
            return index < text.length();
        }

        void skipWhitespace() {
            while (index < text.length() && Character.isWhitespace(text.charAt(index))) {
                index++;
            }
        }

        Object parseValue() {
            skipWhitespace();
            if (!hasRemaining()) {
                throw new IllegalArgumentException("JSON 意外结束");
            }
            char ch = text.charAt(index);
            return switch (ch) {
                case '{' -> parseObject();
                case '[' -> parseArray();
                case '"' -> parseString();
                case 't' -> parseLiteral("true", Boolean.TRUE);
                case 'f' -> parseLiteral("false", Boolean.FALSE);
                case 'n' -> parseLiteral("null", null);
                default -> parseNumber();
            };
        }

        private Map<String, Object> parseObject() {
            expect('{');
            Map<String, Object> result = new LinkedHashMap<>();
            skipWhitespace();
            if (peek() == '}') {
                index++;
                return result;
            }
            while (true) {
                skipWhitespace();
                String key = parseString();
                skipWhitespace();
                expect(':');
                result.put(key, parseValue());
                skipWhitespace();
                char ch = next();
                if (ch == '}') {
                    return result;
                }
                if (ch != ',') {
                    throw new IllegalArgumentException("对象缺少分隔符");
                }
            }
        }

        private List<Object> parseArray() {
            expect('[');
            List<Object> result = new ArrayList<>();
            skipWhitespace();
            if (peek() == ']') {
                index++;
                return result;
            }
            while (true) {
                result.add(parseValue());
                skipWhitespace();
                char ch = next();
                if (ch == ']') {
                    return result;
                }
                if (ch != ',') {
                    throw new IllegalArgumentException("数组缺少分隔符");
                }
            }
        }

        private String parseString() {
            expect('"');
            StringBuilder builder = new StringBuilder();
            while (true) {
                char ch = next();
                if (ch == '"') {
                    return builder.toString();
                }
                if (ch != '\\') {
                    builder.append(ch);
                    continue;
                }
                char escaped = next();
                switch (escaped) {
                    case '"' -> builder.append('"');
                    case '\\' -> builder.append('\\');
                    case '/' -> builder.append('/');
                    case 'b' -> builder.append('\b');
                    case 'f' -> builder.append('\f');
                    case 'n' -> builder.append('\n');
                    case 'r' -> builder.append('\r');
                    case 't' -> builder.append('\t');
                    case 'u' -> {
                        if (index + 4 > text.length()) {
                            throw new IllegalArgumentException("unicode 转义不完整");
                        }
                        String hex = text.substring(index, index + 4);
                        index += 4;
                        builder.append((char) Integer.parseInt(hex, 16));
                    }
                    default -> throw new IllegalArgumentException("未知转义 \\" + escaped);
                }
            }
        }

        private Object parseNumber() {
            int start = index;
            while (index < text.length() && "+-0123456789.eE".indexOf(text.charAt(index)) >= 0) {
                index++;
            }
            String token = text.substring(start, index);
            if (token.isEmpty()) {
                throw new IllegalArgumentException("非法数值");
            }
            try {
                return Double.valueOf(token);
            } catch (NumberFormatException ex) {
                throw new IllegalArgumentException("非法数值 " + token);
            }
        }

        private Object parseLiteral(String literal, Object value) {
            if (!text.startsWith(literal, index)) {
                throw new IllegalArgumentException("非法字面量");
            }
            index += literal.length();
            return value;
        }

        private char peek() {
            if (!hasRemaining()) {
                throw new IllegalArgumentException("JSON 意外结束");
            }
            return text.charAt(index);
        }

        private char next() {
            char ch = peek();
            index++;
            return ch;
        }

        private void expect(char expected) {
            char ch = next();
            if (ch != expected) {
                throw new IllegalArgumentException("期望 " + expected + "，实际 " + ch);
            }
        }
    }
}
