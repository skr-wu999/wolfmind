package com.wolfmind.application.scheduling;

import java.util.function.Predicate;

public record SpeechPacing(long baseMs, long perCharMs, long maxMs, Predicate<String> appliesTo) {
    public static final SpeechPacing NONE = new SpeechPacing(0, 0, 0, id -> false);
    public SpeechPacing {
        if (baseMs < 0 || perCharMs < 0 || maxMs < 0) throw new IllegalArgumentException("发言停顿参数不能为负");
        java.util.Objects.requireNonNull(appliesTo, "appliesTo");
    }
    public long pauseFor(String content) {
        return Math.min(maxMs, baseMs + perCharMs * (content == null ? 0 : content.codePointCount(0, content.length())));
    }
    public boolean enabledFor(String id) { return maxMs > 0 && appliesTo.test(id); }
}
