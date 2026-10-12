package com.example.myGithubAction.execution_engine.recovery;

import com.example.myGithubAction.common.ExecutionState;
import com.example.myGithubAction.execution_engine.core.WorkspaceManager;
import com.example.myGithubAction.workflow.entity.WorkFlowExecution;
import com.example.myGithubAction.workflow.repository.WorkFlowExecutionRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Removes workspaces left on this host by executions that are no longer RUNNING (worker
 * crashed before cleaning up, execution was reaped). A RUNNING execution is always kept:
 * its status is set before its workspace is created.
 */
@Slf4j
@Component
@ConditionalOnExecutionRunner
public class WorkspaceJanitor {

    private final WorkspaceManager workspaceManager;
    private final WorkFlowExecutionRepository executionRepository;

    public WorkspaceJanitor(WorkspaceManager workspaceManager, WorkFlowExecutionRepository executionRepository) {
        this.workspaceManager = workspaceManager;
        this.executionRepository = executionRepository;
    }

    @Scheduled(initialDelay = 5_000, fixedDelayString = "${app.worker.workspace-sweep-interval-ms:3600000}")
    public void cleanLeftovers() {
        List<Long> onDisk = workspaceManager.listWorkspaceExecutionIds();
        if (onDisk.isEmpty()) {
            return;
        }
        try {
            Map<Long, ExecutionState> statuses = executionRepository.findAllById(onDisk).stream()
                    .collect(Collectors.toMap(WorkFlowExecution::getId, WorkFlowExecution::getStatus,
                            (a, b) -> a));
            for (Long executionId : onDisk) {
                if (statuses.get(executionId) != ExecutionState.RUNNING) {
                    log.info("Removing leftover workspace of execution {}", executionId);
                    workspaceManager.deleteWorkspace(executionId);
                }
            }
        } catch (RuntimeException e) {
            log.warn("Workspace cleanup failed: {}", e.getMessage());
        }
    }
}
