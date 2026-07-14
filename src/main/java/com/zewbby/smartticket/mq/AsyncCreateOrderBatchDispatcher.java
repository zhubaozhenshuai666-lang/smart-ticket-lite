package com.zewbby.smartticket.mq;

import com.zewbby.smartticket.config.MqConsumerProperties;
import com.zewbby.smartticket.enums.ConsumerExceptionTypeEnum;
import com.zewbby.smartticket.service.AsyncOrderPartitionService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

@Component
public class AsyncCreateOrderBatchDispatcher implements InitializingBean, DisposableBean {

    private static final Logger LOGGER = LoggerFactory.getLogger(AsyncCreateOrderBatchDispatcher.class);

    private final AsyncCreateOrderConsumer asyncCreateOrderConsumer;

    private final MqConsumerProperties mqConsumerProperties;

    private final AsyncOrderPartitionService asyncOrderPartitionService;

    private volatile boolean batchEnabled;

    private volatile boolean running;

    private List<BlockingQueue<BatchTask>> shardQueues = Collections.emptyList();

    private ExecutorService workerPool;

    public AsyncCreateOrderBatchDispatcher(AsyncCreateOrderConsumer asyncCreateOrderConsumer) {
        this(asyncCreateOrderConsumer, new MqConsumerProperties(), new AsyncOrderPartitionService());
    }

    @Autowired
    public AsyncCreateOrderBatchDispatcher(AsyncCreateOrderConsumer asyncCreateOrderConsumer,
                                           MqConsumerProperties mqConsumerProperties,
                                           AsyncOrderPartitionService asyncOrderPartitionService) {
        this.asyncCreateOrderConsumer = asyncCreateOrderConsumer;
        this.mqConsumerProperties = mqConsumerProperties;
        this.asyncOrderPartitionService = asyncOrderPartitionService;
    }

    @Override
    public void afterPropertiesSet() {
        batchEnabled = mqConsumerProperties.isAsyncOrderBatchEnabled();
        if (!batchEnabled) {
            return;
        }
        int workerCount = mqConsumerProperties.getAsyncOrderBatchWorkerCount();
        int queueCapacityPerShard = Math.max(1, mqConsumerProperties.getAsyncOrderBatchQueueCapacity() / workerCount);
        List<BlockingQueue<BatchTask>> queues = new ArrayList<>(workerCount);
        for (int shard = 0; shard < workerCount; shard++) {
            queues.add(new LinkedBlockingQueue<>(queueCapacityPerShard));
        }
        shardQueues = queues;
        running = true;
        workerPool = Executors.newFixedThreadPool(workerCount, new BatchWorkerThreadFactory());
        for (int shard = 0; shard < workerCount; shard++) {
            final int shardNo = shard;
            workerPool.submit(() -> runShardWorker(shardNo, queues.get(shardNo)));
        }
        LOGGER.info("Started async create order batch dispatcher, workerCount={}, batchSize={}, maxWaitMillis={}, queueCapacityPerWorker={}",
                workerCount,
                mqConsumerProperties.getAsyncOrderBatchSize(),
                mqConsumerProperties.getAsyncOrderBatchMaxWaitMillis(),
                queueCapacityPerShard);
    }

    public void consume(AsyncCreateOrderMessage message) {
        if (message == null) {
            return;
        }
        if (!batchEnabled) {
            asyncCreateOrderConsumer.consume(message);
            return;
        }
        BatchTask task = new BatchTask(message);
        BlockingQueue<BatchTask> queue = shardQueues.get(asyncOrderPartitionService.partition(message, shardQueues.size()));
        offerTask(queue, task);
        awaitTask(task);
    }

    @Override
    public void destroy() {
        running = false;
        for (BlockingQueue<BatchTask> queue : shardQueues) {
            BatchTask task;
            while ((task = queue.poll()) != null) {
                task.completeExceptionally(new IllegalStateException("异步创单批处理调度器已关闭"));
            }
        }
        if (workerPool != null) {
            workerPool.shutdownNow();
        }
    }

    private void offerTask(BlockingQueue<BatchTask> queue, BatchTask task) {
        try {
            boolean offered = queue.offer(
                    task,
                    mqConsumerProperties.getAsyncOrderBatchOfferTimeoutMillis(),
                    TimeUnit.MILLISECONDS
            );
            if (!offered) {
                throw new ConsumerRetryableException(
                        ConsumerExceptionTypeEnum.TRANSIENT_SYSTEM_ERROR,
                        "异步创单本地分片队列已满",
                        new IllegalStateException("async create order shard queue is full")
                );
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new ConsumerRetryableException(
                    ConsumerExceptionTypeEnum.TRANSIENT_SYSTEM_ERROR,
                    "异步创单本地分片队列入队被中断",
                    exception
            );
        }
    }

    private void awaitTask(BatchTask task) {
        try {
            task.future().get();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new ConsumerRetryableException(
                    ConsumerExceptionTypeEnum.TRANSIENT_SYSTEM_ERROR,
                    "异步创单批处理等待被中断",
                    exception
            );
        } catch (ExecutionException exception) {
            Throwable cause = exception.getCause();
            if (cause instanceof RuntimeException runtimeException) {
                throw runtimeException;
            }
            throw new ConsumerRetryableException(
                    ConsumerExceptionTypeEnum.UNKNOWN_ERROR,
                    "异步创单批处理执行失败",
                    exception
            );
        }
    }

    private void runShardWorker(int shardNo, BlockingQueue<BatchTask> queue) {
        while (running || !queue.isEmpty()) {
            try {
                BatchTask first = queue.poll(100, TimeUnit.MILLISECONDS);
                if (first == null) {
                    continue;
                }
                List<BatchTask> tasks = drainBatch(first, queue);
                processBatch(shardNo, tasks);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                if (!running) {
                    return;
                }
                LOGGER.warn("Async create order batch worker interrupted, shardNo={}", shardNo, exception);
            } catch (RuntimeException exception) {
                LOGGER.error("Async create order batch worker failed, shardNo={}", shardNo, exception);
            }
        }
    }

    private List<BatchTask> drainBatch(BatchTask first, BlockingQueue<BatchTask> queue) throws InterruptedException {
        int batchSize = mqConsumerProperties.getAsyncOrderBatchSize();
        long deadlineNanos = System.nanoTime()
                + TimeUnit.MILLISECONDS.toNanos(mqConsumerProperties.getAsyncOrderBatchMaxWaitMillis());
        List<BatchTask> tasks = new ArrayList<>(batchSize);
        tasks.add(first);
        while (tasks.size() < batchSize) {
            long remainingNanos = deadlineNanos - System.nanoTime();
            if (remainingNanos <= 0L) {
                break;
            }
            BatchTask next = queue.poll(remainingNanos, TimeUnit.NANOSECONDS);
            if (next == null) {
                break;
            }
            tasks.add(next);
        }
        return tasks;
    }

    private void processBatch(int shardNo, List<BatchTask> tasks) {
        List<AsyncCreateOrderMessage> messages = tasks.stream()
                .map(BatchTask::message)
                .toList();
        try {
            asyncCreateOrderConsumer.consumeBatch(messages);
            tasks.forEach(BatchTask::complete);
        } catch (RuntimeException batchException) {
            LOGGER.warn("Async create order batch failed, fallback to single consume, shardNo={}, batchSize={}",
                    shardNo, tasks.size(), batchException);
            fallbackToSingleConsume(tasks);
        }
    }

    private void fallbackToSingleConsume(List<BatchTask> tasks) {
        for (BatchTask task : tasks) {
            try {
                asyncCreateOrderConsumer.consume(task.message());
                task.complete();
            } catch (RuntimeException exception) {
                task.completeExceptionally(exception);
            }
        }
    }

    private record BatchTask(AsyncCreateOrderMessage message, CompletableFuture<Void> future) {

        private BatchTask(AsyncCreateOrderMessage message) {
            this(message, new CompletableFuture<>());
        }

        private void complete() {
            future.complete(null);
        }

        private void completeExceptionally(RuntimeException exception) {
            future.completeExceptionally(exception);
        }
    }

    private static class BatchWorkerThreadFactory implements ThreadFactory {

        private final AtomicInteger sequence = new AtomicInteger(1);

        @Override
        public Thread newThread(Runnable runnable) {
            Thread thread = new Thread(runnable);
            thread.setName("async-order-batch-worker-" + sequence.getAndIncrement());
            thread.setDaemon(true);
            return thread;
        }
    }
}
