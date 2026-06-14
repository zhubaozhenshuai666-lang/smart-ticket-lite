package com.zewbby.smartticket.mq;

import com.zewbby.smartticket.constant.RabbitMqConstant;
import com.zewbby.smartticket.domain.entity.TicketOrderRequest;
import com.zewbby.smartticket.enums.OrderRequestStatusEnum;
import com.zewbby.smartticket.enums.RedisStockReleaseResult;
import com.zewbby.smartticket.mapper.OrderRequestMapper;
import com.zewbby.smartticket.service.StockLuaService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.ImmediateRequeueAmqpException;

@Deprecated
public class AsyncCreateOrderDeadLetterConsumer {

    private static final Logger LOGGER = LoggerFactory.getLogger(AsyncCreateOrderDeadLetterConsumer.class);

    private static final String DLQ_FAIL_REASON = "异步下单消息消费失败，已进入死信队列";

    private final OrderRequestMapper orderRequestMapper;

    private final StockLuaService stockLuaService;

    public AsyncCreateOrderDeadLetterConsumer(OrderRequestMapper orderRequestMapper,
                                              StockLuaService stockLuaService) {
        this.orderRequestMapper = orderRequestMapper;
        this.stockLuaService = stockLuaService;
    }

    /*
     * 阶段 2 Task C+ 选择的是“Spring AMQP listener retry + 自定义 MessageRecoverer 直接落库”的方案 A。
     * 这个历史 DLQ 消费器不再注册为 Spring Bean，避免同一条异常消息同时走 Rabbit 原生 DLQ 和数据库死信两套链路。
     * Rabbit 原生 DLQ 配置仍保留为兜底队列定义，但本阶段的重试耗尽入口是 DeadLetterMessageRecoverer。
     */
    public void consume(AsyncCreateOrderMessage message) {
        LOGGER.error("接收到了异步创建订单的死信消息, requestId={}", message.getRequestId());

        TicketOrderRequest orderRequest = orderRequestMapper.selectByRequestId(message.getRequestId());
        if (orderRequest == null) {
            LOGGER.warn("Ignored async order dead letter message, requestId={} does not exist",
                    message.getRequestId());
            return;
        }
        if (OrderRequestStatusEnum.canEnterProcessing(orderRequest.getStatus())
                || OrderRequestStatusEnum.PROCESSING.getCode().equals(orderRequest.getStatus())) {
            int failedRows = orderRequestMapper.markFailed(orderRequest.getId(), DLQ_FAIL_REASON);
            //利用数据库行锁
            if (failedRows != 1) {
                LOGGER.warn("Failed to mark async order request FAILED from DLQ, requestId={}",
                        orderRequest.getRequestId());
                return;
            }
        }
        //处理过的死信消息或者已经成功消费的消息不需要处理
        else if (!OrderRequestStatusEnum.FAILED.getCode().equals(orderRequest.getStatus())
                || !DLQ_FAIL_REASON.equals(orderRequest.getFailReason())) {
            LOGGER.info("Skipped async order dead letter message, requestId={}, status={}",
                    orderRequest.getRequestId(), orderRequest.getStatus());
            return;
        }

        try {
            // 死信只代表消息链路失败，不能证明订单一定失败；这里必须以 requestId 的预扣记录为准做一次性释放。
            RedisStockReleaseResult releaseResult = stockLuaService.releasePreDeductedStock(
                    orderRequest.getRequestId(),
                    orderRequest.getTicketCategoryId(),
                    orderRequest.getStockBucketVersion(),
                    orderRequest.getStockBucketNo(),
                    orderRequest.getQuantity()
            );
            if (releaseResult.isSuccess() || releaseResult == RedisStockReleaseResult.ALREADY_COMPENSATED) {
                orderRequestMapper.markCompensated(orderRequest.getId(), java.time.LocalDateTime.now());
            }
            LOGGER.info("Released Redis pre-deducted stock from DLQ, requestId={}, ticketCategoryId={}, quantity={}, result={}",
                    orderRequest.getRequestId(),
                    orderRequest.getTicketCategoryId(),
                    orderRequest.getQuantity(),
                    releaseResult);
        } catch (RuntimeException exception) {
            LOGGER.error("Failed to rollback Redis stock from DLQ, requestId={}, will requeue dead letter message",
                    orderRequest.getRequestId(), exception.getMessage());
            throw new ImmediateRequeueAmqpException("Redis stock rollback from DLQ failed", exception);
        }
    }
}
