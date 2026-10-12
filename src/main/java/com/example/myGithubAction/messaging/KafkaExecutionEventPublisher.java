package com.example.myGithubAction.messaging;

import com.example.myGithubAction.workflow.repository.WorkFlowExecutionRepository;
import com.example.myGithubAction.workflow.repository.WorkFlowExecutionStepRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

/** Fire-and-forget publish to the events topic, keyed by executionId; a failure only logs a warning. */
@Slf4j
@Component
@ConditionalOnProperty(name = "app.kafka.enabled", havingValue = "true", matchIfMissing = true)
public class KafkaExecutionEventPublisher extends AbstractExecutionEventPublisher {

    private final KafkaTemplate<String, String> kafkaTemplate;
    private final ExecutionEventCodec codec;
    private final ExecutionKafkaProperties properties;

    public KafkaExecutionEventPublisher(
            WorkFlowExecutionRepository executions,
            WorkFlowExecutionStepRepository steps,
            KafkaTemplate<String, String> kafkaTemplate,
            ExecutionEventCodec codec,
            ExecutionKafkaProperties properties) {
        super(executions, steps);
        this.kafkaTemplate = kafkaTemplate;
        this.codec = codec;
        this.properties = properties;
    }

    @Override
    protected void publish(ExecutionEvent event) {
        kafkaTemplate.send(properties.getTopics().getEvents(), event.executionId().toString(), codec.toJson(event))
                .whenComplete((result, error) -> {
                    if (error != null) {
                        log.warn("Execution event {} for execution {} not published: {}",
                                event.type(), event.executionId(), error.getMessage());
                    }
                });
    }
}
