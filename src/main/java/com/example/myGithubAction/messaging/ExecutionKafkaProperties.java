package com.example.myGithubAction.messaging;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Data
@ConfigurationProperties(prefix = "app.kafka")
public class ExecutionKafkaProperties {

    private Topics topics = new Topics();
    private Worker worker = new Worker();
    /** How long dispatch waits for the broker to acknowledge a START message. */
    private long publishTimeoutMs = 5000;

    @Data
    public static class Topics {
        /** START requests, key = executionId, consumed by exactly one worker. */
        private String requested = "workflow.execution.requested";
        /** CANCEL signals, key = executionId, consumed by every worker. */
        private String cancel = "workflow.execution.cancel";
        /** Live-monitor status/log events, key = executionId, consumed by every API instance. */
        private String events = "workflow.execution.events";
        private int partitions = 6;
        private short replicationFactor = 1;

        public String deadLetter() {
            return requested + ".DLT";
        }
    }

    @Data
    public static class Worker {
        private String groupId = "execution-workers";
        /** Executions this instance runs at once; beyond it START messages wait on the topic. */
        private int maxConcurrentExecutions = 4;
        /** How often a running execution is checked against the DB for a missed CANCEL. */
        private long cancelPollMs = 5000;
    }
}
