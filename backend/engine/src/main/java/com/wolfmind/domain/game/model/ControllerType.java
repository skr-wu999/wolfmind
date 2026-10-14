package com.wolfmind.domain.game.model;

/**
 * 座位控制者类型（ddd-design.md §3.3）。
 *
 * <p>{@code Player} 只保存"谁在操作"的类型与引用标识，不引用 Agent/AI 的任何类型，
 * 因此同一套规则校验同时适用于真人、AI 与内置机器人（C-5）。
 */
public enum ControllerType {
    /** 真人：{@code refId} 为 {@code userId}。 */
    HUMAN,
    /** AI Agent：{@code refId} 为 {@code agentId}，由 Agent Runtime 驱动。 */
    AI,
    /** 内置规则机器人：{@code refId} 为 {@code strategyKey}，无 LLM。 */
    BOT
}
