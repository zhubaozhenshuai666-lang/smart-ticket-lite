package com.zewbby.smartticket.service;

import com.zewbby.smartticket.mq.AsyncCreateOrderMessage;

public interface AsyncOrderMessagePublisher {

    /**
     * 将异步下单消息提交给当前发送链路。
     *
     * 这个接口把“业务请求已经预扣库存”和“消息如何进入 MQ”隔离开来。
     * 当前项目底层使用 local_message Outbox 和 Publisher Confirm。
     * 如果后续要把消息链路迁移到更独立的消息平台，只需要替换这个接口的实现，订单入口不用再关心投递细节。
     *
     * @param message 异步创建订单消息，requestId 是后续消费者幂等处理的业务主键。
     * @return 当前发送链路产生的 messageId；如果底层链路暂时没有独立消息号，可以返回 requestId 作为关联值。
     */
    String publish(AsyncCreateOrderMessage message);

    /**
     * 使用调用方已经确定的 messageId 提交消息。
     *
     * 抢票入口需要在 ticket_order_request 首次落库时就写入 messageId，避免先插入请求再 update QUEUED 的写放大。
     * 不支持外部 messageId 的实现可以继续退化为普通发布。
     */
    default String publish(String messageId, AsyncCreateOrderMessage message) {
        return publish(message);
    }
}
