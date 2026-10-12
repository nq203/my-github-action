package com.example.myGithubAction.messaging;

import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.common.KafkaException;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

import java.util.concurrent.ExecutionException;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/** Publishes START / CANCEL messages; the key is the executionId so a record is per-execution. */
@Slf4j
@Component
@ConditionalOnProperty(name = "app.kafka.enabled", havingValue = "true", matchIfMissing = true)
public class KafkaExecutionDispatcher implements ExecutionDispatcher {

    private final KafkaTemplate<String, String> kafkaTemplate;
    private final ExecutionEventCodec codec;
    private final ExecutionKafkaProperties properties;

    public KafkaExecutionDispatcher(
            KafkaTemplate<String, String> kafkaTemplate,
            ExecutionEventCodec codec,
            ExecutionKafkaProperties properties) {
        this.kafkaTemplate = kafkaTemplate;
        this.codec = codec;
        this.properties = properties;
    }

    /** Waits for the broker ack (acks=all): a returned dispatch means the START is durable. */
    @Override
    public void dispatch(Long executionId) {
        String payload = codec.toJson(ExecutionEvents.ExecutionRequested.of(executionId));
        try {
            kafkaTemplate.send(properties.getTopics().getRequested(), executionId.toString(), payload)
                    .get(properties.getPublishTimeoutMs(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RejectedExecutionException("interrupted while queueing execution", e);
        } catch (ExecutionException | TimeoutException | KafkaException e) {
            throw new RejectedExecutionException("could not queue execution: " + e.getMessage(), e);
        }
    }

    /** Fire-and-forget: a lost CANCEL is recovered by the worker's DB re-check. */
    @Override
    public void requestCancel(Long executionId) {
        String payload = codec.toJson(ExecutionEvents.ExecutionCancel.of(executionId));
        try {
            kafkaTemplate.send(properties.getTopics().getCancel(), executionId.toString(), payload)
                    .whenComplete((result, error) -> {
                        if (error != null) {
                            log.warn("Cancel signal for execution {} not published, relying on DB re-check: {}",
                                    executionId, error.getMessage());
                        }
                    });
        } catch (KafkaException e) {
            log.warn("Cancel signal for execution {} not published, relying on DB re-check: {}",
                    executionId, e.getMessage());
        }
    }
}
