package com.zewbby.smartticket.mq;

import com.zewbby.smartticket.config.MqConsumerProperties;
import com.zewbby.smartticket.enums.ConsumerExceptionTypeEnum;
import com.zewbby.smartticket.service.AsyncOrderPartitionService;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

class AsyncCreateOrderBatchDispatcherTest {

    @Test
    void disabledBatchDelegatesDirectly() {
        AsyncCreateOrderConsumer delegate = mock(AsyncCreateOrderConsumer.class);
        AsyncCreateOrderBatchDispatcher dispatcher = new AsyncCreateOrderBatchDispatcher(delegate);
        AsyncCreateOrderMessage message = message("REQ1");

        dispatcher.consume(message);

        verify(delegate).consume(message);
        verify(delegate, never()).consumeBatch(anyList());
    }

    @Test
    void enabledBatchDelegatesToBatchConsumer() {
        AsyncCreateOrderConsumer delegate = mock(AsyncCreateOrderConsumer.class);
        AsyncCreateOrderBatchDispatcher dispatcher = dispatcher(delegate, batchProperties(4, 1L));
        AsyncCreateOrderMessage message = message("REQ1");

        try {
            dispatcher.afterPropertiesSet();
            dispatcher.consume(message);
        } finally {
            dispatcher.destroy();
        }

        verify(delegate).consumeBatch(argThat(messages -> messages.size() == 1 && messages.get(0) == message));
        verify(delegate, never()).consume(any());
    }

    @Test
    void enabledBatchCanGroupConcurrentMessages() throws Exception {
        AsyncCreateOrderConsumer delegate = mock(AsyncCreateOrderConsumer.class);
        AsyncCreateOrderBatchDispatcher dispatcher = dispatcher(delegate, batchProperties(2, 200L));
        ExecutorService executorService = Executors.newFixedThreadPool(2);
        CountDownLatch startLatch = new CountDownLatch(1);
        AsyncCreateOrderMessage firstMessage = message("REQ1");
        AsyncCreateOrderMessage secondMessage = message("REQ2");

        try {
            dispatcher.afterPropertiesSet();
            Future<?> firstFuture = executorService.submit(() -> consumeAfterStart(dispatcher, firstMessage, startLatch));
            Future<?> secondFuture = executorService.submit(() -> consumeAfterStart(dispatcher, secondMessage, startLatch));

            startLatch.countDown();
            firstFuture.get(1, TimeUnit.SECONDS);
            secondFuture.get(1, TimeUnit.SECONDS);
        } finally {
            executorService.shutdownNow();
            dispatcher.destroy();
        }

        verify(delegate).consumeBatch(argThat(messages ->
                messages.size() == 2
                        && messages.contains(firstMessage)
                        && messages.contains(secondMessage)
        ));
        verify(delegate, never()).consume(any());
    }

    @Test
    void batchFailureFallsBackToSingleConsumer() {
        AsyncCreateOrderConsumer delegate = mock(AsyncCreateOrderConsumer.class);
        AsyncCreateOrderBatchDispatcher dispatcher = dispatcher(delegate, batchProperties(4, 1L));
        AsyncCreateOrderMessage message = message("REQ1");
        doThrow(new ConsumerRetryableException(
                ConsumerExceptionTypeEnum.TRANSIENT_SYSTEM_ERROR,
                "批处理失败",
                new IllegalStateException("failed")
        )).when(delegate).consumeBatch(anyList());

        try {
            dispatcher.afterPropertiesSet();
            dispatcher.consume(message);
        } finally {
            dispatcher.destroy();
        }

        verify(delegate).consumeBatch(argThat(messages -> messages.size() == 1 && messages.get(0) == message));
        verify(delegate).consume(message);
    }

    private AsyncCreateOrderBatchDispatcher dispatcher(AsyncCreateOrderConsumer delegate,
                                                       MqConsumerProperties properties) {
        return new AsyncCreateOrderBatchDispatcher(delegate, properties, new AsyncOrderPartitionService());
    }

    private MqConsumerProperties batchProperties(int batchSize, long maxWaitMillis) {
        MqConsumerProperties properties = new MqConsumerProperties();
        properties.setAsyncOrderBatchEnabled(true);
        properties.setAsyncQueueShardCount(1);
        properties.setAsyncOrderBatchSize(batchSize);
        properties.setAsyncOrderBatchMaxWaitMillis(maxWaitMillis);
        properties.setAsyncOrderBatchQueueCapacity(16);
        properties.setAsyncOrderBatchOfferTimeoutMillis(50L);
        return properties;
    }

    private AsyncCreateOrderMessage message(String requestId) {
        return new AsyncCreateOrderMessage(requestId, 1L, 1L, 1L, 2L, 1);
    }

    private void consumeAfterStart(AsyncCreateOrderBatchDispatcher dispatcher,
                                   AsyncCreateOrderMessage message,
                                   CountDownLatch startLatch) {
        try {
            startLatch.await();
            dispatcher.consume(message);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(exception);
        }
    }
}
