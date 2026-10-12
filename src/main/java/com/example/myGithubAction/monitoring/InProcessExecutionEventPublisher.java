package com.example.myGithubAction.monitoring;

import com.example.myGithubAction.messaging.AbstractExecutionEventPublisher;
import com.example.myGithubAction.messaging.ExecutionEvent;
import com.example.myGithubAction.workflow.repository.WorkFlowExecutionRepository;
import com.example.myGithubAction.workflow.repository.WorkFlowExecutionStepRepository;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/** Kafka-disabled mode: events go straight to the local hub. */
@Component
@ConditionalOnProperty(name = "app.kafka.enabled", havingValue = "false")
public class InProcessExecutionEventPublisher extends AbstractExecutionEventPublisher {

    private final ExecutionEventHub hub;

    public InProcessExecutionEventPublisher(
            WorkFlowExecutionRepository executions, WorkFlowExecutionStepRepository steps, ExecutionEventHub hub) {
        super(executions, steps);
        this.hub = hub;
    }

    @Override
    protected void publish(ExecutionEvent event) {
        hub.accept(event);
    }
}
