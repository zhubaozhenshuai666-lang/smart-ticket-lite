package com.zewbby.smartticket.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zewbby.smartticket.config.AsyncOrderSubmitProperties;
import com.zewbby.smartticket.mq.AsyncCreateOrderMessage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.connection.stream.RecordId;
import org.springframework.data.redis.core.StreamOperations;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RedisStreamAsyncOrderMessagePublisherTest {

    @Mock
    private StringRedisTemplate stringRedisTemplate;

    @Mock
    private StreamOperations<String, Object, Object> streamOperations;

    private RedisStreamAsyncOrderMessagePublisher publisher;

    @BeforeEach
    void setUp() {
        AsyncOrderSubmitProperties properties = new AsyncOrderSubmitProperties();
        properties.setPublisherMode(AsyncOrderSubmitProperties.PUBLISHER_MODE_REDIS_STREAM);
        properties.setRedisStreamName("stream:test");
        when(stringRedisTemplate.opsForStream()).thenReturn(streamOperations);
        publisher = new RedisStreamAsyncOrderMessagePublisher(stringRedisTemplate, new ObjectMapper(), properties);
    }

    @Test
    void publishWritesAsyncOrderMessageToRedisStream() {
        when(streamOperations.add(eq("stream:test"), anyMap())).thenReturn(RecordId.of("1-0"));
        AsyncCreateOrderMessage message = new AsyncCreateOrderMessage("REQ1", 1L, 2L, 3L, 4L, 1);
        message.setActivityScopeKey("show:2:session:3");
        message.setRoutingPartitionKey("show:2:session:3:ticket:4");

        String messageId = publisher.publish("MSG1", message);

        ArgumentCaptor<Map<String, String>> fieldsCaptor = ArgumentCaptor.forClass(Map.class);
        verify(streamOperations).add(eq("stream:test"), fieldsCaptor.capture());
        assertThat(messageId).isEqualTo("MSG1");
        assertThat(fieldsCaptor.getValue())
                .containsEntry(RedisStreamAsyncOrderMessagePublisher.FIELD_MESSAGE_ID, "MSG1")
                .containsEntry(RedisStreamAsyncOrderMessagePublisher.FIELD_ACTIVITY_SCOPE_KEY, "show:2:session:3")
                .containsEntry(RedisStreamAsyncOrderMessagePublisher.FIELD_ROUTING_PARTITION_KEY, "show:2:session:3:ticket:4");
        assertThat(fieldsCaptor.getValue().get(RedisStreamAsyncOrderMessagePublisher.FIELD_PAYLOAD))
                .contains("\"requestId\":\"REQ1\"")
                .contains("\"messageId\":\"MSG1\"");
    }
}
