package com.example.myGithubAction.messaging;

import org.apache.kafka.common.KafkaException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.RejectedExecutionException;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class KafkaExecutionDispatcherTest {

    private KafkaTemplate<String, String> template;
    private KafkaExecutionDispatcher dispatcher;

    @BeforeEach
    void setUp() {
        template = mock(KafkaTemplate.class);
        ExecutionKafkaProperties properties = new ExecutionKafkaProperties();
        properties.setPublishTimeoutMs(200);
        dispatcher = new KafkaExecutionDispatcher(template, new ExecutionEventCodec(), properties);
    }

    @Test
    void dispatchPublishesKeyedByExecutionIdAndWaitsForAck() {
        when(template.send(eq("workflow.execution.requested"), eq("7"), anyString()))
            .thenReturn(CompletableFuture.completedFuture(mock(SendResult.class)));

        dispatcher.dispatch(7L);

        verify(template).send(eq("workflow.execution.requested"), eq("7"),
            argThat(payload -> payload.contains("\"executionId\":7")));
    }

    @Test
    void brokerFailureBecomesRejection() {
        when(template.send(anyString(), anyString(), anyString()))
            .thenReturn(CompletableFuture.failedFuture(new KafkaException("broker down")));

        assertThrows(RejectedExecutionException.class, () -> dispatcher.dispatch(7L));
    }

    @Test
    void missingAckWithinTimeoutBecomesRejection() {
        when(template.send(anyString(), anyString(), anyString())).thenReturn(new CompletableFuture<>());

        assertThrows(RejectedExecutionException.class, () -> dispatcher.dispatch(7L));
    }

    @Test
    void cancelPublishFailureIsSwallowed() {
        when(template.send(eq("workflow.execution.cancel"), eq("7"), anyString()))
            .thenReturn(CompletableFuture.failedFuture(new KafkaException("broker down")));

        assertDoesNotThrow(() -> dispatcher.requestCancel(7L));
    }
}
