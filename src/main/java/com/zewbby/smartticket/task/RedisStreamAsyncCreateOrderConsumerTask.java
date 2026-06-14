package com.zewbby.smartticket.task;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zewbby.smartticket.config.AsyncOrderSubmitProperties;
import com.zewbby.smartticket.mq.AsyncCreateOrderMessage;
import com.zewbby.smartticket.mq.AsyncCreateOrderConsumer;
import com.zewbby.smartticket.service.impl.RedisStreamAsyncOrderMessagePublisher;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.connection.stream.Consumer;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.ReadOffset;
import org.springframework.data.redis.connection.stream.StreamOffset;
import org.springframework.data.redis.connection.stream.StreamReadOptions;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

@Component
@ConditionalOnProperty(prefix = "smart-ticket.async-order-submit", name = "publisher-mode", havingValue = "redis-stream")
public class RedisStreamAsyncCreateOrderConsumerTask {

    private static final Logger LOGGER = LoggerFactory.getLogger(RedisStreamAsyncCreateOrderConsumerTask.class);

    private final StringRedisTemplate stringRedisTemplate;

    private final ObjectMapper objectMapper;

    private final AsyncOrderSubmitProperties properties;

    private final AsyncCreateOrderConsumer asyncCreateOrderConsumer;

    private final ExecutorService workerExecutor;

    public RedisStreamAsyncCreateOrderConsumerTask(StringRedisTemplate stringRedisTemplate,
                                                   ObjectMapper objectMapper,
                                                   AsyncOrderSubmitProperties properties,
                                                   AsyncCreateOrderConsumer asyncCreateOrderConsumer) {
        this.stringRedisTemplate = stringRedisTemplate;
        this.objectMapper = objectMapper;
        this.properties = properties;
        this.asyncCreateOrderConsumer = asyncCreateOrderConsumer;
        this.workerExecutor = Executors.newFixedThreadPool(properties.getRedisStreamWorkerThreads());
    }

    @PostConstruct
    public void ensureConsumerGroup() {
        byte[] streamKey = properties.getRedisStreamName().getBytes(StandardCharsets.UTF_8);
        try {
            stringRedisTemplate.execute((RedisCallback<Object>) connection -> {
                connection.streamCommands().xGroupCreate(
                        streamKey,
                        properties.getRedisStreamConsumerGroup(),
                        ReadOffset.latest(),
                        true
                );
                return null;
            });
        } catch (DataAccessException exception) {
            if (exception.getMessage() == null || !exception.getMessage().contains("BUSYGROUP")) {
                throw exception;
            }
        }
    }

    @Scheduled(fixedDelayString = "#{@asyncOrderSubmitProperties.redisStreamPollFixedDelayMillis}")
    public void poll() {
        List<MapRecord<String, Object, Object>> records = stringRedisTemplate.opsForStream().read(
                Consumer.from(properties.getRedisStreamConsumerGroup(), properties.getRedisStreamConsumerName()),
                StreamReadOptions.empty()
                        .count(properties.getRedisStreamReadBatchSize())
                        .block(Duration.ofMillis(properties.getRedisStreamBlockMillis())),
                StreamOffset.create(properties.getRedisStreamName(), ReadOffset.lastConsumed())
        );
        if (records == null || records.isEmpty()) {
            return;
        }
        if (properties.getRedisStreamWorkerThreads() <= 1) {
            for (MapRecord<String, Object, Object> record : records) {
                consume(record);
            }
            return;
        }
        for (MapRecord<String, Object, Object> record : records) {
            workerExecutor.submit(() -> consume(record));
        }
    }

    @PreDestroy
    public void shutdown() {
        workerExecutor.shutdown();
        try {
            if (!workerExecutor.awaitTermination(5, TimeUnit.SECONDS)) {
                workerExecutor.shutdownNow();
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            workerExecutor.shutdownNow();
        }
    }

    private void consume(MapRecord<String, Object, Object> record) {
        try {
            AsyncCreateOrderMessage message = deserialize(record.getValue());
            if (message == null) {
                acknowledge(record);
                return;
            }
            asyncCreateOrderConsumer.consume(message);
            acknowledge(record);
        } catch (RuntimeException exception) {
            LOGGER.warn("Redis stream async order consume failed, stream={}, recordId={}",
                    properties.getRedisStreamName(), record.getId(), exception);
        }
    }

    private AsyncCreateOrderMessage deserialize(Map<Object, Object> fields) {
        Object payload = fields.get(RedisStreamAsyncOrderMessagePublisher.FIELD_PAYLOAD);
        if (payload == null || payload.toString().isBlank()) {
            LOGGER.warn("Redis stream async order payload is empty, stream={}", properties.getRedisStreamName());
            return null;
        }
        try {
            return objectMapper.readValue(payload.toString(), AsyncCreateOrderMessage.class);
        } catch (JsonProcessingException exception) {
            LOGGER.warn("Redis stream async order payload cannot be parsed, stream={}, payload={}",
                    properties.getRedisStreamName(), payload);
            return null;
        }
    }

    private void acknowledge(MapRecord<String, Object, Object> record) {
        stringRedisTemplate.opsForStream()
                .acknowledge(properties.getRedisStreamName(), properties.getRedisStreamConsumerGroup(), record.getId());
    }
}
