package com.example.myGithubAction.execution_engine.recovery;

import com.example.myGithubAction.messaging.ExecutionEventPublisher;
import com.example.myGithubAction.workflow.repository.WorkFlowExecutionRepository;
import com.example.myGithubAction.workflow.repository.WorkFlowExecutionStepRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.LocalDateTime;

/**
 * Fails executions whose worker stopped heartbeating (crash, kill, network partition).
 *
 * Replaces the old "fail every RUNNING execution at startup", which killed healthy jobs of
 * other instances. Several instances may run this at once: each failure is a conditional
 * UPDATE that re-checks staleness, so a late heartbeat always wins and only one reaper acts.
 * Lost executions are not retried: their workspace is gone and shell steps need not be idempotent.
 */
@Slf4j
@Component
@ConditionalOnExecutionRunner
public class StaleExecutionReaper {

    private final WorkFlowExecutionRepository executionRepository;
    private final WorkFlowExecutionStepRepository stepRepository;
    private final WorkerProperties properties;
    private final ExecutionEventPublisher eventPublisher;

    public StaleExecutionReaper(
            WorkFlowExecutionRepository executionRepository,
            WorkFlowExecutionStepRepository stepRepository,
            WorkerProperties properties,
            ExecutionEventPublisher eventPublisher) {
        this.executionRepository = executionRepository;
        this.stepRepository = stepRepository;
        this.properties = properties;
        this.eventPublisher = eventPublisher;
    }

    @Scheduled(fixedDelayString = "${app.worker.reaper-interval-ms:15000}")
    public void reap() {
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime cutoff = now.minus(Duration.ofMillis(properties.getStaleAfterMs()));
        String message = "Worker lost: no heartbeat for over " + properties.getStaleAfterMs() / 1000 + "s";

        try {
            for (Long executionId : executionRepository.findStaleRunningIds(cutoff)) {
                if (executionRepository.failIfStale(executionId, cutoff, now, message) > 0) {
                    int stepsFailed = stepRepository.failRunningSteps(executionId, now, message);
                    if (stepsFailed > 0) {
                        eventPublisher.stepsOfExecutionChanged(executionId);
                    }
                    eventPublisher.executionChanged(executionId);
                    log.warn("Execution {} failed: {}", executionId, message);
                }
            }
        } catch (RuntimeException e) {
            log.warn("Reaper pass failed: {}", e.getMessage());
        }
    }
}
