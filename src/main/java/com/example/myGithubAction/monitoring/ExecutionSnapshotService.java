package com.example.myGithubAction.monitoring;

import com.example.myGithubAction.auth.exception.ResourceNotFoundException;
import com.example.myGithubAction.workflow.entity.WorkFlowExecution;
import com.example.myGithubAction.workflow.entity.WorkFlowExecutionStep;
import com.example.myGithubAction.workflow.repository.WorkFlowExecutionRepository;
import com.example.myGithubAction.workflow.repository.WorkFlowExecutionStepRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/** Reads {@link ExecutionSnapshot}s from the DB (the source of truth for the live monitor). */
@Service
public class ExecutionSnapshotService {

    private static final Comparator<WorkFlowExecutionStep> BY_ORDER = Comparator
            .comparing(WorkFlowExecutionStep::getStepOrder, Comparator.nullsLast(Comparator.naturalOrder()))
            .thenComparing(WorkFlowExecutionStep::getId);

    private final WorkFlowExecutionRepository executions;
    private final WorkFlowExecutionStepRepository steps;

    public ExecutionSnapshotService(WorkFlowExecutionRepository executions, WorkFlowExecutionStepRepository steps) {
        this.executions = executions;
        this.steps = steps;
    }

    @Transactional(readOnly = true)
    public ExecutionSnapshot load(Long executionId) {
        WorkFlowExecution e = executions.findById(executionId)
                .orElseThrow(() -> new ResourceNotFoundException("Execution not found"));
        return snapshot(e, steps.findByExecutionIdOrderByStepOrder(executionId));
    }

    /** One execution query and one step query for all ids; ids that no longer exist are skipped. */
    @Transactional(readOnly = true)
    public List<ExecutionSnapshot> loadAll(Collection<Long> executionIds) {
        if (executionIds == null || executionIds.isEmpty()) {
            return List.of();
        }
        List<WorkFlowExecution> found = executions.findAllById(executionIds);
        Map<Long, List<WorkFlowExecutionStep>> stepsByExecution = steps.findByExecutionIdIn(executionIds).stream()
                .collect(Collectors.groupingBy(WorkFlowExecutionStep::getExecutionId));
        List<ExecutionSnapshot> result = new ArrayList<>(found.size());
        for (WorkFlowExecution e : found) {
            List<WorkFlowExecutionStep> own = new ArrayList<>(stepsByExecution.getOrDefault(e.getId(), List.of()));
            own.sort(BY_ORDER);
            result.add(snapshot(e, own));
        }
        return result;
    }

    private static ExecutionSnapshot snapshot(WorkFlowExecution e, List<WorkFlowExecutionStep> stepRows) {
        ExecutionView view = new ExecutionView(e.getId(), e.getWorkflowId(), name(e.getStatus()),
                iso(e.getStartedAt()), iso(e.getEndedAt()), e.getErrorMessage());
        List<StepView> stepViews = stepRows.stream()
                .map(s -> new StepView(s.getId(), s.getStepOrder(), s.getCommand(), name(s.getStatus()),
                        iso(s.getStartedAt()), iso(s.getEndedAt()), s.getDuration(), s.getErrorMessage(),
                        s.getLogs() == null ? "" : s.getLogs(), s.getLogLineCount()))
                .toList();
        return new ExecutionSnapshot(view, stepViews);
    }

    private static String name(Enum<?> status) {
        return status == null ? null : status.name();
    }

    private static String iso(LocalDateTime t) {
        return t == null ? null : t.toString();
    }
}
