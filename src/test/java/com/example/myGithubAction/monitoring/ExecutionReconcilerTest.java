package com.example.myGithubAction.monitoring;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

class ExecutionReconcilerTest {

    private static ExecutionSnapshot snap(long id, String status) {
        return new ExecutionSnapshot(new ExecutionView(id, 1L, status, null, null, null), List.of());
    }

    private static StreamSubscription started(long executionId, StreamSubscriptionTest.RecordingSink sink) {
        StreamSubscription s = new StreamSubscription(executionId, 5L, sink, () -> snap(executionId, "RUNNING"));
        s.start();
        return s;
    }

    @Test
    void reconcilesOnlyWatchedExecutionsWithOneBatchLoad() {
        ExecutionEventHub hub = new ExecutionEventHub();
        ExecutionSnapshotService snapshots = mock(ExecutionSnapshotService.class);
        ExecutionReconciler reconciler = new ExecutionReconciler(hub, snapshots);

        reconciler.reconcile();
        verifyNoInteractions(snapshots);

        var one = new StreamSubscriptionTest.RecordingSink();
        var two = new StreamSubscriptionTest.RecordingSink();
        hub.register(started(1L, one));
        hub.register(started(2L, two));
        when(snapshots.loadAll(any())).thenReturn(List.of(snap(1L, "SUCCESS"), snap(2L, "RUNNING")));

        reconciler.reconcile();

        verify(snapshots, times(1)).loadAll(Set.of(1L, 2L));
        verifyNoMoreInteractions(snapshots);
        assertThat(one.names).containsExactly("snapshot", "execution", "end");
        assertThat(one.completed).isEqualTo(1);
        assertThat(two.names).containsExactly("snapshot");
    }
}
