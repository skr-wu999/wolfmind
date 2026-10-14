package com.wolfmind.infrastructure.json;

import java.util.Map;

/**
 * JSON 编解码门面：{@link JsonWriter} 负责写，{@link JsonReader} 负责读。
 *
 * <p>放在 {@code infrastructure.json} 而不是接口层：它的使用者是快照编解码
 * （{@code GameSnapshotCodec}）与归档仓储（{@code SqliteArchiveRepository}），
 * 两者都在基础设施层。早先它位于 {@code interfaces.json}，导致基础设施反向依赖接口层——
 * 一个层次倒置（DEP-1 只约束 domain 不依赖外层，所以这条没被机器规则抓住）。
 *
 * <p>读写都会把 record 当作"字段名即契约"的载体：这是本项目让领域投影直接作为响应体与
 * 快照状态的前提，不需要为每个投影手写一份序列化代码。
 */
public final class Json {

    private Json() {}

    /** 序列化为紧凑 JSON；{@code record} 按声明组件顺序展开。 */
    public static String write(Object value) {
        return JsonWriter.write(value);
    }

    /** 解析任意 JSON 根值：对象→{@code LinkedHashMap}（保序）、数组→{@code List}、
     *  整数→{@code Long}、小数→{@code Double}、{@code null} 保留。 */
    public static Object read(String text) {
        return JsonReader.parse(text);
    }

    /** 解析 JSON 对象根；根不是对象时抛出 {@link JsonException}（错误信息会指出实际类型）。 */
    public static Map<String, Object> readObject(String text) {
        Object value = read(text);
        if (value instanceof Map<?, ?> map) {
            @SuppressWarnings("unchecked")
            Map<String, Object> typed = (Map<String, Object>) map;
            return typed;
        }
        throw new JsonException("期望 JSON 对象，实际为 " + typeName(value));
    }

    /**
     * 把 JSON 解析出的整数收窄回 {@code Integer}。
     *
     * <p>JSON 没有整数宽度，读取器统一给 {@code Long}，而事件 {@code payload()} 在领域侧用
     * {@code Integer}（座位号、轮次、票数……）。不收窄的话，从外部存储读回的 payload 与原
     * payload 不再 {@code equals}，重放与对账都会误报差异。只收窄落在 {@code int} 范围内的值；
     * 超出的保持 {@code Long}，不静默截断。
     */
    public static Object narrowIntegers(Object value) {
        if (value instanceof Long number) {
            long asLong = number;
            if (asLong >= Integer.MIN_VALUE && asLong <= Integer.MAX_VALUE) {
                return (int) asLong;
            }
            return asLong;
        }
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> normalized = new java.util.LinkedHashMap<>();
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                normalized.put((String) entry.getKey(), narrowIntegers(entry.getValue()));
            }
            return normalized;
        }
        if (value instanceof java.util.List<?> list) {
            java.util.List<Object> normalized = new java.util.ArrayList<>(list.size());
            for (Object item : list) {
                normalized.add(narrowIntegers(item));
            }
            return normalized;
        }
        return value;
    }

    /** JSON 形态名；用于错误信息，让"期望 X、实际 Y"可读。 */
    public static String typeName(Object value) {
        if (value == null) {
            return "null";
        }
        if (value instanceof Map) {
            return "object";
        }
        if (value instanceof Iterable) {
            return "array";
        }
        if (value instanceof String) {
            return "string";
        }
        if (value instanceof Boolean) {
            return "boolean";
        }
        if (value instanceof Number) {
            return "number";
        }
        return value.getClass().getSimpleName();
    }
}
