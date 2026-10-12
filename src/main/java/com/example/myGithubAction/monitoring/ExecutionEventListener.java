package com.example.myGithubAction.monitoring;

import com.example.myGithubAction.messaging.ExecutionEvent;
import com.example.myGithubAction.messaging.ExecutionEventCodec;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * Feeds every API instance's hub from the events topic (own consumer group, latest offset).
 * Never rethrows: a bad or unprocessable event must not reach the retry/dead-letter handler;
 * the DB poll is the safety net.
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "app.kafka.enabled", matchIfMissing = true)
public class ExecutionEventListener {

    private final ExecutionEventHub hub;
    private final ExecutionEventCodec codec;

    public ExecutionEventListener(ExecutionEventHub hub, ExecutionEventCodec codec) {
        this.hub = hub;
        this.codec = codec;
    }

    @KafkaListener(
            topics = "${app.kafka.topics.events:workflow.execution.events}",
            groupId = "execution-events-#{T(java.util.UUID).randomUUID()}",
            properties = "auto.offset.reset=latest")
    public void onEvent(String payload) {
        try {
            ExecutionEvent event = codec.fromJson(payload, ExecutionEvent.class);
            if (event.executionId() == null || event.type() == null) {
                log.warn("Ignoring execution event without executionId/type");
                return;
            }
            hub.accept(event);
        } catch (Exception e) {
            log.warn("Ignoring execution event: {}", e.getMessage());
        }
    }
}
