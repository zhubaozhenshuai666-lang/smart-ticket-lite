package com.zewbby.smartticket.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zewbby.smartticket.constant.OrderConstant;
import com.zewbby.smartticket.constant.RabbitMqConstant;
import com.zewbby.smartticket.mq.DeadLetterMessageRecoverer;
import com.zewbby.smartticket.mq.RabbitPublisherCallbackHandler;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.Declarable;
import org.springframework.amqp.core.Declarables;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.config.SimpleRabbitListenerContainerFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.amqp.rabbit.config.RetryInterceptorBuilder;
import org.springframework.retry.interceptor.RetryOperationsInterceptor;

import java.util.ArrayList;
import java.util.List;

@Configuration
public class RabbitMqConfig {

    @Bean
    public DirectExchange orderTimeoutDelayExchange() {
        return new DirectExchange(RabbitMqConstant.ORDER_TIMEOUT_DELAY_EXCHANGE, true, false);
    }

    @Bean
    public Queue orderTimeoutDelayQueue() {
        return QueueBuilder.durable(RabbitMqConstant.ORDER_TIMEOUT_DELAY_QUEUE)
                .ttl(OrderConstant.ORDER_TIMEOUT_TTL_MILLIS)
                .deadLetterExchange(RabbitMqConstant.ORDER_TIMEOUT_DEAD_EXCHANGE)
                .deadLetterRoutingKey(RabbitMqConstant.ORDER_TIMEOUT_DEAD_ROUTING_KEY)
                .build();
    }

    @Bean
    public Binding orderTimeoutDelayBinding(
            @Qualifier("orderTimeoutDelayQueue") Queue orderTimeoutDelayQueue,
            @Qualifier("orderTimeoutDelayExchange") DirectExchange orderTimeoutDelayExchange) {
        return BindingBuilder.bind(orderTimeoutDelayQueue)
                .to(orderTimeoutDelayExchange)
                .with(RabbitMqConstant.ORDER_TIMEOUT_DELAY_ROUTING_KEY);
    }

    @Bean
    public DirectExchange orderTimeoutDeadExchange() {
        return new DirectExchange(RabbitMqConstant.ORDER_TIMEOUT_DEAD_EXCHANGE, true, false);
    }

    @Bean
    public Queue orderTimeoutDeadQueue() {
        return QueueBuilder.durable(RabbitMqConstant.ORDER_TIMEOUT_DEAD_QUEUE).build();
    }

    @Bean
    public Binding orderTimeoutDeadBinding(
            @Qualifier("orderTimeoutDeadQueue") Queue orderTimeoutDeadQueue,
            @Qualifier("orderTimeoutDeadExchange") DirectExchange orderTimeoutDeadExchange) {
        return BindingBuilder.bind(orderTimeoutDeadQueue)
                .to(orderTimeoutDeadExchange)
                .with(RabbitMqConstant.ORDER_TIMEOUT_DEAD_ROUTING_KEY);
    }

    @Bean
    public DirectExchange orderAsyncExchange() {
        return new DirectExchange(RabbitMqConstant.ORDER_ASYNC_EXCHANGE, true, false);
    }

    @Bean
    public Queue orderAsyncQueue() {
        return QueueBuilder.durable(RabbitMqConstant.ORDER_ASYNC_QUEUE)
                .deadLetterExchange(RabbitMqConstant.ORDER_ASYNC_DLX_EXCHANGE)
                .deadLetterRoutingKey(RabbitMqConstant.ORDER_ASYNC_DLQ_ROUTING_KEY)
                .build();
    }

    @Bean
    public Binding orderAsyncBinding(
            @Qualifier("orderAsyncQueue") Queue orderAsyncQueue,
            @Qualifier("orderAsyncExchange") DirectExchange orderAsyncExchange) {
        return BindingBuilder.bind(orderAsyncQueue)
                .to(orderAsyncExchange)
                .with(RabbitMqConstant.ORDER_ASYNC_ROUTING_KEY);
    }

    @Bean
    public Declarables orderAsyncShardDeclarables(
            @Qualifier("orderAsyncExchange") DirectExchange orderAsyncExchange,
            MqConsumerProperties mqConsumerProperties) {
        if (mqConsumerProperties.getAsyncQueueShardCount() <= 1) {
            return new Declarables();
        }
        List<Declarable> declarables = new ArrayList<>();
        for (int shardNo = 0; shardNo < mqConsumerProperties.getAsyncQueueShardCount(); shardNo++) {
            Queue queue = QueueBuilder.durable(RabbitMqConstant.orderAsyncQueueName(shardNo))
                    .deadLetterExchange(RabbitMqConstant.ORDER_ASYNC_DLX_EXCHANGE)
                    .deadLetterRoutingKey(RabbitMqConstant.ORDER_ASYNC_DLQ_ROUTING_KEY)
                    .build();
            Binding binding = BindingBuilder.bind(queue)
                    .to(orderAsyncExchange)
                    .with(RabbitMqConstant.orderAsyncRoutingKey(shardNo));
            declarables.add(queue);
            declarables.add(binding);
        }
        return new Declarables(declarables);
    }

    @Bean
    public String[] orderAsyncQueueNames(MqConsumerProperties mqConsumerProperties) {
        if (mqConsumerProperties.getAsyncQueueShardCount() <= 1) {
            return new String[] {RabbitMqConstant.ORDER_ASYNC_QUEUE};
        }
        String[] queueNames = new String[mqConsumerProperties.getAsyncQueueShardCount()];
        for (int shardNo = 0; shardNo < mqConsumerProperties.getAsyncQueueShardCount(); shardNo++) {
            queueNames[shardNo] = RabbitMqConstant.orderAsyncQueueName(shardNo);
        }
        return queueNames;
    }

    @Bean
    public DirectExchange orderAsyncDlxExchange() {
        return new DirectExchange(RabbitMqConstant.ORDER_ASYNC_DLX_EXCHANGE, true, false);
    }

    @Bean
    public Queue orderAsyncDlq() {
        return QueueBuilder.durable(RabbitMqConstant.ORDER_ASYNC_DLQ).build();
    }

    @Bean
    public Binding orderAsyncDlqBinding(
            @Qualifier("orderAsyncDlq") Queue orderAsyncDlq,
            @Qualifier("orderAsyncDlxExchange") DirectExchange orderAsyncDlxExchange) {
        return BindingBuilder.bind(orderAsyncDlq)
                .to(orderAsyncDlxExchange)
                .with(RabbitMqConstant.ORDER_ASYNC_DLQ_ROUTING_KEY);
    }

    @Bean
    public MessageConverter rabbitMessageConverter(ObjectMapper objectMapper) {
        return new Jackson2JsonMessageConverter(objectMapper);
    }

    @Bean
    public RabbitTemplate rabbitTemplate(ConnectionFactory connectionFactory,
                                         MessageConverter rabbitMessageConverter,
                                         RabbitPublisherCallbackHandler rabbitPublisherCallbackHandler) {
        RabbitTemplate rabbitTemplate = new RabbitTemplate(connectionFactory);
        rabbitTemplate.setMessageConverter(rabbitMessageConverter);
        rabbitTemplate.setMandatory(true);
        rabbitTemplate.setConfirmCallback(rabbitPublisherCallbackHandler);
        rabbitTemplate.setReturnsCallback(rabbitPublisherCallbackHandler);
        return rabbitTemplate;
    }

    @Bean
    public RetryOperationsInterceptor asyncOrderRetryInterceptor(DeadLetterMessageRecoverer deadLetterMessageRecoverer,
                                                                MqConsumerProperties mqConsumerProperties) {
        /*
         * 这里选择方案 A：消费者本地有限重试，重试耗尽后由自定义 MessageRecoverer 写 dead_letter_message。
         * 注意：重试解决的是“短暂系统异常”重试几次可能恢复；业务拒绝和重复消息应在消费者内部直接 ack，
         * 不应该被 RabbitMQ 反复重投。
         */
        long intervalMillis = mqConsumerProperties.getRetryIntervalSeconds() * 1000L;
        return RetryInterceptorBuilder.stateless()
                .maxAttempts(mqConsumerProperties.getMaxRetryCount())
                .backOffOptions(intervalMillis, 1.0, intervalMillis)
                .recoverer(deadLetterMessageRecoverer)
                .build();
    }

    @Bean
    public SimpleRabbitListenerContainerFactory asyncOrderRabbitListenerContainerFactory(
            ConnectionFactory connectionFactory,
            MessageConverter rabbitMessageConverter,
            RetryOperationsInterceptor asyncOrderRetryInterceptor,
            MqConsumerProperties mqConsumerProperties) {
        SimpleRabbitListenerContainerFactory factory = new SimpleRabbitListenerContainerFactory();
        factory.setConnectionFactory(connectionFactory);
        factory.setMessageConverter(rabbitMessageConverter);
        factory.setDefaultRequeueRejected(false);
        factory.setAdviceChain(asyncOrderRetryInterceptor);
        factory.setConcurrentConsumers(mqConsumerProperties.getConcurrentConsumers());
        factory.setMaxConcurrentConsumers(mqConsumerProperties.getMaxConcurrentConsumers());
        factory.setPrefetchCount(mqConsumerProperties.getPrefetchCount());
        return factory;
    }
}
