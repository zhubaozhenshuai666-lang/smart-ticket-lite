package com.zewbby.smartticket.task;

import com.zewbby.smartticket.config.StockConsistencyProperties;
import com.zewbby.smartticket.enums.StockCheckTypeEnum;
import com.zewbby.smartticket.service.StockConsistencyService;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class StockConsistencyScanTask {

    private final StockConsistencyProperties stockConsistencyProperties;

    private final StockConsistencyService stockConsistencyService;

    public StockConsistencyScanTask(StockConsistencyProperties stockConsistencyProperties,
                                    StockConsistencyService stockConsistencyService) {
        this.stockConsistencyProperties = stockConsistencyProperties;
        this.stockConsistencyService = stockConsistencyService;
    }

    /**
     * 定时库存巡检。
     *
     * 巡检不能太频繁：每次都要访问 MySQL、Redis 并统计在途请求，高频执行会和正常售卖抢资源。
     * batch-size 用来限制每轮扫描量，避免大库一次性扫全表。
     * 默认只检查和记录差异，不自动修复；自动修复必须显式开启，因为修库存属于高风险运维动作。
     */
    @Scheduled(fixedDelayString = "#{@stockConsistencyProperties.fixedDelayMillis}")
    public void scan() {
        if (!stockConsistencyProperties.isEnabled()) {
            return;
        }
        stockConsistencyService.checkAll(
                StockCheckTypeEnum.SCHEDULED.getCode(),
                stockConsistencyProperties.getBatchSize()
        );
        if (stockConsistencyProperties.isAutoRepairEnabled()) {
            stockConsistencyService.compensateFailedRequests(stockConsistencyProperties.getBatchSize());
        }
    }
}
