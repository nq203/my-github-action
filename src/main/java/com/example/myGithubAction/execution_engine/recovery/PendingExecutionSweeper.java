package com.example.myGithubAction.execution_engine.recovery;

import com.example.myGithubAction.messaging.ExecutionDispatcher;
import com.example.myGithubAction.workflow.repository.WorkFlowExecutionRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.concurrent.RejectedExecutionException;

/**
 * Publishes the START of PENDING executions again when it may have been lost (the process
 * died between committing the execution and publishing, or the message was never consumed).
 *
 * Re-publishing is safe: the claim is atomic, so a duplicate START is ignored. To keep a long
 * legitimate backlog from being multiplied, each execution is re-published at most once per
 * app.worker.pending-republish-after-ms, and only by the one sweeper that wins the update.
 */
@Slf4j
@Component
@ConditionalOnExecutionRunner
public class PendingExecutionSweeper {

    private final WorkFlowExecutionRepository executionRepository;
    private final ExecutionDispatcher executionDispatcher;
    private final WorkerProperties properties;

    public PendingExecutionSweeper(
            WorkFlowExecutionRepository executionRepository,
            ExecutionDispatcher executionDispatcher,
            WorkerProperties properties) {
        this.executionRepository = executionRepository;
        this.executionDispatcher = executionDispatcher;
        this.properties = properties;
    }

    @Scheduled(fixedDelayString = "${app.worker.sweep-interval-ms:30000}")
    public void sweep() {
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime cutoff = now.minus(Duration.ofMillis(properties.getPendingRepublishAfterMs()));

        try {
            for (Long executionId : executionRepository.findStalePendingIds(
                    cutoff, PageRequest.of(0, properties.getSweepBatchSize()))) {
                if (executionRepository.claimRedispatch(executionId, cutoff, now) == 0) {
                    continue; // another sweeper took it, or it was claimed / cancelled meanwhile
                }
                try {
                    executionDispatcher.dispatch(executionId);
                    log.info("Re-published START of PENDING execution {}", executionId);
                } catch (RejectedExecutionException e) {
                    log.warn("Could not re-publish START of execution {}, retrying later: {}",
                            executionId, e.getMessage());
                }
            }
        } catch (RuntimeException e) {
            log.warn("Pending sweep failed: {}", e.getMessage());
        }
    }
}
