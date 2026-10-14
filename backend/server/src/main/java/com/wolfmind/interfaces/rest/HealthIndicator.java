package com.wolfmind.interfaces.rest;

/**
 * 一项依赖的健康检查（api-spec.md 9）。
 *
 * <p>required 决定它是否影响整体状态：事件存储不可用必须让 /health 返回 503，
 * 而 LLM 不可用只降低能力（对局仍可走规则兜底），不应把服务判成不可用。
 */
public interface HealthIndicator {

    Result check();

    record Result(String name, boolean healthy, boolean required, String detail) {}
}