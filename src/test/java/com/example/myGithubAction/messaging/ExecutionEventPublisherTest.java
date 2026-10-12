package com.example.myGithubAction.messaging;

import com.example.myGithubAction.common.ExecutionState;
import com.example.myGithubAction.workflow.entity.WorkFlowExecution;
import com.example.myGithubAction.workflow.entity.WorkFlowExecutionStep;
import com.example.myGithubAction.workflow.repository.WorkFlowExecutionRepository;
import com.example.myGithubAction.workflow.repository.WorkFlowExecutionStepRepository;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.common.KafkaException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ExecutionEventPublisherTest {

    private WorkFlowExecutionRepository executions;
    private WorkFlowExecutionStepRepository steps;
    private List<ExecutionEvent> published;
    private AbstractExecutionEventPublisher publisher;

    @BeforeEach
    void setUp() {
        executions = mock(WorkFlowExecutionRepository.class);
        steps = mock(WorkFlowExecutionStepRepository.class);
        published = new ArrayList<>();
        publisher = new AbstractExecutionEventPublisher(executions, steps) {
            @Override
            protected void publish(ExecutionEvent event) {
                published.add(event);
            }
        };
    }

    private WorkFlowExecution execution() {
        return WorkFlowExecution.builder().id(42L).status(ExecutionState.FAILED)
            .startedAt(LocalDateTime.of(2026, 1, 2, 3, 4, 5))
            .endedAt(LocalDateTime.of(2026, 1, 2, 3, 5, 5))
            .errorMessage("boom").build();
    }

    private WorkFlowExecutionStep step(Long id, int order) {
        return WorkFlowExecutionStep.builder().id(id).executionId(42L).stepOrder(order)
            .status(ExecutionState.SUCCESS).duration(1500L)
            .startedAt(LocalDateTime.of(2026, 1, 2, 3, 4, 5))
            .endedAt(LocalDateTime.of(2026, 1, 2, 3, 4, 6)).build();
    }

    @Test
    void executionChangedBuildsExecutionStatusEventFromDbRow() {
        when(executions.findById(42L)).thenReturn(Optional.of(execution()));

        publisher.executionChanged(42L);

        assertEquals(1, published.size());
        ExecutionEvent e = published.get(0);
        assertEquals(ExecutionEvent.Type.EXECUTION_STATUS, e.type());
        assertEquals(ExecutionEvent.VERSION, e.version());
        assertEquals(42L, e.executionId());
        assertNull(e.stepId());
        assertEquals("FAILED", e.status());
        assertEquals("2026-01-02T03:04:05", e.startedAt());
        assertEquals("2026-01-02T03:05:05", e.endedAt());
        assertEquals("boom", e.errorMessage());
    }

    @Test
    void stepChangedCarriesExecutionIdAndStatus() {
        when(steps.findById(5L)).thenReturn(Optional.of(step(5L, 1)));

        publisher.stepChanged(5L);

        ExecutionEvent e = published.get(0);
        assertEquals(ExecutionEvent.Type.STEP_STATUS, e.type());
        assertEquals(42L, e.executionId());
        assertEquals(5L, e.stepId());
        assertEquals("SUCCESS", e.status());
        assertEquals(1500L, e.duration());
    }

    @Test
    void stepsOfExecutionChangedPublishesOneEventPerStep() {
        when(steps.findByExecutionIdOrderByStepOrder(42L)).thenReturn(List.of(step(5L, 1), step(6L, 2)));

        publisher.stepsOfExecutionChanged(42L);

        assertEquals(2, published.size());
        assertEquals(List.of(5L, 6L), published.stream().map(ExecutionEvent::stepId).toList());
        assertTrue(published.stream().allMatch(e -> e.type() == ExecutionEvent.Type.STEP_STATUS));
    }

    @Test
    void logAppendedResolvesExecutionIdFromStep() {
        when(steps.findById(5L)).thenReturn(Optional.of(step(5L, 1)));

        publisher.logAppended(5L, 3, List.of("a", "b"));

        ExecutionEvent e = published.get(0);
        assertEquals(ExecutionEvent.Type.LOG, e.type());
        assertEquals(42L, e.executionId());
        assertEquals(5L, e.stepId());
        assertEquals(3, e.fromSeq());
        assertEquals(List.of("a", "b"), e.lines());
    }

    @Test
    void missingRowPublishesNothing() {
        when(executions.findById(anyLong())).thenReturn(Optional.empty());
        when(steps.findById(anyLong())).thenReturn(Optional.empty());

        publisher.executionChanged(1L);
        publisher.stepChanged(1L);
        publisher.logAppended(1L, 0, List.of("x"));

        assertTrue(published.isEmpty());
    }

    @Test
    void publishFailureIsSwallowed() {
        when(executions.findById(42L)).thenReturn(Optional.of(execution()));
        AbstractExecutionEventPublisher failing = new AbstractExecutionEventPublisher(executions, steps) {
            @Override
            protected void publish(ExecutionEvent event) {
                throw new IllegalStateException("boom");
            }
        };

        assertDoesNotThrow(() -> failing.executionChanged(42L));
    }

    private KafkaExecutionEventPublisher kafkaPublisher(KafkaTemplate<String, String> template) {
        return new KafkaExecutionEventPublisher(executions, steps, template,
            new ExecutionEventCodec(), new ExecutionKafkaProperties());
    }

    @Test
    void kafkaPublisherSendsToEventsTopicKeyedByExecutionId() {
        KafkaTemplate<String, String> template = mock(KafkaTemplate.class);
        when(template.send(anyString(), anyString(), anyString()))
            .thenReturn(CompletableFuture.completedFuture(mock(SendResult.class)));
        when(executions.findById(42L)).thenReturn(Optional.of(execution()));

        kafkaPublisher(template).executionChanged(42L);

        verify(template).send(eq("workflow.execution.events"), eq("42"),
            argThat(p -> p.contains("\"type\":\"EXECUTION_STATUS\"")));
    }

    @Test
    void kafkaSendThrowingDoesNotPropagate() {
        KafkaTemplate<String, String> template = mock(KafkaTemplate.class);
        when(template.send(anyString(), anyString(), anyString())).thenThrow(new KafkaException("down"));
        when(executions.findById(42L)).thenReturn(Optional.of(execution()));

        assertDoesNotThrow(() -> kafkaPublisher(template).executionChanged(42L));

        KafkaTemplate<String, String> failing = mock(KafkaTemplate.class);
        when(failing.send(anyString(), anyString(), anyString()))
            .thenReturn(CompletableFuture.failedFuture(new KafkaException("down")));
        assertDoesNotThrow(() -> kafkaPublisher(failing).executionChanged(42L));
    }

    @Test
    void eventSurvivesCodecRoundTrip() {
        ExecutionEvent original = new ExecutionEvent(ExecutionEvent.VERSION, ExecutionEvent.Type.LOG,
            42L, 5L, "RUNNING", "2026-01-02T03:04:05", "2026-01-02T03:05:05", 99L, "err", 7,
            List.of("l1", "l2"));
        ExecutionEventCodec codec = new ExecutionEventCodec();

        assertEquals(original, codec.fromJson(codec.toJson(original), ExecutionEvent.class));
    }

    @Test
    void eventsTopicHasSixPartitionsAndOneHourRetention() {
        NewTopic topic = new KafkaConfig().executionEventsTopic(new ExecutionKafkaProperties());

        assertEquals("workflow.execution.events", topic.name());
        assertEquals(6, topic.numPartitions());
        assertEquals("3600000", topic.configs().get("retention.ms"));
    }
}
