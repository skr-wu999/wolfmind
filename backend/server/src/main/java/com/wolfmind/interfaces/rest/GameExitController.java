package com.wolfmind.interfaces.rest;

import com.wolfmind.application.room.RoomLifecycleService;
import com.wolfmind.application.room.RoomView;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1")
final class GameExitController {
    private final ObjectProvider<RoomLifecycleService> lifecycle;
    private final ViewerResolverPort viewer;
    GameExitController(ObjectProvider<RoomLifecycleService> lifecycle, ViewerResolverPort viewer) {
        this.lifecycle=lifecycle; this.viewer=viewer;
    }
    @PostMapping("/games/{gameId}/leave")
    RoomView leave(@PathVariable String gameId,
            @RequestHeader(name="Idempotency-Key", required=false) String key, HttpServletRequest request) {
        String actor = viewer.resolve(request).viewerUserId();
        if (actor == null || actor.isBlank()) throw new UnauthorizedException("退出对局需要登录");
        if (key == null || key.isBlank()) throw new RequestShapeException("缺少 Idempotency-Key 头");
        RoomLifecycleService service = lifecycle.getIfAvailable();
        if (service == null) throw new NotWiredException("房间退出服务尚未接入");
        return service.leaveGame(gameId, actor, key);
    }
}
