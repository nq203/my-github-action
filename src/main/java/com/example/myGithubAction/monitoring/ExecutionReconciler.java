package com.example.myGithubAction.monitoring;

import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.Set;

/**
 * DB safety net for lost events: every few seconds, one batch read of all executions that have a
 * viewer on this instance, handed to the hub (forward-only statuses, resync on missing lines,
 * {@code end} once terminal).
 */
@Slf4j
@Component
public class ExecutionReconciler {

    private final ExecutionEventHub hub;
    private final ExecutionSnapshotService snapshots;

    public ExecutionReconciler(ExecutionEventHub hub, ExecutionSnapshotService snapshots) {
        this.hub = hub;
        this.snapshots = snapshots;
    }

    @Scheduled(fixedDelayString = "${app.stream.reconcile-ms:5000}")
    public void reconcile() {
        Set<Long> watched = hub.watchedExecutionIds();
        if (watched.isEmpty()) {
            return;
        }
        try {
            hub.reconcile(snapshots.loadAll(watched));
        } catch (RuntimeException e) {
            log.warn("Live-monitor reconcile failed: {}", e.getMessage());
        }
    }
}
