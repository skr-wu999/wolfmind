package com.wolfmind.interfaces.rest;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

/** 健康检查的构造工具：把 lambda 变成 HealthIndicator（api-spec.md 9）。 */
public final class HealthChecks {

    private HealthChecks() {}

    /** 单项检查。 */
    @FunctionalInterface
    public interface Check {
        HealthIndicator.Result check();
    }

    /** 适配为 Spring 容器可收集的 HealthIndicator。 */
    public static HealthIndicator toIndicator(Check check) {
        return check::check;
    }

    public static List<HealthIndicator> toIndicators(List<Check> checks) {
        List<HealthIndicator> indicators = new ArrayList<>(checks.size());
        for (Check check : checks) {
            indicators.add(toIndicator(check));
        }
        return indicators;
    }

    /** 便捷构造：健康。 */
    public static HealthIndicator healthy(String name, boolean required, Supplier<String> detail) {
        return () -> new HealthIndicator.Result(name, true, required, detail.get());
    }

    /** 便捷构造：不健康。 */
    public static HealthIndicator unhealthy(String name, boolean required, Supplier<String> detail) {
        return () -> new HealthIndicator.Result(name, false, required, detail.get());
    }
}