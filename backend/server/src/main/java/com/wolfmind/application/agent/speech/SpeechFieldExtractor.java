package com.wolfmind.application.agent.speech;

/**
 * 从流式到达的结构化 JSON 里增量取出顶层 {@code speech} 字段的文本。
 *
 * <p>模型的流式 token 是 JSON 片段，其中还有动作类型、目标、理由等字段；预览只能包含发言本身，
 * 因此这里只解码顶层对象里 {@code "speech"} 键对应的字符串值，其余内容一律丢弃。
 * 只认第一个顶层 {@code speech}；JSON 之前的围栏或说明文字被忽略。
 *
 * <p>非线程安全：一次模型调用一个实例，回调按到达顺序串行进入。
 */
public final class SpeechFieldExtractor {

    private static final String FIELD = "speech";

    private int depth;
    private boolean inString;
    private boolean escaping;
    private int unicodeDigits = -1;
    private int unicodeValue;

    private boolean expectKey;
    private boolean readingKey;
    private final StringBuilder key = new StringBuilder();
    private String lastKey;
    private boolean valueArmed;

    private boolean inSpeech;
    private boolean speechClosed;

    /** 喂入一段原始输出，返回其中新解码出的发言文本（可能为空串）。 */
    public String feed(String chunk) {
        if (chunk == null || chunk.isEmpty() || speechClosed) {
            return "";
        }
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < chunk.length() && !speechClosed; i++) {
            char c = chunk.charAt(i);
            if (inString) {
                consumeStringChar(c, out);
            } else {
                consumeStructuralChar(c);
            }
        }
        return out.toString();
    }

    /** 发言字符串是否已完整闭合。 */
    public boolean speechClosed() {
        return speechClosed;
    }

    private void consumeStructuralChar(char c) {
        if (depth == 0) {
            if (c == '{') {
                depth = 1;
                expectKey = true;
            }
            return;
        }
        switch (c) {
            case '{', '[' -> {
                depth++;
                valueArmed = false;
            }
            case '}', ']' -> depth--;
            case ',' -> {
                if (depth == 1) {
                    expectKey = true;
                }
            }
            case ':' -> {
                if (depth == 1) {
                    valueArmed = FIELD.equals(lastKey);
                }
            }
            case '"' -> {
                inString = true;
                if (depth == 1 && expectKey) {
                    readingKey = true;
                    expectKey = false;
                    key.setLength(0);
                } else if (depth == 1 && valueArmed) {
                    inSpeech = true;
                    valueArmed = false;
                }
            }
            default -> {
                if (!Character.isWhitespace(c)) {
                    valueArmed = false;
                }
            }
        }
    }

    private void consumeStringChar(char c, StringBuilder out) {
        if (unicodeDigits >= 0) {
            int digit = Character.digit(c, 16);
            unicodeValue = unicodeValue * 16 + Math.max(digit, 0);
            if (++unicodeDigits == 4) {
                emit((char) unicodeValue, out);
                unicodeDigits = -1;
            }
            return;
        }
        if (escaping) {
            escaping = false;
            switch (c) {
                case 'n' -> emit('\n', out);
                case 't' -> emit('\t', out);
                case 'r' -> emit('\r', out);
                case 'b', 'f' -> { }
                case 'u' -> {
                    unicodeDigits = 0;
                    unicodeValue = 0;
                }
                default -> emit(c, out);
            }
            return;
        }
        if (c == '\\') {
            escaping = true;
            return;
        }
        if (c == '"') {
            inString = false;
            if (readingKey) {
                readingKey = false;
                lastKey = key.toString();
            } else if (inSpeech) {
                inSpeech = false;
                speechClosed = true;
            }
            return;
        }
        emit(c, out);
    }

    private void emit(char c, StringBuilder out) {
        if (readingKey) {
            key.append(c);
        } else if (inSpeech) {
            out.append(c);
        }
    }
}
