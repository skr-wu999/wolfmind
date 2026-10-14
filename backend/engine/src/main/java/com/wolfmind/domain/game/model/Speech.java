package com.wolfmind.domain.game.model;

/**
 * 发言内容（ddd-design.md §2.3）：去首尾空白后长度 1..{@code maxLength}。
 *
 * <p>上限来自本局 {@code GameRules.speechMaxLength}，属于领域校验。
 */
public record Speech(String content) {

    public Speech {
        if (content == null) {
            throw GameException.Factory.speechInvalid("发言内容不能为空");
        }
        content = content.strip();
        if (content.isEmpty()) {
            throw GameException.Factory.speechInvalid("发言内容不能为空白");
        }
    }

    /** 按本局规则校验长度。 */
    public Speech validateLength(int maxLength) {
        if (content.length() > maxLength) {
            throw GameException.Factory.speechInvalid(
                    "发言长度 " + content.length() + " 超过上限 " + maxLength);
        }
        return this;
    }

    public static Speech of(String content) {
        return new Speech(content);
    }
}
