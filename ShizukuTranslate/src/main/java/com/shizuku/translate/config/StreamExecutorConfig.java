package com.shizuku.translate.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.ThreadPoolExecutor;

@Configuration
public class StreamExecutorConfig {

    @Bean(name = "translationStreamExecutor")
    public ThreadPoolTaskExecutor translationStreamExecutor(
            @Value("${app.stream.core-pool-size}") int corePoolSize,
            @Value("${app.stream.max-pool-size}") int maxPoolSize,
            @Value("${app.stream.queue-capacity}") int queueCapacity) {
        if (corePoolSize < 1) {
            throw new IllegalArgumentException("app.stream.core-pool-size must be at least 1");
        }
        if (maxPoolSize < corePoolSize) {
            throw new IllegalArgumentException("app.stream.max-pool-size must be at least core-pool-size");
        }
        if (queueCapacity < 0) {
            throw new IllegalArgumentException("app.stream.queue-capacity must not be negative");
        }
        if (maxPoolSize > 256 || queueCapacity > 10_000) {
            throw new IllegalArgumentException("stream executor limits are too large");
        }

        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setThreadNamePrefix("translate-stream-");
        executor.setCorePoolSize(corePoolSize);
        executor.setMaxPoolSize(maxPoolSize);
        executor.setQueueCapacity(queueCapacity);
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.AbortPolicy());
        executor.setWaitForTasksToCompleteOnShutdown(false);
        executor.initialize();
        return executor;
    }
}
