package com.example.myGithubAction.execution_engine.core;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class WorkspaceManagerTest {

    @TempDir
    Path tempDirectory;

    @Test
    void createsDistinctWorkspacesAndDeletesNestedContents() throws Exception {
        WorkspaceManager manager = new WorkspaceManager(tempDirectory.toString());

        Path first = manager.createWorkspace(1L);
        Path second = manager.createWorkspace(2L);
        Files.createDirectories(first.resolve("nested"));
        Files.writeString(first.resolve("nested/output.txt"), "data");

        assertTrue(Files.isDirectory(first));
        assertTrue(Files.isDirectory(second));
        assertNotEquals(first, second);
        assertTrue(first.startsWith(tempDirectory));

        manager.deleteWorkspace(1L);

        assertFalse(Files.exists(first));
        assertTrue(Files.exists(second));
    }

    @Test
    void deletingMissingWorkspaceIsANoOp() {
        WorkspaceManager manager = new WorkspaceManager(tempDirectory.toString());

        assertDoesNotThrow(() -> manager.deleteWorkspace(99L));
    }
}
