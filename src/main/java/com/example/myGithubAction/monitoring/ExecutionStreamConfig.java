package com.example.myGithubAction.monitoring;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Live-monitor infrastructure. Carries {@code @EnableScheduling} itself: an API-only instance
 * ({@code app.kafka.consumer.enabled=false}) has no other scheduled bean that would enable it,
 * and the reconciler and keepalive must still run there.
 */
@Configuration
@EnableScheduling
public class ExecutionStreamConfig {

    /**
     * Writes SSE events to the sockets, so a slow reader never holds a request, Kafka listener or
     * scheduler thread. Bounded threads and queue; a rejected task closes that one stream.
     * Deliberately a plain {@link ExecutorService} rather than a Spring {@code TaskExecutor}, so
     * it does not compete with other executors in by-type lookups (@Async default, MVC async).
     */
    @Bean(name = "streamExecutor", destroyMethod = "shutdownNow")
    public ExecutorService streamExecutor(
            @Value("${app.stream.executor.threads:8}") int threads,
            @Value("${app.stream.executor.queue-capacity:10000}") int queueCapacity) {
        AtomicInteger n = new AtomicInteger();
        ThreadFactory factory = r -> {
            Thread t = new Thread(r, "sse-stream-" + n.incrementAndGet());
            t.setDaemon(true);
            return t;
        };
        ThreadPoolExecutor executor = new ThreadPoolExecutor(threads, threads, 60, TimeUnit.SECONDS,
                new LinkedBlockingQueue<>(queueCapacity), factory, new ThreadPoolExecutor.AbortPolicy());
        executor.allowCoreThreadTimeOut(true);
        return executor;
    }
}
