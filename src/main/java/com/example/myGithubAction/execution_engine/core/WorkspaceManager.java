package com.example.myGithubAction.execution_engine.core;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;

@Component
public class WorkspaceManager {

    private final Path baseDirectory;

    public WorkspaceManager(@Value("${execution.workspace.base-dir:${java.io.tmpdir}/ci-workspaces}") String baseDirectory) {
        this.baseDirectory = Path.of(baseDirectory).toAbsolutePath().normalize();
    }

    public Path createWorkspace(Long executionId) throws IOException {
        Path workspace = resolveWorkspacePath(executionId);
        Files.createDirectories(workspace);
        return workspace;
    }

    public Path resolveWorkspacePath(Long executionId) {
        Path workspace = baseDirectory.resolve("exec-" + executionId).normalize();
        if (!workspace.startsWith(baseDirectory)) {
            throw new IllegalArgumentException("Workspace path escapes the configured base directory");
        }
        return workspace;
    }

    public void deleteWorkspace(Long executionId) {
        Path workspace = resolveWorkspacePath(executionId);
        if (!Files.exists(workspace)) {
            return;
        }

        try (var paths = Files.walk(workspace)) {
            paths.sorted(Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException ignored) {
                }
            });
        } catch (IOException ignored) {
        }
    }

    /** Ids of the executions that currently have a workspace directory on this host. */
    public List<Long> listWorkspaceExecutionIds() {
        if (!Files.isDirectory(baseDirectory)) {
            return List.of();
        }
        try (var entries = Files.list(baseDirectory)) {
            return entries
                .map(path -> path.getFileName().toString())
                .filter(name -> name.matches("exec-\\d+"))
                .map(name -> Long.valueOf(name.substring("exec-".length())))
                .toList();
        } catch (IOException e) {
            return List.of();
        }
    }
}
