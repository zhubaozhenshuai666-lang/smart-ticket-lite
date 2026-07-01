package com.zewbby.smartticket.mq;

import com.zewbby.smartticket.config.OrderTimeoutProperties;
import com.zewbby.smartticket.service.OrderTimeoutMessagePublisher;
import org.springframework.stereotype.Component;

@Component
public class OrderTimeoutProducer {

    private final OrderTimeoutMessagePublisher orderTimeoutMessagePublisher;

    private final OrderTimeoutProperties orderTimeoutProperties;

    public OrderTimeoutProducer(OrderTimeoutMessagePublisher orderTimeoutMessagePublisher,
                                OrderTimeoutProperties orderTimeoutProperties) {
        this.orderTimeoutMessagePublisher = orderTimeoutMessagePublisher;
        this.orderTimeoutProperties = orderTimeoutProperties;
    }

    /**
     * 接收一个超时消息对象，先把它安全地存到本地数据库的消息表里，然后准备发送。
     * @param message
     * @return
     */
    public String sendOrderTimeoutMessage(OrderTimeoutMessage message) {
        //如果不开启延迟功能直接拦截
        if (!orderTimeoutProperties.isDelayMessageEnabled()) {
            return null;
        }
        return orderTimeoutMessagePublisher.publish(message);
    }

    public String sendOrderTimeoutMessage(Long orderId, String orderNo) {
        return sendOrderTimeoutMessage(new OrderTimeoutMessage(orderId, orderNo));
    }
}
