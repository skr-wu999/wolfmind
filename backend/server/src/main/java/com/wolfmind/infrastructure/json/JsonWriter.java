package com.wolfmind.infrastructure.json;

import java.lang.reflect.RecordComponent;
import java.util.Map;

/**
 * 无第三方依赖的 JSON 写出器。
 *
 * <p>支持的形态：{@code null}、{@link String}、{@link Boolean}、{@link Number}、
 * {@link Character}、{@link Enum}（写名称）、{@link Map}（键必须是字符串）、
 * {@link Iterable}，以及 **record**。
 *
 * <p><b>record 按声明的组件顺序展开</b>，且 {@code null} 组件写成显式 {@code null} 而不是省略键。
 * 这两点都是契约：接口层的响应体就是领域投影（record），字段名与顺序即线上契约，
 * "键存在但值为 null"与"键不存在"是不同的事实（api-spec.md §1 的字段约定）。
 *
 * <p>浮点的 {@code NaN}/{@code Infinity} 写成 JSON 字符串而不是裸字面量：
 * 裸写会产生**非法 JSON**，让标准解析器直接失败。
 */
final class JsonWriter {

    private JsonWriter() {}

    static String write(Object value) {
        StringBuilder builder = new StringBuilder();
        append(builder, value);
        return builder.toString();
    }

    private static void append(StringBuilder builder, Object value) {
        if (value == null) {
            builder.append("null");
            return;
        }
        if (value instanceof String text) {
            appendString(builder, text);
            return;
        }
        if (value instanceof Boolean flag) {
            builder.append(flag.booleanValue());
            return;
        }
        if (value instanceof Double || value instanceof Float) {
            appendFloating(builder, ((Number) value).doubleValue());
            return;
        }
        if (value instanceof Number number) {
            // Long/Integer/BigInteger 等直接按十进制输出；不经 double 以免大整数丢精度。
            builder.append(number);
            return;
        }
        if (value instanceof Character character) {
            appendString(builder, String.valueOf(character));
            return;
        }
        if (value instanceof Enum<?> enumValue) {
            appendString(builder, enumValue.name());
            return;
        }
        if (value instanceof Map<?, ?> map) {
            appendObject(builder, map);
            return;
        }
        if (value instanceof Iterable<?> iterable) {
            appendArray(builder, iterable);
            return;
        }
        Class<?> type = value.getClass();
        if (type.isRecord()) {
            appendRecord(builder, value);
            return;
        }
        // 不静默退化成 toString()：那会把"忘了支持的形态"写成看起来合法的字符串，
        // 读回来才发现类型错了，而那时数据已经落库。
        throw new JsonException("无法序列化的类型：" + type.getName());
    }

    private static void appendRecord(StringBuilder builder, Object record) {
        RecordComponent[] components = record.getClass().getRecordComponents();
        builder.append('{');
        for (int index = 0; index < components.length; index++) {
            if (index > 0) {
                builder.append(',');
            }
            appendString(builder, components[index].getName());
            builder.append(':');
            append(builder, readComponent(record, components[index]));
        }
        builder.append('}');
    }

    private static Object readComponent(Object record, RecordComponent component) {
        try {
            return component.getAccessor().invoke(record);
        } catch (ReflectiveOperationException error) {
            throw new JsonException(
                    "读取 record 组件失败："
                            + record.getClass().getSimpleName() + "." + component.getName()
                            + "（" + error.getClass().getSimpleName() + "）");
        }
    }

    private static void appendObject(StringBuilder builder, Map<?, ?> map) {
        builder.append('{');
        boolean first = true;
        for (Map.Entry<?, ?> entry : map.entrySet()) {
            if (!(entry.getKey() instanceof String key)) {
                throw new JsonException("JSON 对象的键必须是字符串，实际为 "
                        + (entry.getKey() == null ? "null" : entry.getKey().getClass().getName()));
            }
            if (!first) {
                builder.append(',');
            }
            first = false;
            appendString(builder, key);
            builder.append(':');
            append(builder, entry.getValue());
        }
        builder.append('}');
    }

    private static void appendArray(StringBuilder builder, Iterable<?> iterable) {
        builder.append('[');
        boolean first = true;
        for (Object item : iterable) {
            if (!first) {
                builder.append(',');
            }
            first = false;
            append(builder, item);
        }
        builder.append(']');
    }

    private static void appendFloating(StringBuilder builder, double value) {
        if (!Double.isFinite(value)) {
            // NaN / ±Infinity 在 JSON 里没有字面量；写字符串至少保证输出仍是合法 JSON。
            appendString(builder, Double.toString(value));
            return;
        }
        builder.append(Double.toString(value));
    }

    static void appendString(StringBuilder builder, String value) {
        builder.append('"');
        for (int index = 0; index < value.length(); index++) {
            char current = value.charAt(index);
            switch (current) {
                case '"' -> builder.append("\\\"");
                case '\\' -> builder.append("\\\\");
                case '\n' -> builder.append("\\n");
                case '\r' -> builder.append("\\r");
                case '\t' -> builder.append("\\t");
                case '\b' -> builder.append("\\b");
                case '\f' -> builder.append("\\f");
                default -> {
                    if (current < 0x20) {
                        // 其余控制字符用 u 转义，保证输出是单行且合法。
                        builder.append(String.format("\\u%04X", (int) current));
                    } else {
                        // 非 ASCII（含中文、emoji）按原字符输出，不做 u 转义：
                        // 传输与存储都是 UTF-8，转义只会让日志与抓包更难读。
                        builder.append(current);
                    }
                }
            }
        }
        builder.append('"');
    }
}
