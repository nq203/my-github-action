package com.example.myGithubAction.execution_engine.core;

import com.example.myGithubAction.common.ExecutionState;
import com.example.myGithubAction.workflow.entity.WorkFlowExecution;
import com.example.myGithubAction.workflow.entity.WorkFlowExecutionStep;
import com.example.myGithubAction.workflow.repository.WorkFlowExecutionRepository;
import com.example.myGithubAction.workflow.repository.WorkFlowExecutionStepRepository;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;

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

    private final WorkFlowExecutionRepository executionRepository;
    private final WorkFlowExecutionStepRepository stepRepository;

    public StuckExecutionRecoveryRunner(
            WorkFlowExecutionRepository executionRepository,
            WorkFlowExecutionStepRepository stepRepository) {
        this.executionRepository = executionRepository;
        this.stepRepository = stepRepository;
    }

    @Override
    public void run(String... args) {
        LocalDateTime now = LocalDateTime.now();

        List<WorkFlowExecution> stuckExecutions = executionRepository.findByStatus(ExecutionState.RUNNING);
        for (WorkFlowExecution execution : stuckExecutions) {
            execution.setStatus(ExecutionState.FAILED);
            execution.setErrorMessage(INTERRUPTED_MESSAGE);
            execution.setEndedAt(now);
        }
        executionRepository.saveAll(stuckExecutions);

        List<WorkFlowExecutionStep> stuckSteps = stepRepository.findByStatus(ExecutionState.RUNNING);
        for (WorkFlowExecutionStep step : stuckSteps) {
            step.setStatus(ExecutionState.FAILED);
            step.setErrorMessage(INTERRUPTED_MESSAGE);
            step.setEndedAt(now);
        }
        stepRepository.saveAll(stuckSteps);
    }
}
