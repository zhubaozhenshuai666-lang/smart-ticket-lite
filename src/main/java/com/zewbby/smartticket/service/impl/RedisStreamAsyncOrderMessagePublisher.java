package com.zewbby.smartticket.service.impl;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zewbby.smartticket.common.BusinessException;
import com.zewbby.smartticket.config.AsyncOrderSubmitProperties;
import com.zewbby.smartticket.mq.AsyncCreateOrderMessage;
import com.zewbby.smartticket.service.AsyncOrderMessagePublisher;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.redis.connection.stream.RecordId;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

@Component
@ConditionalOnProperty(prefix = "smart-ticket.async-order-submit", name = "publisher-mode", havingValue = "redis-stream")
public class RedisStreamAsyncOrderMessagePublisher implements AsyncOrderMessagePublisher {

    public static final String FIELD_MESSAGE_ID = "messageId";

    public static final String FIELD_ACTIVITY_SCOPE_KEY = "activityScopeKey";

    public static final String FIELD_ROUTING_PARTITION_KEY = "routingPartitionKey";

    public static final String FIELD_PAYLOAD = "payload";

    private final StringRedisTemplate stringRedisTemplate;

    private final ObjectMapper objectMapper;

    private final AsyncOrderSubmitProperties properties;

    public RedisStreamAsyncOrderMessagePublisher(StringRedisTemplate stringRedisTemplate,
                                                 ObjectMapper objectMapper,
                                                 AsyncOrderSubmitProperties properties) {
        this.stringRedisTemplate = stringRedisTemplate;
        this.objectMapper = objectMapper;
        this.properties = properties;
    }

    @Override
    public String publish(AsyncCreateOrderMessage message) {
        String messageId = message.getMessageId();
        if (messageId == null || messageId.isBlank()) {
            messageId = "MSG" + message.getRequestId();
        }
        return publish(messageId, message);
    }

    @Override
    public String publish(String messageId, AsyncCreateOrderMessage message) {
        message.setMessageId(messageId);
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put(FIELD_MESSAGE_ID, messageId);
        fields.put(FIELD_ACTIVITY_SCOPE_KEY, nullToEmpty(message.getActivityScopeKey()));
        fields.put(FIELD_ROUTING_PARTITION_KEY, nullToEmpty(message.getRoutingPartitionKey()));
        fields.put(FIELD_PAYLOAD, serialize(message));
        RecordId recordId = stringRedisTemplate.opsForStream()
                .add(properties.getRedisStreamName(), fields);
        if (recordId == null) {
            throw new BusinessException("异步下单事件流写入失败");
        }
        return messageId;
    }

    private String serialize(AsyncCreateOrderMessage message) {
        try {
            return objectMapper.writeValueAsString(message);
        } catch (JsonProcessingException exception) {
            throw new BusinessException("异步下单事件序列化失败: " + exception.getOriginalMessage());
        }
    }

    private String nullToEmpty(String value) {
        return value == null ? "" : value;
    }
}
