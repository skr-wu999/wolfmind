package com.wolfmind.application.lab;

import java.util.List;
import java.util.Optional;

/**
 * 实验室存储端口：Agent 配置与批次记录（api-spec.md §8）。
 *
 * <p><b>为什么不落 SQLite。</b>database-schema.md 的六张业务表里没有"实验配置"这一类，
 * 而那张表清单是被批准的契约——为实验室凭空加一张表，等于在"归档与长期数据"里混进
 * 一类既非对局也非统计的记录。批次与对局的关联已经在 {@code games.lab_batch_id} 上，
 * 这里只保存操作者可见的配置文本与进度视图，两种存储模式下都活得下去。
 *
 * <p>两种实现的语义必须一致：<b>保存是覆盖（同一 id 保留 {@code createdAt}），
 * 列表按 {@code updatedAt} 倒序</b>，否则"刚保存的配置排在列表最后"会看起来像没保存成功。
 */
public interface LabStorePort {

    /** 保存（新增或覆盖）。 */
    LabAgentConfig saveConfig(LabAgentConfig config);

    Optional<LabAgentConfig> config(String agentConfigId);

    /**
     * 按 {@code updatedAt} 倒序分页。
     *
     * @param ownerId 只返回该账号的配置；{@code null} 表示不限（运维与测试用）。
     *                <b>目录必须按账号过滤</b>：实验配置含模型、温度与人格，
     *                是这个人做过的实验条件，不该成为别人可读的资产
     */
    List<LabAgentConfig> configs(String ownerId, int page, int pageSize);

    /** 按 id 批量取；缺失的 id 不会出现在结果里，调用方据此判断"配置已被删除"。 */
    List<LabAgentConfig> configsByIds(List<String> agentConfigIds);

    LabBatch saveBatch(LabBatch batch);

    Optional<LabBatch> batch(String batchId);

    /** 按 {@code createdAt} 倒序分页；{@code ownerId} 为 {@code null} 时不限。 */
    List<LabBatch> batches(String ownerId, int page, int pageSize);

    /** 尚未结束的批次（供运行器每轮扫描）；跨账号，因为并发上限是全局的。 */
    List<LabBatch> runningBatches();
}
