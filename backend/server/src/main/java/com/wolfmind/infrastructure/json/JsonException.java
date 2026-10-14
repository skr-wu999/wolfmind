package com.wolfmind.infrastructure.json;

/**
 * JSON 读写失败。
 *
 * <p>它是**运行时异常**而不是受检异常：调用方几乎都是"已经确认输入是 JSON"的场景
 * （快照、归档、接口请求体），把异常塞进每个方法签名只会让调用链噪声更大。
 * 真正需要区分"输入损坏"的地方（归档对账、恢复流程）会显式捕获它。
 */
public class JsonException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final transient JsonReader.Location location;

    public JsonException(String message) {
        this(message, null);
    }

    public JsonException(String message, JsonReader.Location location) {
        super(location == null ? message : message + "（位置 " + location.offset() + "）");
        this.location = location;
    }

    /** 出错位置；仅当解析器能定位时非 null。 */
    public JsonReader.Location location() {
        return location;
    }
}
