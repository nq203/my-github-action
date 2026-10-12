package com.example.myGithubAction.monitoring;

import com.example.myGithubAction.messaging.ExecutionEvent;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ExecutionEventHubTest {

    private final ExecutionEventHub hub = new ExecutionEventHub();

    private static ExecutionSnapshot snap(long id) {
        return new ExecutionSnapshot(new ExecutionView(id, 1L, "RUNNING", null, null, null), List.of());
    }

    private static StreamSubscription sub(long execId, long userId, StreamSubscriptionTest.RecordingSink sink) {
        StreamSubscription s = new StreamSubscription(execId, userId, sink, () -> snap(execId));
        s.start();
        return s;
    }

    private static ExecutionEvent exec(long id, String status) {
        return new ExecutionEvent(1, ExecutionEvent.Type.EXECUTION_STATUS, id, null, status, null, null, null, null, null, null);
    }

    @Test
    void routesEventsOnlyToSubscribersOfThatExecution() {
        var a = new StreamSubscriptionTest.RecordingSink();
        var b = new StreamSubscriptionTest.RecordingSink();
        hub.register(sub(1L, 5L, a));
        hub.register(sub(2L, 5L, b));
        // statuses are forward-only; PENDING snapshot is not used here, RUNNING -> SUCCESS is ahead
        hub.accept(exec(1L, "SUCCESS"));
        assertThat(a.names).contains("execution");
        assertThat(b.names).containsExactly("snapshot");
    }

    @Test
    void oneFailingSubscriptionDoesNotBlockOthers() {
        var ok = new StreamSubscriptionTest.RecordingSink();
        StreamSubscription bad = new StreamSubscription(1L, 5L, ok, () -> snap(1L)) {
            @Override public void accept(ExecutionEvent event) { throw new IllegalStateException("boom"); }
        };
        hub.register(bad);
        hub.register(sub(1L, 6L, ok));
        hub.accept(exec(1L, "SUCCESS"));
        assertThat(ok.names).contains("execution");
    }

    @Test
    void countForUserAndWatchedIdsTrackRegisterAndUnregister() {
        var sink = new StreamSubscriptionTest.RecordingSink();
        StreamSubscription s1 = sub(1L, 5L, sink);
        StreamSubscription s2 = sub(2L, 5L, sink);
        hub.register(s1);
        hub.register(s2);
        assertThat(hub.countForUser(5L)).isEqualTo(2);
        assertThat(hub.countForUser(6L)).isZero();
        assertThat(hub.watchedExecutionIds()).containsExactlyInAnyOrder(1L, 2L);
        hub.unregister(s1);
        assertThat(hub.countForUser(5L)).isEqualTo(1);
        assertThat(hub.watchedExecutionIds()).containsExactly(2L);
        hub.unregister(s2);
        assertThat(hub.watchedExecutionIds()).isEmpty();
    }

    @Test
    void reconcileRoutesDbStateToWatchers() {
        var sink = new StreamSubscriptionTest.RecordingSink();
        hub.register(sub(1L, 5L, sink));
        hub.reconcile(List.of(new ExecutionSnapshot(new ExecutionView(1L, 1L, "FAILED", null, null, null), List.of())));
        assertThat(sink.names).containsExactly("snapshot", "execution", "end");
    }
}
