package com.example.myGithubAction.execution_engine.core;

import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * In-memory registry tracking cancellation state and the currently running
 * shell process for each workflow execution.
 *
 * State is not persisted: if the application restarts, all registrations
 * are lost; recovery marks the interrupted executions FAILED.
 */
@Component
public class ExecutionCancellationRegistry {

    private final Map<Long, AtomicBoolean> cancelledFlags = new ConcurrentHashMap<>();
    private final Map<Long, Process> runningProcesses = new ConcurrentHashMap<>();

    public void register(Long executionId) {
        // Preserve an existing cancel request: cancel() may have already run
        // (e.g. execution was cancelled while still PENDING, before this
        // async task got scheduled) and must not be silently reset to false.
        cancelledFlags.computeIfAbsent(executionId, id -> new AtomicBoolean(false));
    }

    public void unregister(Long executionId) {
        cancelledFlags.remove(executionId);
        runningProcesses.remove(executionId);
    }

    public boolean isCancelled(Long executionId) {
        AtomicBoolean flag = cancelledFlags.get(executionId);
        return flag != null && flag.get();
    }

    /**
     * Flags a locally running execution as cancelled and kills its process.
     *
     * No-op for executions this instance is not running: the DB status is the source
     * of truth (an execution cancelled before being claimed can never be claimed),
     * and remembering unknown ids would leak entries, since every worker receives
     * every broadcast cancel.
     */
    public void cancel(Long executionId) {
        AtomicBoolean flag = cancelledFlags.get(executionId);
        if (flag != null) {
            flag.set(true);
        }

        Process process = runningProcesses.get(executionId);
        if (process != null && process.isAlive()) {
            process.destroyForcibly();
        }
    }

    /** Ids of the executions currently running on this instance. */
    public Set<Long> activeExecutionIds() {
        return Set.copyOf(cancelledFlags.keySet());
    }

    public void registerProcess(Long executionId, Process process) {
        runningProcesses.put(executionId, process);
    }

    public void clearProcess(Long executionId) {
        runningProcesses.remove(executionId);
    }
}
