package com.zewbby.smartticket.task;

import com.zewbby.smartticket.config.StockBucketProperties;
import com.zewbby.smartticket.domain.entity.TicketStock;
import com.zewbby.smartticket.mapper.TicketStockMapper;
import com.zewbby.smartticket.service.StockBucketPorterService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class StockBucketPorterTask {

    private static final Logger LOGGER = LoggerFactory.getLogger(StockBucketPorterTask.class);

    private final StockBucketProperties stockBucketProperties;

    private final TicketStockMapper ticketStockMapper;

    private final StockBucketPorterService stockBucketPorterService;

    public StockBucketPorterTask(StockBucketProperties stockBucketProperties,
                                 TicketStockMapper ticketStockMapper,
                                 StockBucketPorterService stockBucketPorterService) {
        this.stockBucketProperties = stockBucketProperties;
        this.ticketStockMapper = ticketStockMapper;
        this.stockBucketPorterService = stockBucketPorterService;
    }

    /**
     * The Porter 后台搬运任务。
     *
     * 只有在 activeVersion 切到新版本且 porterEnabled=true 后才会运行。它不负责切版本，
     * 只负责把旧版本回收站里的 available_stock 逐步搬到 activeVersion。
     */
    @Scheduled(fixedDelayString = "#{@stockBucketProperties.porterFixedDelayMillis}")
    public void moveReturnedStock() {
        if (!stockBucketProperties.isEnabled() || !stockBucketProperties.isPorterEnabled()) {
            return;
        }
        int fromVersion = stockBucketProperties.getPorterSourceVersion();
        int toVersion = stockBucketProperties.getActiveVersion();
        if (fromVersion <= 0 || toVersion <= 0 || fromVersion == toVersion) {
            return;
        }
        List<TicketStock> stocks = ticketStockMapper.selectPageAfterId(0L, stockBucketProperties.getPorterBatchSize());
        for (TicketStock stock : stocks) {
            try {
                stockBucketPorterService.moveReturnedStock(
                        stock.getTicketCategoryId(),
                        fromVersion,
                        toVersion,
                        stockBucketProperties.getDefaultBucketCount(),
                        stockBucketProperties.getTailBucketCount(),
                        stockBucketProperties.getPorterMaxMoveQuantityPerRun()
                );
            } catch (RuntimeException exception) {
                LOGGER.warn("Porter move failed, ticketCategoryId={}, fromVersion={}, toVersion={}, reason={}",
                        stock.getTicketCategoryId(), fromVersion, toVersion, exception.getMessage());
            }
        }
    }
}
