package com.wolfmind.application.room;

import java.util.List;

/**
 * 房间索引：回答"有哪些房间"。
 *
 * <p>为什么需要它：事件存储是按流派组织的（键=房间号），无法枚举"所有房间"。
 * 真实部署里这个索引对应 SQLite 的 {@code rooms} 表或 Redis 的一个 Set；
 * 放在端口后面，查询用例就不必知道索引存在哪里。
 *
 * <p>它**不承载房间状态**——那只能来自事件流重放。索引只记"谁存在"，
 * 因此索引与事件流不一致时后果是"某个房间查不到"或"指向一个不存在的事件流"，
 * 而不会让客户端看到错误的座位或就绪状态。
 */
public interface RoomIndexPort {

    /** 记录一个房间号；重复记录是幂等的。 */
    void add(String roomId);

    /** 全部房间号，按插入顺序（大厅按"最新在前"展示时由查询用例反转）。 */
    List<String> all();

    boolean contains(String roomId);
}