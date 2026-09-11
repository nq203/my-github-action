package com.example.myGithubAction.execution_engine.executor;

import com.example.myGithubAction.workflow.entity.WorkFlowExecutionStep;
import com.example.myGithubAction.workflow.entity.Step;
import com.example.myGithubAction.workflow.repository.StepRepository;
import com.example.myGithubAction.execution_engine.dto.StepExecutionResult;
import com.example.myGithubAction.execution_engine.exception.StepExecutionException;
import com.example.myGithubAction.execution_engine.logging.LogManager;
import com.example.myGithubAction.execution_engine.executor.CommandValidator.CommandValidationException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * Executes a single workflow step.
 *
 * Responsible for:
 * - Loading step definition
 * - Calling ShellCommandRunner
 * - Capturing output
 * - Logging results
 * - Handling errors
 */
@Component
public class StepExecutor {

    private static final long DEFAULT_TIMEOUT_MS = 600000; // 10 minutes

    private final ShellCommandRunner commandRunner;
    private final LogManager logManager;
    private final StepRepository stepRepository;
    private final CommandValidator commandValidator;

    @Autowired
    public StepExecutor(
            ShellCommandRunner commandRunner,
            LogManager logManager,
            StepRepository stepRepository,
            CommandValidator commandValidator) {
        this.commandRunner = commandRunner;
        this.logManager = logManager;
        this.stepRepository = stepRepository;
        this.commandValidator = commandValidator;
    }

    /**
     * Executes a single step.
     *
     * Loads step definition, executes the command, captures output.
     *
     * @param step the execution step
     * @return execution result with output and status
     * @throws StepExecutionException if step fails to execute
     */
    public StepExecutionResult execute(WorkFlowExecutionStep step) throws StepExecutionException {
        try {
            // 1. Load step definition from repository
            Step stepDefinition = stepRepository.findById(step.getStepId())
                .orElseThrow(() -> new StepExecutionException(
                    "Step definition not found for stepId: " + step.getStepId()));

            String command = stepDefinition.getCommand();

            // Validate command is not null or empty
            if (command == null || command.trim().isEmpty()) {
                throw new StepExecutionException("Step command is null or empty");
            }

            // 2. Log: "Executing: <command>"
            logManager.appendLog(step.getId(), "Executing: " + command);

            // 3. Validate command (security check)
            try {
                commandValidator.validateCommand(command);
            } catch (CommandValidationException e) {
                logManager.appendLog(step.getId(), "[BLOCKED] " + e.getMessage());
                throw new StepExecutionException("Command validation failed: " + e.getMessage(), e);
            }

            // 4. Call commandRunner.execute()
            StepExecutionResult result = commandRunner.execute(command, DEFAULT_TIMEOUT_MS);

            // 5. Log results
            if (result.isSuccess()) {
                logManager.appendLog(step.getId(), "[SUCCESS] Exit code: " + result.getExitCode());
            } else {
                logManager.appendLog(step.getId(), "[FAILED] Exit code: " + result.getExitCode());
            }

            // 6. Return StepExecutionResult
            return result;

        } catch (StepExecutionException e) {
            // Re-throw StepExecutionException as-is
            throw e;
        } catch (Exception e) {
            // Wrap unexpected exceptions
            throw new StepExecutionException("Unexpected error executing step: " + e.getMessage(), e);
        }
    }
}
