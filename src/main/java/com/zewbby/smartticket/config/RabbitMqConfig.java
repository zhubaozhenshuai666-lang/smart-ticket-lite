package com.zewbby.smartticket.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zewbby.smartticket.constant.RabbitMqConstant;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.beans.factory.annotation.Qualifier;

@Configuration
public class RabbitMqConfig {

    @Bean
    public DirectExchange orderTimeoutDelayExchange() {
        return new DirectExchange(RabbitMqConstant.ORDER_TIMEOUT_DELAY_EXCHANGE, true, false);
    }

    @Bean
    public Queue orderTimeoutDelayQueue() {
        return QueueBuilder.durable(RabbitMqConstant.ORDER_TIMEOUT_DELAY_QUEUE)
                .ttl(RabbitMqConstant.ORDER_TIMEOUT_TTL_MILLIS)
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
}
