package com.wolfmind.application.agent.speech;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SpeechPreviewStreamTest {

    private final List<SpeechPreviewPort.SpeechPreview> published = new ArrayList<>();

    private SpeechPreviewStream stream(Predicate<String> leakFree) {
        return new SpeechPreviewStream(published::add, "g_s", "p_3", 3, 41, leakFree);
    }

    private static void feed(SpeechPreviewStream stream, String output) {
        for (int i = 0; i < output.length(); i += 2) {
            stream.accept(output.substring(i, Math.min(output.length(), i + 2)));
        }
    }

    private String joined() {
        StringBuilder text = new StringBuilder();
        published.forEach(preview -> text.append(preview.delta()));
        return text.toString();
    }

    @Test
    void streamedSpeechDoesNotExposeStageHeaderAcrossTokenBoundaries() {
        var preview = stream(text -> true);
        feed(preview, "{\"speech\":\"第 1 天，DAY_DISCUSS。我的判断是先关注 8 号。\"}");
        preview.complete(true);
        assertEquals("我的判断是先关注 8 号。", joined());
        assertTrue(published.get(published.size() - 1).last());
        assertTrue(published.stream().noneMatch(p -> p.delta().contains("DAY_DISCUSS")));
    }

    @Test
    void removedHeaderDoesNotBypassPreviewLeakCheck() {
        var preview = stream(text -> !text.contains("查验"));
        feed(preview, "{\"speech\":\"第 1 天，DAY_DISCUSS。我昨晚查验了 4 号。\"}");
        preview.complete(true);
        assertEquals("", joined());
        assertTrue(published.isEmpty());
    }

    @Test
    @DisplayName("按小句边界推送，结束时补发余下文本并标记 final；拼接结果等于完整发言")
    void emitsClausesThenFinal() {
        SpeechPreviewStream stream = stream(text -> true);
        feed(stream, "{\"actionType\":\"SPEAK\",\"speech\":\"我先说两句，5 号很可疑。我投 5 号\"}");

        assertTrue(published.size() >= 2, "生成过程中就应按小句推送：" + published);
        assertTrue(published.stream().noneMatch(SpeechPreviewPort.SpeechPreview::last), "完成前不得标记 final");
        stream.complete(true);

        SpeechPreviewPort.SpeechPreview last = published.get(published.size() - 1);
        assertTrue(last.last());
        assertEquals("我先说两句，5 号很可疑。我投 5 号", joined());
        for (int i = 0; i < published.size(); i++) {
            SpeechPreviewPort.SpeechPreview preview = published.get(i);
            assertEquals(i, preview.index(), "index 必须从 0 连续递增");
            assertEquals(41, preview.expectedVersion());
            assertEquals("g_s", preview.gameId());
            assertEquals(3, preview.seatNo());
        }
    }

    @Test
    @DisplayName("泄密检查不通过：立即停止且不发 final，命中的小句不会被推送")
    void stopsWithoutFinalOnLeak() {
        SpeechPreviewStream stream = stream(text -> !text.contains("查验"));
        feed(stream, "{\"speech\":\"大家好，我昨晚查验了 4 号，是狼。后面的也不发\"}");
        stream.complete(true);

        assertEquals("大家好，", joined(), "只有命中之前已通过检查的小句可以出现");
        assertTrue(published.stream().noneMatch(SpeechPreviewPort.SpeechPreview::last), "泄密后不得发 final");
        assertTrue(published.stream().noneMatch(preview -> preview.delta().contains("查验")));
    }

    @Test
    @DisplayName("模型调用失败或发言未闭合：不发 final")
    void noFinalOnFailureOrUnclosedSpeech() {
        SpeechPreviewStream failed = stream(text -> true);
        feed(failed, "{\"speech\":\"说到一半，");
        failed.complete(false);
        assertFalse(published.stream().anyMatch(SpeechPreviewPort.SpeechPreview::last));

        published.clear();
        SpeechPreviewStream unclosed = stream(text -> true);
        feed(unclosed, "{\"speech\":\"没有闭合的引号");
        unclosed.complete(true);
        assertTrue(published.isEmpty(), "没有小句边界、也没闭合时不应有任何预览：" + published);
    }

    @Test
    @DisplayName("没有 speech 字段的输出（例如投票）不产生预览")
    void noPreviewWithoutSpeech() {
        SpeechPreviewStream stream = stream(text -> true);
        feed(stream, "{\"actionType\":\"VOTE\",\"targetSeatNo\":5,\"reason\":\"他可疑，我投他。\"}");
        stream.complete(true);
        assertTrue(published.isEmpty());
        assertEquals(0, stream.emittedCount());
    }
}
