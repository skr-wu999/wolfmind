package com.wolfmind.interfaces.rest;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 依赖健康（api-spec.md 9）。
 *
 * <p>required 项不健康即 503：事件存储不可用等于无法对局，必须让编排层把实例摘掉。
 * LLM 不可用只是一个 required=false 的检查项——对局仍能走规则兜底，不该把服务判成不可用。
 *
 * <p>检查项用 ObjectProvider 注入：它由配置类按部署形态注册，因此"内存部署"与
 * "Redis + SQLite 部署"共享同一个控制器，不需要分支。
 */
@RestController
@RequestMapping(HealthController.BASE_PATH)
class HealthController {

    static final String BASE_PATH = "/api/v1";

    private final ObjectProvider<HealthIndicator> indicators;

    HealthController(ObjectProvider<HealthIndicator> indicators) {
        this.indicators = indicators;
    }

    @GetMapping("/health")
    ResponseEntity<Map<String, Object>> health() {
        List<Map<String, Object>> dependencies = new ArrayList<>();
        boolean requiredDown = false;
        for (HealthIndicator indicator : indicators) {
            HealthIndicator.Result result = indicator.check();
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("name", result.name());
            item.put("status", result.healthy() ? "UP" : "DOWN");
            item.put("required", result.required());
            item.put("detail", result.detail());
            dependencies.add(item);
            if (!result.healthy() && result.required()) {
                requiredDown = true;
            }
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status", requiredDown ? "DOWN" : "UP");
        body.put("version", "0.1.0");
        body.put("dependencies", dependencies);
        return ResponseEntity.status(requiredDown ? 503 : 200).body(body);
    }
}
