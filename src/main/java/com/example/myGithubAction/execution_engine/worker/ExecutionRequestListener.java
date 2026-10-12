package com.example.myGithubAction.execution_engine.worker;

import com.example.myGithubAction.messaging.ExecutionEventCodec;
import com.example.myGithubAction.messaging.ExecutionEvents;
import com.example.myGithubAction.execution_engine.core.ExecutionEngine;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * Worker side of dispatch: START messages, one worker per message (consumer group).
 *
 * The listener never runs the execution itself: it reserves a slot, claims the execution
 * (atomic PENDING → RUNNING), hands it to the workflow executor and returns, so the
 * consumer keeps polling while jobs run for minutes (no max.poll.interval rebalances).
 * The offset is committed only after the claim, so a crash before it redelivers the message.
 */
@Slf4j
@Component
@ConditionalOnKafkaWorker
public class ExecutionRequestListener {

    private final ExecutionEngine executionEngine;
    private final WorkerCapacity capacity;
    private final ExecutionEventCodec codec;

    public ExecutionRequestListener(
            ExecutionEngine executionEngine, WorkerCapacity capacity, ExecutionEventCodec codec) {
        this.executionEngine = executionEngine;
        this.capacity = capacity;
        this.codec = codec;
    }

    @KafkaListener(
            topics = "${app.kafka.topics.requested:workflow.execution.requested}",
            groupId = "${app.kafka.worker.group-id:execution-workers}")
    public void onRequested(String payload) {
        Long executionId = codec.fromJson(payload, ExecutionEvents.ExecutionRequested.class).executionId();

        // No free slot: fail the record so the error handler retries it later (backpressure).
        if (!capacity.tryAcquire()) {
            throw new WorkerBusyException();
        }

        boolean handedOver = false;
        try {
            if (!executionEngine.claim(executionId)) {
                // Cancelled while PENDING, or a duplicate / already claimed by another worker.
                log.info("Execution {} not claimed (cancelled or already taken), skipping", executionId);
                return;
            }

            try {
                executionEngine.runClaimed(executionId).whenComplete((result, error) -> {
                    capacity.release();
                    if (error != null) {
                        log.error("Execution {} crashed unexpectedly", executionId, error);
                        executionEngine.failClaimed(executionId, "Worker error: " + error.getMessage());
                    }
                });
                handedOver = true;
            } catch (RuntimeException e) {
                // We own the claim but could not even start: do not leave it RUNNING forever.
                log.error("Execution {} claimed but could not be started", executionId, e);
                executionEngine.failClaimed(executionId, "Worker could not start execution: " + e.getMessage());
            }
        } finally {
            if (!handedOver) {
                capacity.release();
            }
        }
    }
}
