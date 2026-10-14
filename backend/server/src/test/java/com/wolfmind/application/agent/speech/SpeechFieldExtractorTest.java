package com.wolfmind.application.agent.speech;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SpeechFieldExtractorTest {

    private static String feedAll(SpeechFieldExtractor extractor, List<String> chunks) {
        StringBuilder out = new StringBuilder();
        for (String chunk : chunks) {
            out.append(extractor.feed(chunk));
        }
        return out.toString();
    }

    /** 把整段输出按每 {@code size} 个字符切开，模拟 token 边界落在任意位置。 */
    private static List<String> split(String text, int size) {
        List<String> chunks = new java.util.ArrayList<>();
        for (int i = 0; i < text.length(); i += size) {
            chunks.add(text.substring(i, Math.min(text.length(), i + size)));
        }
        return chunks;
    }

    @Test
    @DisplayName("只取出顶层 speech 的文本，其他字段（含理由）一律不外流")
    void extractsOnlyTopLevelSpeech() {
        String output = "{\"actionType\":\"SPEAK\",\"reason\":\"我是狼，要装好人\","
                + "\"speech\":\"我觉得 5 号可疑，\\\"跳得太早\\\"。\",\"targetSeatNo\":5}";
        for (int size = 1; size <= 7; size++) {
            SpeechFieldExtractor extractor = new SpeechFieldExtractor();
            assertEquals("我觉得 5 号可疑，\"跳得太早\"。", feedAll(extractor, split(output, size)),
                    "切片大小 " + size);
            assertTrue(extractor.speechClosed());
        }
    }

    @Test
    @DisplayName("转义序列跨 token 也能正确解码（含 4 位十六进制转义）")
    void decodesEscapesAcrossChunks() {
        String output = "{\"speech\":\"第一句\\n第二句\\u4f60\\u597d\"}";
        for (int size = 1; size <= 5; size++) {
            SpeechFieldExtractor extractor = new SpeechFieldExtractor();
            assertEquals("第一句\n第二句你好", feedAll(extractor, split(output, size)), "切片大小 " + size);
        }
    }

    @Test
    @DisplayName("嵌套对象里的 speech、作为值出现的 \"speech\" 都不算")
    void ignoresNestedAndValueOccurrences() {
        String output = "{\"meta\":{\"speech\":\"嵌套的不算\"},\"note\":\"speech\","
                + "\"list\":[\"speech\"],\"speech\":\"这才是发言\"}";
        SpeechFieldExtractor extractor = new SpeechFieldExtractor();
        assertEquals("这才是发言", feedAll(extractor, split(output, 3)));
    }

    @Test
    @DisplayName("JSON 前的围栏与说明文字被忽略；speech 为 null 时没有输出")
    void ignoresPreambleAndNullSpeech() {
        SpeechFieldExtractor fenced = new SpeechFieldExtractor();
        assertEquals("你好", feedAll(fenced, List.of("```json\n{\"spe", "ech\": \"你", "好\"}\n```")));

        SpeechFieldExtractor nullSpeech = new SpeechFieldExtractor();
        assertEquals("", feedAll(nullSpeech, List.of("{\"speech\": null, \"reason\": \"不说话\"}")));
        assertFalse(nullSpeech.speechClosed());
    }
}
