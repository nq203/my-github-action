package com.example.myGithubAction.execution_engine.worker;

import com.example.myGithubAction.common.ExecutionState;
import com.example.myGithubAction.execution_engine.core.ExecutionCancellationRegistry;
import com.example.myGithubAction.workflow.repository.WorkFlowExecutionRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.Set;

/**
 * Safety net for lost CANCEL messages (consumer rebalance, broker hiccup): periodically
 * checks the executions running here against the DB, which is the source of truth.
 */
@Slf4j
@Component
@EnableScheduling
@ConditionalOnKafkaWorker
public class CancelWatcher {

    private final ExecutionCancellationRegistry cancellationRegistry;
    private final WorkFlowExecutionRepository executionRepository;

    public CancelWatcher(
            ExecutionCancellationRegistry cancellationRegistry, WorkFlowExecutionRepository executionRepository) {
        this.cancellationRegistry = cancellationRegistry;
        this.executionRepository = executionRepository;
    }

    @Scheduled(fixedDelayString = "${app.kafka.worker.cancel-poll-ms:5000}")
    public void cancelExecutionsCancelledInDb() {
        Set<Long> running = cancellationRegistry.activeExecutionIds();
        if (running.isEmpty()) {
            return;
        }
        executionRepository.findAllById(running).stream()
                .filter(execution -> execution.getStatus() == ExecutionState.CANCELLED)
                .forEach(execution -> {
                    log.info("Execution {} is CANCELLED in DB, stopping it", execution.getId());
                    cancellationRegistry.cancel(execution.getId());
                });
    }
}
