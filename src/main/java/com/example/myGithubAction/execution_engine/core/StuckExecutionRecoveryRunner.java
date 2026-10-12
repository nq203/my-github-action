package com.example.myGithubAction.execution_engine.core;

import com.example.myGithubAction.common.ExecutionState;
import com.example.myGithubAction.workflow.entity.WorkFlowExecution;
import com.example.myGithubAction.workflow.entity.WorkFlowExecutionStep;
import com.example.myGithubAction.workflow.repository.WorkFlowExecutionRepository;
import com.example.myGithubAction.workflow.repository.WorkFlowExecutionStepRepository;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/**
 * Marks executions/steps left in RUNNING as FAILED on application startup.
 *
 * A RUNNING row means the previous process died mid-execution: there is no
 * persisted execution state (no distributed workers yet), so RUNNING can
 * never legitimately survive a restart. PENDING rows are left untouched -
 * they never started, so there's nothing to clean up.
 */
@Component
public class StuckExecutionRecoveryRunner implements CommandLineRunner {

    private static final String INTERRUPTED_MESSAGE = "Interrupted by application restart";
    private static final Set<ExecutionState> RUNNING = EnumSet.of(ExecutionState.RUNNING);

    private final WorkFlowExecutionRepository executionRepository;
    private final WorkFlowExecutionStepRepository stepRepository;
    private final WorkspaceManager workspaceManager;

    public StuckExecutionRecoveryRunner(
            WorkFlowExecutionRepository executionRepository,
            WorkFlowExecutionStepRepository stepRepository,
            WorkspaceManager workspaceManager) {
        this.executionRepository = executionRepository;
        this.stepRepository = stepRepository;
        this.workspaceManager = workspaceManager;
    }

    @Override
    public void run(String... args) {
        LocalDateTime now = LocalDateTime.now();

        // Compare-and-set per row: a row that left RUNNING in the meantime is skipped.
        List<WorkFlowExecution> stuckExecutions = executionRepository.findByStatus(ExecutionState.RUNNING);
        for (WorkFlowExecution execution : stuckExecutions) {
            workspaceManager.deleteWorkspace(execution.getId());
            executionRepository.transition(
                execution.getId(), RUNNING, ExecutionState.FAILED, now, INTERRUPTED_MESSAGE);
        }

        List<WorkFlowExecutionStep> stuckSteps = stepRepository.findByStatus(ExecutionState.RUNNING);
        for (WorkFlowExecutionStep step : stuckSteps) {
            stepRepository.transition(
                step.getId(), RUNNING, ExecutionState.FAILED, now, null, INTERRUPTED_MESSAGE);
        }
    }
}
