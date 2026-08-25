package com.shizuku.translate.config;

import org.junit.jupiter.api.Test;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StreamExecutorConfigTest {

    @Test
    void rejectsWhenWorkerAndQueueAreFull() throws Exception {
        ThreadPoolTaskExecutor executor = executor(1, 1, 1);
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        try {
            executor.execute(() -> {
                started.countDown();
                await(release);
            });
            assertTrue(started.await(2, TimeUnit.SECONDS));
            executor.execute(() -> await(release));

            assertThrows(RejectedExecutionException.class,
                    () -> executor.execute(() -> { }));
        } finally {
            release.countDown();
            executor.shutdown();
        }
    }

    @Test
    void shutdownDoesNotWaitForRunningTranslationTask() throws Exception {
        ThreadPoolTaskExecutor executor = executor(1, 1, 0);
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        executor.execute(() -> {
            started.countDown();
            await(release);
        });
        assertTrue(started.await(2, TimeUnit.SECONDS));

        executor.shutdown();
        assertTrue(executor.getThreadPoolExecutor().isShutdown());
        release.countDown();
    }

    private ThreadPoolTaskExecutor executor(int core, int max, int queue) {
        return new StreamExecutorConfig().translationStreamExecutor(core, max, queue);
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await(5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
