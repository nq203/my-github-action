package com.example.myGithubAction.execution_engine.worker;

import com.example.myGithubAction.common.ExecutionState;
import com.example.myGithubAction.messaging.ExecutionEventCodec;
import com.example.myGithubAction.messaging.ExecutionKafkaProperties;
import com.example.myGithubAction.workflow.repository.WorkFlowExecutionRepository;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.common.TopicPartition;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.CommonErrorHandler;
import org.springframework.kafka.listener.ConsumerRecordRecoverer;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.util.backoff.BackOff;
import org.springframework.util.backoff.FixedBackOff;

import java.time.LocalDateTime;
import java.util.EnumSet;

/** Error handling for the worker's listener containers. */
@Slf4j
@Configuration
@ConditionalOnKafkaWorker
public class WorkerKafkaErrorHandlerConfig {

    private static final BackOff WORKER_BUSY_BACKOFF = new FixedBackOff(2000L, FixedBackOff.UNLIMITED_ATTEMPTS);

    /**
     * Applied by Spring Boot to every listener container.
     * - WorkerBusyException: retried every 2s without limit (backpressure, never dropped).
     * - Malformed payloads: straight to the dead-letter topic.
     * - Anything else: 3 retries, then dead-letter.
     * A START that ends up dead-lettered also marks its execution FAILED, so it is not
     * left PENDING forever; a failing CANCEL is only logged (the worker re-checks the DB).
     */
    @Bean
    public CommonErrorHandler executionKafkaErrorHandler(
            KafkaTemplate<String, String> kafkaTemplate,
            WorkFlowExecutionRepository executionRepository,
            ExecutionKafkaProperties properties) {
        DeadLetterPublishingRecoverer deadLetters = new DeadLetterPublishingRecoverer(kafkaTemplate,
                (record, ex) -> new TopicPartition(record.topic() + ".DLT", -1));

        ConsumerRecordRecoverer recoverer = (record, ex) -> {
            if (!record.topic().equals(properties.getTopics().getRequested())) {
                log.error("Dropping unprocessable message from {}: {}", record.topic(), ex.getMessage());
                return;
            }
            deadLetters.accept(record, ex);
            markFailed(executionRepository, record.key(), ex);
        };

        DefaultErrorHandler handler = new DefaultErrorHandler(recoverer, new FixedBackOff(1000L, 3));
        handler.setBackOffFunction((record, ex) ->
                ex instanceof WorkerBusyException || ex.getCause() instanceof WorkerBusyException
                        ? WORKER_BUSY_BACKOFF
                        : null);
        handler.addNotRetryableExceptions(ExecutionEventCodec.MalformedEventException.class);
        return handler;
    }

    private void markFailed(WorkFlowExecutionRepository executionRepository, Object key, Exception cause) {
        try {
            Long executionId = Long.valueOf(String.valueOf(key));
            executionRepository.transition(executionId, EnumSet.of(ExecutionState.PENDING), ExecutionState.FAILED,
                    LocalDateTime.now(), "Execution could not be dispatched: " + cause.getMessage());
        } catch (RuntimeException e) {
            log.error("Could not mark dead-lettered execution {} as FAILED", key, e);
        }
    }
}
