package com.zewbby.smartticket.task;

import com.zewbby.smartticket.domain.entity.TicketOrder;
import com.zewbby.smartticket.mapper.OrderMapper;
import com.zewbby.smartticket.service.OrderService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;

@Component
public class OrderTimeoutScanTask {

    private static final Logger LOGGER = LoggerFactory.getLogger(OrderTimeoutScanTask.class);

    private static final int SCAN_LIMIT = 100;

    private final OrderMapper orderMapper;

    private final OrderService orderService;

    public OrderTimeoutScanTask(OrderMapper orderMapper, OrderService orderService) {
        this.orderMapper = orderMapper;
        this.orderService = orderService;
    }

    @Scheduled(cron = "0 * * * * *")
    public void closeExpiredPendingOrders() {
        List<TicketOrder> expiredOrders =
                orderMapper.selectExpiredPendingOrders(LocalDateTime.now(), SCAN_LIMIT);
        for (TicketOrder order : expiredOrders) {
            try {
                orderService.closeTimeoutOrder(order.getId());
            } catch (RuntimeException exception) {
                LOGGER.warn("Failed to close expired order, orderId={}", order.getId(), exception);
            }
        }
    }
}
