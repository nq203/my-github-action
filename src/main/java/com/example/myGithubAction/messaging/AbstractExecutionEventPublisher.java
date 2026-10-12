package com.example.myGithubAction.messaging;

import com.example.myGithubAction.workflow.entity.WorkFlowExecution;
import com.example.myGithubAction.workflow.entity.WorkFlowExecutionStep;
import com.example.myGithubAction.workflow.repository.WorkFlowExecutionRepository;
import com.example.myGithubAction.workflow.repository.WorkFlowExecutionStepRepository;
import lombok.extern.slf4j.Slf4j;

import java.time.LocalDateTime;
import java.util.List;

/** Builds events from the current DB rows; transport is left to {@link #publish}. Never throws. */
@Slf4j
public abstract class AbstractExecutionEventPublisher implements ExecutionEventPublisher {

    private final WorkFlowExecutionRepository executions;
    private final WorkFlowExecutionStepRepository steps;

    protected AbstractExecutionEventPublisher(
            WorkFlowExecutionRepository executions, WorkFlowExecutionStepRepository steps) {
        this.executions = executions;
        this.steps = steps;
    }

    protected abstract void publish(ExecutionEvent event);

    @Override
    public void executionChanged(Long executionId) {
        safely("executionChanged " + executionId, () ->
                executions.findById(executionId).ifPresent(e -> publish(executionEvent(e))));
    }

    @Override
    public void stepChanged(Long stepId) {
        safely("stepChanged " + stepId, () ->
                steps.findById(stepId).ifPresent(s -> publish(stepEvent(s))));
    }

    @Override
    public void stepsOfExecutionChanged(Long executionId) {
        safely("stepsOfExecutionChanged " + executionId, () -> {
            for (WorkFlowExecutionStep s : steps.findByExecutionIdOrderByStepOrder(executionId)) {
                publish(stepEvent(s));
            }
        });
    }

    @Override
    public void logAppended(Long stepId, int fromSeq, List<String> lines) {
        safely("logAppended " + stepId, () ->
                steps.findById(stepId).ifPresent(s -> publish(new ExecutionEvent(
                        ExecutionEvent.VERSION, ExecutionEvent.Type.LOG, s.getExecutionId(), s.getId(),
                        null, null, null, null, null, fromSeq, lines))));
    }

    private void safely(String what, Runnable action) {
        try {
            action.run();
        } catch (RuntimeException e) {
            log.warn("Execution event not published ({}): {}", what, e.getMessage());
        }
    }

    private static ExecutionEvent executionEvent(WorkFlowExecution e) {
        return new ExecutionEvent(ExecutionEvent.VERSION, ExecutionEvent.Type.EXECUTION_STATUS,
                e.getId(), null, e.getStatus() == null ? null : e.getStatus().name(),
                iso(e.getStartedAt()), iso(e.getEndedAt()), null, e.getErrorMessage(), null, null);
    }

    private static ExecutionEvent stepEvent(WorkFlowExecutionStep s) {
        return new ExecutionEvent(ExecutionEvent.VERSION, ExecutionEvent.Type.STEP_STATUS,
                s.getExecutionId(), s.getId(), s.getStatus() == null ? null : s.getStatus().name(),
                iso(s.getStartedAt()), iso(s.getEndedAt()), s.getDuration(), s.getErrorMessage(), null, null);
    }

    private static String iso(LocalDateTime t) {
        return t == null ? null : t.toString();
    }
}
