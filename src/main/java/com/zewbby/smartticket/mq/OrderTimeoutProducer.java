package com.zewbby.smartticket.mq;

import com.zewbby.smartticket.service.LocalMessageService;
import org.springframework.stereotype.Component;

@Component
public class OrderTimeoutProducer {

    private final LocalMessageService localMessageService;

    public OrderTimeoutProducer(LocalMessageService localMessageService) {
        this.localMessageService = localMessageService;
    }

    /**
     * 提交订单超时关闭消息。
     *
     * 这里故意不直接调用 RabbitTemplate。订单超时关闭和异步创单一样，都是交易主链路的一部分：
     * 如果订单创建成功但延迟消息丢了，locked_stock 会一直占住，未支付 payment_order 也无法关闭。
     * Outbox 让“需要发送超时关闭消息”先落库，再由统一发送器投递并等待 Publisher Confirm。
     */
    public String sendOrderTimeoutMessage(OrderTimeoutMessage message) {
        return localMessageService.createOrderTimeoutCloseMessage(message);
    }

    public String sendOrderTimeoutMessage(Long orderId, String orderNo) {
        return sendOrderTimeoutMessage(new OrderTimeoutMessage(orderId, orderNo));
    }
}
