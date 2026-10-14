package com.wolfmind.domain.game.event;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 事件载荷的构造与校验。
 *
 * <p>载荷里允许出现 {@code null} 值：{@code WOLF_KILL_SELECTED.targetId}、缺省时的
 * {@code PLAYER_VOTED.targetId} 等字段按契约就是"可空且有语义"（event-design.md §7.2 明确
 * 空刀也产生事件）。因此**不能**用 {@code Map.copyOf} 做收尾——它会拒绝空值，让空刀这种
 * 合法事实无法写入事件流。
 *
 * <p>这里统一做两件事：维持插入顺序（键顺序稳定，便于比对与归档），以及拒绝空键
 * （空键一定是编码 bug，而不是业务事实）。
 */
public final class EventPayloads {

    private EventPayloads() {}

    /** 新建一个保持插入顺序的载荷容器。 */
    public static Map<String, Object> create() {
        return new LinkedHashMap<>();
    }

    /** 收尾校验：键非空、顺序稳定、允许 null 值。 */
    public static Map<String, Object> seal(Map<String, Object> payload) {
        for (Map.Entry<String, Object> entry : payload.entrySet()) {
            if (entry.getKey() == null) {
                throw new IllegalArgumentException("事件载荷不允许空键");
            }
        }
        return java.util.Collections.unmodifiableMap(new LinkedHashMap<>(payload));
    }

    /** 便于嵌套对象（如 seats[]、tally[]、deaths[] 的元素）。 */
    public static Map<String, Object> entry(Object... keyValuePairs) {
        if (keyValuePairs.length % 2 != 0) {
            throw new IllegalArgumentException("entry 需要成对的键值");
        }
        Map<String, Object> map = new LinkedHashMap<>();
        for (int index = 0; index < keyValuePairs.length; index += 2) {
            map.put(String.valueOf(keyValuePairs[index]), keyValuePairs[index + 1]);
        }
        return seal(map);
    }
}
