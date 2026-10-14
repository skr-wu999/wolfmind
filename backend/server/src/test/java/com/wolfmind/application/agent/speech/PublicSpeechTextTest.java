package com.wolfmind.application.agent.speech;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class PublicSpeechTextTest {
    @Test
    void removesOnlyStageHeaderAndPreservesJudgmentAndNaturalTimeReferences() {
        assertEquals("6 号发言。我想听听 3 号的理由。",
                PublicSpeechText.clean("第 1 天，DAY_DISCUSS，6 号发言。我想听听 3 号的理由。"));
        assertEquals("我比较了第 1 天的投票，仍然怀疑 3 号。",
                PublicSpeechText.clean("我比较了第 1 天的投票，仍然怀疑 3 号。"));
        assertEquals("第 1 天我怀疑过 3 号，现在想再听听他的解释。",
                PublicSpeechText.clean("第 1 天我怀疑过 3 号，现在想再听听他的解释。"));
        assertEquals("我会结合白天投票再判断。", PublicSpeechText.clean("我会结合DAY_VOTE再判断。"));
    }

    @Test
    void headingRemovalDoesNotHidePrivateClaims() {
        assertEquals("我昨晚查验了 4 号。",
                PublicSpeechText.clean("第 1 天，DAY_DISCUSS。我昨晚查验了 4 号。"));
        assertNull(PublicSpeechText.clean(null));
    }
}
