package com.example.myGithubAction.execution_engine.recovery;

import com.example.myGithubAction.common.ExecutionState;
import com.example.myGithubAction.execution_engine.core.WorkspaceManager;
import com.example.myGithubAction.workflow.entity.WorkFlowExecution;
import com.example.myGithubAction.workflow.repository.WorkFlowExecutionRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class WorkspaceJanitorTest {

    @TempDir
    Path base;

    private WorkFlowExecution execution(Long id, ExecutionState status) {
        return WorkFlowExecution.builder().id(id).status(status).build();
    }

    @Test
    void removesWorkspacesOfFinishedLostOrUnknownExecutionsButKeepsRunningOnes() throws Exception {
        WorkspaceManager workspaceManager = new WorkspaceManager(base.toString());
        for (long id : new long[] {1, 2, 3, 4}) {
            workspaceManager.createWorkspace(id);
        }
        Files.createDirectories(base.resolve("not-a-workspace"));
        WorkFlowExecutionRepository repository = mock(WorkFlowExecutionRepository.class);
        when(repository.findAllById(anyIterable())).thenReturn(List.of(
            execution(1L, ExecutionState.RUNNING),
            execution(2L, ExecutionState.FAILED),
            execution(3L, ExecutionState.SUCCESS)));
        // execution 4 does not exist in the DB at all

        new WorkspaceJanitor(workspaceManager, repository).cleanLeftovers();

        assertTrue(Files.exists(workspaceManager.resolveWorkspacePath(1L)), "RUNNING must be kept");
        assertFalse(Files.exists(workspaceManager.resolveWorkspacePath(2L)));
        assertFalse(Files.exists(workspaceManager.resolveWorkspacePath(3L)));
        assertFalse(Files.exists(workspaceManager.resolveWorkspacePath(4L)));
        assertTrue(Files.exists(base.resolve("not-a-workspace")), "unrelated directories are never touched");
    }

    @Test
    void databaseFailureLeavesEverythingInPlace() throws Exception {
        WorkspaceManager workspaceManager = new WorkspaceManager(base.toString());
        workspaceManager.createWorkspace(1L);
        WorkFlowExecutionRepository repository = mock(WorkFlowExecutionRepository.class);
        when(repository.findAllById(anyIterable())).thenThrow(new IllegalStateException("db down"));

        new WorkspaceJanitor(workspaceManager, repository).cleanLeftovers();

        assertTrue(Files.exists(workspaceManager.resolveWorkspacePath(1L)));
    }
}
