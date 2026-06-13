package com.zewbby.smartticket.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "smart-ticket.stock-bucket")
public class StockBucketProperties {

    /**
     * 是否启用库存 bucket 分片。
     *
     * 旧链路仍然保留单 Redis Key / 单 MySQL 行的兼容能力，方便历史数据、单元测试和本地调试。
     * 真正的高并发异步下单主链路应开启 bucket，把热点票档拆成多个 Redis Key 和 MySQL bucket 行。
     */
    private boolean enabled = true;

    /**
     * 一个票档默认拆成多少个库存桶。
     *
     * 10 不是生产结论，只是本项目本地压测和学习阶段的默认值。真实项目要根据热点票档 QPS、
     * 消费者并发数、MySQL 行锁等待和 Redis 热 key 指标继续压测调优。
     */
    private int defaultBucketCount = 10;

    /**
     * 当前接收新订单请求的库存 bucket 版本。
     *
     * 版本化 bucket 演进后，V1/V2 等物理桶池可以并存：新请求只打 active 版本，旧订单仍按
     * ticket_order_request.stock_bucket_version 回滚到原始版本。
     */
    private int activeVersion = 1;

    /**
     * 洪峰期单次 Redis Lua 允许探测的 bucket 数。
     *
     * 这个值必须显著小于洪峰期 bucket 总数；小窗口未命中会返回 PROBE_MISS，不在 Java 层二次重试。
     */
    private int activeProbeCount = 3;

    /**
     * 长尾收尾期聚合版本的目标 bucket 数。
     *
     * 例如 V1 使用 100 个桶抗洪峰，V2 可以使用 4 个桶承接回收库存和捡漏请求。
     */
    private int tailBucketCount = 4;

    /**
     * 是否启用 The Porter 后台搬运任务。
     *
     * 这个开关默认关闭，因为搬运只应该在 activeVersion 切到新版本且旧版本进入回收站之后启动。
     */
    private boolean porterEnabled = false;

    /**
     * Porter 搬运的旧版本来源。通常 V1=1，activeVersion 切到 2 后从 V1 搬到 V2。
     */
    private int porterSourceVersion = 1;

    /**
     * 每轮最多搬运多少张票，防止一次扫描把 Redis 和 MySQL 打满。
     */
    private int porterMaxMoveQuantityPerRun = 1000;

    /**
     * Porter 定时任务每轮最多扫描多少个票档。
     */
    private int porterBatchSize = 100;

    /**
     * 同一票档同一版本对的 Porter 分布式锁 TTL。
     */
    private long porterLockTtlSeconds = 30L;

    /**
     * 单次搬运 Redis 幂等记录 TTL。
     */
    private long porterMoveRecordTtlSeconds = 24 * 60 * 60L;

    /**
     * Porter 定时任务间隔。
     */
    private long porterFixedDelaySeconds = 300L;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public int getDefaultBucketCount() {
        return defaultBucketCount;
    }

    public void setDefaultBucketCount(int defaultBucketCount) {
        this.defaultBucketCount = defaultBucketCount;
    }

    public int getActiveVersion() {
        return activeVersion;
    }

    public void setActiveVersion(int activeVersion) {
        this.activeVersion = activeVersion;
    }

    public int getActiveProbeCount() {
        return activeProbeCount;
    }

    public void setActiveProbeCount(int activeProbeCount) {
        this.activeProbeCount = activeProbeCount;
    }

    public int getTailBucketCount() {
        return tailBucketCount;
    }

    public void setTailBucketCount(int tailBucketCount) {
        this.tailBucketCount = tailBucketCount;
    }

    public boolean isPorterEnabled() {
        return porterEnabled;
    }

    public void setPorterEnabled(boolean porterEnabled) {
        this.porterEnabled = porterEnabled;
    }

    public int getPorterSourceVersion() {
        return porterSourceVersion;
    }

    public void setPorterSourceVersion(int porterSourceVersion) {
        this.porterSourceVersion = porterSourceVersion;
    }

    public int getPorterMaxMoveQuantityPerRun() {
        return porterMaxMoveQuantityPerRun;
    }

    public void setPorterMaxMoveQuantityPerRun(int porterMaxMoveQuantityPerRun) {
        this.porterMaxMoveQuantityPerRun = porterMaxMoveQuantityPerRun;
    }

    public int getPorterBatchSize() {
        return porterBatchSize;
    }

    public void setPorterBatchSize(int porterBatchSize) {
        this.porterBatchSize = porterBatchSize;
    }

    public long getPorterLockTtlSeconds() {
        return porterLockTtlSeconds;
    }

    public void setPorterLockTtlSeconds(long porterLockTtlSeconds) {
        this.porterLockTtlSeconds = porterLockTtlSeconds;
    }

    public long getPorterMoveRecordTtlSeconds() {
        return porterMoveRecordTtlSeconds;
    }

    public void setPorterMoveRecordTtlSeconds(long porterMoveRecordTtlSeconds) {
        this.porterMoveRecordTtlSeconds = porterMoveRecordTtlSeconds;
    }

    public long getPorterFixedDelaySeconds() {
        return porterFixedDelaySeconds;
    }

    public void setPorterFixedDelaySeconds(long porterFixedDelaySeconds) {
        this.porterFixedDelaySeconds = porterFixedDelaySeconds;
    }

    public long getPorterFixedDelayMillis() {
        return porterFixedDelaySeconds * 1000L;
    }

}
