package com.example.myGithubAction.execution_engine.worker;

import com.example.myGithubAction.messaging.ExecutionKafkaProperties;
import org.springframework.stereotype.Component;

import java.util.concurrent.Semaphore;

/** Bounds how many executions this worker runs concurrently. */
@Component
@ConditionalOnKafkaWorker
public class WorkerCapacity {

    private final Semaphore permits;

    public WorkerCapacity(ExecutionKafkaProperties properties) {
        this.permits = new Semaphore(properties.getWorker().getMaxConcurrentExecutions());
    }

    public boolean tryAcquire() {
        return permits.tryAcquire();
    }

    public void release() {
        permits.release();
    }

    public int available() {
        return permits.availablePermits();
    }
}
