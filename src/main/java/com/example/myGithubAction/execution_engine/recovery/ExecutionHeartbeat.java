package com.example.myGithubAction.execution_engine.recovery;

import com.example.myGithubAction.execution_engine.core.ExecutionCancellationRegistry;
import com.example.myGithubAction.execution_engine.core.WorkerIdentity;
import com.example.myGithubAction.workflow.repository.WorkFlowExecutionRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.Set;

/**
 * Tells the DB that the executions this process runs are alive, so the reaper of any
 * other instance does not declare them lost.
 *
 * It also detects the opposite: an execution that is no longer RUNNING under this worker
 * (cancelled, or already declared lost by a reaper) is stopped here, so a process the
 * system considers dead does not keep running.
 */
@Slf4j
@Component
@ConditionalOnExecutionRunner
public class ExecutionHeartbeat {

    private final ExecutionCancellationRegistry cancellationRegistry;
    private final WorkFlowExecutionRepository executionRepository;
    private final WorkerIdentity workerIdentity;

    public ExecutionHeartbeat(
            ExecutionCancellationRegistry cancellationRegistry,
            WorkFlowExecutionRepository executionRepository,
            WorkerIdentity workerIdentity) {
        this.cancellationRegistry = cancellationRegistry;
        this.executionRepository = executionRepository;
        this.workerIdentity = workerIdentity;
    }

    @Scheduled(fixedDelayString = "${app.worker.heartbeat-interval-ms:10000}")
    public void beat() {
        Set<Long> running = cancellationRegistry.activeExecutionIds();
        if (running.isEmpty()) {
            return;
        }

        try {
            int renewed = executionRepository.heartbeat(running, workerIdentity.id(), LocalDateTime.now());
            if (renewed == running.size()) {
                return;
            }
            Set<Long> stillOwned = new HashSet<>(executionRepository.findOwnedRunningIds(running, workerIdentity.id()));
            for (Long executionId : running) {
                if (!stillOwned.contains(executionId)) {
                    log.info("Execution {} is no longer RUNNING under worker {}, stopping it",
                            executionId, workerIdentity.id());
                    cancellationRegistry.cancel(executionId);
                }
            }
        } catch (RuntimeException e) {
            // DB unreachable: the next beat retries. If it stays down past the stale timeout,
            // the reaper rightly treats this worker as lost.
            log.warn("Heartbeat failed: {}", e.getMessage());
        }
    }
}
