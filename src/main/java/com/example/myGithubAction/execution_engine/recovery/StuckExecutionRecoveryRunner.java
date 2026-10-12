package com.example.myGithubAction.execution_engine.recovery;

import com.example.myGithubAction.common.ExecutionState;
import com.example.myGithubAction.execution_engine.core.WorkerIdentity;
import com.example.myGithubAction.execution_engine.core.WorkspaceManager;
import com.example.myGithubAction.messaging.ExecutionEventPublisher;
import com.example.myGithubAction.workflow.repository.WorkFlowExecutionRepository;
import com.example.myGithubAction.workflow.repository.WorkFlowExecutionStepRepository;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.EnumSet;
import java.util.Set;

/**
 * Fails, at startup, the executions left RUNNING by the previous incarnation of THIS worker.
 *
 * Only possible when the worker id is stable across restarts (app.worker.id): the same id
 * means the previous process is certainly dead. Without a stable id nothing here can tell
 * its own leftovers from another live worker's jobs, so it does nothing and the
 * {@link StaleExecutionReaper} fails them once their heartbeat times out. It must never
 * touch executions owned by other workers.
 */
@Component
@ConditionalOnExecutionRunner
public class StuckExecutionRecoveryRunner implements CommandLineRunner {

    private static final String INTERRUPTED_MESSAGE = "Interrupted by application restart";
    private static final Set<ExecutionState> RUNNING = EnumSet.of(ExecutionState.RUNNING);

    private final WorkFlowExecutionRepository executionRepository;
    private final WorkFlowExecutionStepRepository stepRepository;
    private final WorkspaceManager workspaceManager;
    private final WorkerIdentity workerIdentity;
    private final ExecutionEventPublisher eventPublisher;

    public StuckExecutionRecoveryRunner(
            WorkFlowExecutionRepository executionRepository,
            WorkFlowExecutionStepRepository stepRepository,
            WorkspaceManager workspaceManager,
            WorkerIdentity workerIdentity,
            ExecutionEventPublisher eventPublisher) {
        this.executionRepository = executionRepository;
        this.stepRepository = stepRepository;
        this.workspaceManager = workspaceManager;
        this.workerIdentity = workerIdentity;
        this.eventPublisher = eventPublisher;
    }

    @Override
    public void run(String... args) {
        if (!workerIdentity.isStable()) {
            return;
        }

        LocalDateTime now = LocalDateTime.now();
        for (Long executionId : executionRepository.findRunningIdsByWorker(workerIdentity.id())) {
            workspaceManager.deleteWorkspace(executionId);
            int executionUpdated = executionRepository
                .transition(executionId, RUNNING, ExecutionState.FAILED, now, INTERRUPTED_MESSAGE);
            int stepsFailed = stepRepository.failRunningSteps(executionId, now, INTERRUPTED_MESSAGE);
            if (stepsFailed > 0) {
                eventPublisher.stepsOfExecutionChanged(executionId);
            }
            if (executionUpdated > 0) {
                eventPublisher.executionChanged(executionId);
            }
        }
    }
}
