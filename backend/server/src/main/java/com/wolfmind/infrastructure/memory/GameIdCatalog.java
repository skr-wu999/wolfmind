package com.wolfmind.infrastructure.memory;

import java.util.List;

/**
 * 事件存储中已存在的对局流目录。
 *
 * <p>不放进 {@code EventStorePort}：端口只表达"按流读写"，而枚举全部对局是列表接口与
 * 对局驱动的运维需求。返回值必须排除房间流与账号流，且按 id 升序。
 */
public interface GameIdCatalog {

    List<String> gameIds();
}
