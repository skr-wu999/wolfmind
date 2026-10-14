package com.wolfmind.application.port;

import com.wolfmind.domain.game.event.EventEnvelope;
import java.util.List;

/** 将已验证的历史事件完整安装到不存在的流，已有流不得覆盖，不发布新行动或唤醒 AI。 */
@FunctionalInterface
public interface ArchivedEventRestorePort {
    boolean restoreAbsent(String gameId, List<EventEnvelope> events);
}
