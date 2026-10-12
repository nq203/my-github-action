package com.example.myGithubAction.execution_engine.worker;

import com.example.myGithubAction.messaging.ExecutionEventCodec;
import com.example.myGithubAction.messaging.ExecutionEvents;
import com.example.myGithubAction.execution_engine.core.ExecutionCancellationRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * Receives every CANCEL: each instance has its own consumer group, so the worker that
 * happens to run the execution always gets it, whichever partition owner it is.
 * Instances that are not running it ignore the message (registry no-op).
 */
@Slf4j
@Component
@ConditionalOnKafkaWorker
public class ExecutionCancelListener {

    private final ExecutionCancellationRegistry cancellationRegistry;
    private final ExecutionEventCodec codec;

    public ExecutionCancelListener(ExecutionCancellationRegistry cancellationRegistry, ExecutionEventCodec codec) {
        this.cancellationRegistry = cancellationRegistry;
        this.codec = codec;
    }

    @KafkaListener(
            topics = "${app.kafka.topics.cancel:workflow.execution.cancel}",
            groupId = "execution-cancel-#{T(java.util.UUID).randomUUID()}",
            properties = "auto.offset.reset=latest")
    public void onCancel(String payload) {
        Long executionId = codec.fromJson(payload, ExecutionEvents.ExecutionCancel.class).executionId();
        cancellationRegistry.cancel(executionId);
    }
}
