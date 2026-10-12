package com.example.myGithubAction.monitoring;

import com.example.myGithubAction.messaging.ExecutionEvent;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class StreamSubscriptionTest {

    static class RecordingSink implements StreamSink {
        final List<String> names = new ArrayList<>();
        final List<Object> data = new ArrayList<>();
        int completed;

        @Override public synchronized void send(String eventName, Object d) { names.add(eventName); data.add(d); }
        @Override public synchronized void complete() { completed++; }
    }

    static ExecutionSnapshot snap(String execStatus, String stepStatus, int lineCount) {
        return new ExecutionSnapshot(
                new ExecutionView(1L, 7L, execStatus, null, null, null),
                List.of(new StepView(10L, 1, "echo", stepStatus, null, null, null, null, "x\n".repeat(lineCount), lineCount)));
    }

    static ExecutionEvent log(int fromSeq, String... lines) {
        return new ExecutionEvent(1, ExecutionEvent.Type.LOG, 1L, 10L, null, null, null, null, null, fromSeq, List.of(lines));
    }

    static ExecutionEvent exec(String status) {
        return new ExecutionEvent(1, ExecutionEvent.Type.EXECUTION_STATUS, 1L, null, status, null, null, null, null, null, null);
    }

    static ExecutionEvent step(String status) {
        return new ExecutionEvent(1, ExecutionEvent.Type.STEP_STATUS, 1L, 10L, status, null, null, null, null, null, null);
    }

    private final RecordingSink sink = new RecordingSink();
    private final AtomicReference<ExecutionSnapshot> db = new AtomicReference<>(snap("RUNNING", "RUNNING", 2));

    private StreamSubscription sub() {
        return new StreamSubscription(1L, 5L, sink, db::get);
    }

    @SuppressWarnings("unchecked")
    private static List<String> linesOf(Object data) {
        return (List<String>) ((java.util.Map<String, Object>) data).get("lines");
    }

    @Test
    void sendsSnapshotFirst() {
        sub().start();
        assertThat(sink.names).containsExactly("snapshot");
        assertThat(sink.data.get(0)).isEqualTo(db.get());
    }

    @Test
    void logEventAtNextSeqIsSentAndAdvances() {
        StreamSubscription s = sub();
        s.start();
        s.accept(log(3, "a", "b"));
        s.accept(log(5, "c"));
        assertThat(sink.names).containsExactly("snapshot", "log", "log");
        assertThat(linesOf(sink.data.get(1))).containsExactly("a", "b");
    }

    @Test
    void duplicateLogEventIsSkipped() {
        StreamSubscription s = sub();
        s.start();
        s.accept(log(1, "a", "b"));
        s.accept(log(2, "b"));
        assertThat(sink.names).containsExactly("snapshot");
    }

    @Test
    void overlappingLogEventSendsOnlyNewTail() {
        StreamSubscription s = sub();
        s.start();
        s.accept(log(2, "b", "c", "d"));
        assertThat(sink.names).containsExactly("snapshot", "log");
        assertThat(linesOf(sink.data.get(1))).containsExactly("c", "d");
        assertThat(((java.util.Map<?, ?>) sink.data.get(1)).get("fromSeq")).isEqualTo(3);
        s.accept(log(5, "e"));
        assertThat(sink.names).hasSize(3);
    }

    @Test
    void gapTriggersFreshSnapshot() {
        StreamSubscription s = sub();
        s.start();
        db.set(snap("RUNNING", "RUNNING", 6));
        s.accept(log(6, "f"));
        assertThat(sink.names).containsExactly("snapshot", "snapshot");
        assertThat(sink.data.get(1)).isEqualTo(db.get());
        s.accept(log(7, "g"));
        assertThat(sink.names).containsExactly("snapshot", "snapshot", "log");
    }

    @Test
    void eventsBufferedBeforeStartAreAppliedAfterSnapshot() {
        StreamSubscription s = sub();
        s.accept(log(3, "a"));
        assertThat(sink.names).isEmpty();
        s.start();
        assertThat(sink.names).containsExactly("snapshot", "log");
    }

    @Test
    void eventsArrivingWhileSnapshotLoadsAreBufferedAndApplied() {
        StreamSubscription[] holder = new StreamSubscription[1];
        holder[0] = new StreamSubscription(1L, 5L, sink, () -> {
            holder[0].accept(log(3, "a"));
            return db.get();
        });
        holder[0].start();
        assertThat(sink.names).containsExactly("snapshot", "log");
    }

    @Test
    void bufferedEventAlreadyInSnapshotIsDropped() {
        StreamSubscription s = sub();
        s.accept(log(1, "a", "b"));
        s.start();
        assertThat(sink.names).containsExactly("snapshot");
    }

    @Test
    void staleStatusEventIsIgnored() {
        db.set(snap("RUNNING", "RUNNING", 2));
        StreamSubscription s = sub();
        s.start();
        s.accept(step("SUCCESS"));
        s.accept(step("RUNNING"));
        s.accept(exec("RUNNING"));
        assertThat(sink.names).containsExactly("snapshot", "step");
    }

    @Test
    void sameStatusIsIgnored() {
        StreamSubscription s = sub();
        s.start();
        s.accept(exec("RUNNING"));
        s.accept(step("RUNNING"));
        assertThat(sink.names).containsExactly("snapshot");
    }

    @Test
    void terminalStatusSendsEndOnceAndCompletes() {
        StreamSubscription s = sub();
        s.start();
        db.set(snap("SUCCESS", "SUCCESS", 2));
        s.accept(exec("SUCCESS"));
        s.accept(exec("SUCCESS"));
        assertThat(sink.names).containsExactly("snapshot", "execution", "end");
        assertThat(sink.completed).isEqualTo(1);
        assertThat(s.isClosed()).isTrue();
    }

    @Test
    void terminalWithMissingLinesSendsSnapshotBeforeEnd() {
        StreamSubscription s = sub();
        s.start();
        db.set(snap("FAILED", "FAILED", 5));
        s.accept(exec("FAILED"));
        assertThat(sink.names).containsExactly("snapshot", "execution", "snapshot", "end");
        assertThat(sink.data.get(2)).isEqualTo(db.get());
    }

    @Test
    void alreadyTerminalAtStartSendsSnapshotThenEnd() {
        db.set(snap("SUCCESS", "SUCCESS", 2));
        StreamSubscription s = sub();
        s.start();
        assertThat(sink.names).containsExactly("snapshot", "end");
        assertThat(sink.completed).isEqualTo(1);
    }

    @Test
    void reconcileEmitsMissedTerminalStatus() {
        StreamSubscription s = sub();
        s.start();
        s.reconcile(snap("SUCCESS", "SUCCESS", 2));
        assertThat(sink.names).containsExactly("snapshot", "step", "execution", "end");
        assertThat(sink.completed).isEqualTo(1);
    }

    @Test
    void reconcileWithMoreLinesSendsFreshSnapshot() {
        StreamSubscription s = sub();
        s.start();
        db.set(snap("RUNNING", "RUNNING", 4));
        s.reconcile(snap("RUNNING", "RUNNING", 4));
        assertThat(sink.names).containsExactly("snapshot", "snapshot");
        assertThat(sink.data.get(1)).isEqualTo(db.get());
    }

    @Test
    void reconcileWithStaleSnapshotNeverMovesBackwards() {
        ExecutionSnapshot start = new ExecutionSnapshot(new ExecutionView(1L, 7L, "RUNNING", null, null, null),
                List.of(new StepView(10L, 1, "a", "RUNNING", null, null, null, null, "", 10),
                        new StepView(11L, 2, "b", "PENDING", null, null, null, null, "", 3)));
        ExecutionSnapshot stale = start;
        ExecutionSnapshot fresh = new ExecutionSnapshot(new ExecutionView(1L, 7L, "RUNNING", null, null, null),
                List.of(new StepView(10L, 1, "a", "SUCCESS", null, null, null, null, "", 12),
                        new StepView(11L, 2, "b", "RUNNING", null, null, null, null, "", 5)));
        AtomicReference<ExecutionSnapshot> loader = new AtomicReference<>(start);
        StreamSubscription s = new StreamSubscription(1L, 5L, sink, loader::get);
        s.start();
        loader.set(fresh);
        s.accept(new ExecutionEvent(1, ExecutionEvent.Type.LOG, 1L, 10L, null, null, null, null, null, 11, List.of("l", "m")));
        s.accept(step("SUCCESS"));
        s.accept(new ExecutionEvent(1, ExecutionEvent.Type.LOG, 1L, 11L, null, null, null, null, null, 4, List.of("x", "y")));
        // poll's view is stale on step 10 (10 lines, RUNNING) but ahead on step 11 (6 > 5 known)
        ExecutionSnapshot polled = new ExecutionSnapshot(new ExecutionView(1L, 7L, "RUNNING", null, null, null),
                List.of(new StepView(10L, 1, "a", "RUNNING", null, null, null, null, "", 10),
                        new StepView(11L, 2, "b", "RUNNING", null, null, null, null, "", 6)));
        s.reconcile(polled);
        for (Object d : sink.data) {
            if (d instanceof ExecutionSnapshot sn && sn != start) {
                StepView a = sn.steps().get(0);
                assertThat(a.status()).isEqualTo("SUCCESS");
                assertThat(a.lineCount()).isGreaterThanOrEqualTo(12);
            }
        }
        assertThat(stale).isSameAs(start);
    }

    @Test
    void failingStartClosesSubscriptionAndPropagates() {
        StreamSubscription s = new StreamSubscription(1L, 5L, sink, () -> { throw new IllegalStateException("db down"); });
        org.assertj.core.api.Assertions.assertThatThrownBy(s::start).isInstanceOf(IllegalStateException.class);
        assertThat(s.isClosed()).isTrue();
        s.accept(log(1, "a"));
        assertThat(sink.names).isEmpty();
    }

    @Test
    void eventsAfterEndAreIgnored() {
        StreamSubscription s = sub();
        s.start();
        db.set(snap("CANCELLED", "RUNNING", 2));
        s.accept(exec("CANCELLED"));
        int n = sink.names.size();
        s.accept(log(3, "late"));
        s.accept(exec("CANCELLED"));
        s.reconcile(snap("CANCELLED", "RUNNING", 9));
        assertThat(sink.names).hasSize(n);
        assertThat(sink.names.stream().filter("end"::equals)).hasSize(1);
    }

    @Test
    void failingSinkClosesSubscriptionWithoutThrowing() {
        StreamSink bad = new StreamSink() {
            @Override public void send(String n, Object d) { throw new IllegalStateException("gone"); }
            @Override public void complete() { }
        };
        StreamSubscription s = new StreamSubscription(1L, 5L, bad, db::get);
        s.start();
        assertThat(s.isClosed()).isTrue();
    }
}
