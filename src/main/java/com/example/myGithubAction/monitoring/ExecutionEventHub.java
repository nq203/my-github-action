package com.example.myGithubAction.monitoring;

import com.example.myGithubAction.messaging.ExecutionEvent;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArraySet;

/**
 * Per-instance registry of live viewers, keyed by executionId. The hub holds no lock while it
 * calls a subscription, so the only lock order is subscription lock -> sink.
 */
@Slf4j
@Component
public class ExecutionEventHub {

    private final ConcurrentHashMap<Long, CopyOnWriteArraySet<StreamSubscription>> byExecution =
            new ConcurrentHashMap<>();

    public void register(StreamSubscription s) {
        byExecution.compute(s.executionId(), (id, set) -> {
            CopyOnWriteArraySet<StreamSubscription> next = set == null ? new CopyOnWriteArraySet<>() : set;
            next.add(s);
            return next;
        });
    }

    public void unregister(StreamSubscription s) {
        byExecution.computeIfPresent(s.executionId(), (id, set) -> {
            set.remove(s);
            return set.isEmpty() ? null : set;
        });
    }

    public void accept(ExecutionEvent e) {
        if (e == null || e.executionId() == null) {
            return;
        }
        for (StreamSubscription s : subscribers(e.executionId())) {
            try {
                s.accept(e);
            } catch (RuntimeException ex) {
                log.warn("Subscription for execution {} failed on {}: {}", e.executionId(), e.type(), ex.getMessage());
            }
            pruneIfClosed(s);
        }
    }

    public void reconcile(Collection<ExecutionSnapshot> dbStates) {
        for (ExecutionSnapshot db : dbStates) {
            for (StreamSubscription s : subscribers(db.execution().id())) {
                try {
                    s.reconcile(db);
                } catch (RuntimeException ex) {
                    log.warn("Reconcile of execution {} failed: {}", db.execution().id(), ex.getMessage());
                }
                pruneIfClosed(s);
            }
        }
    }

    public int countForUser(Long userId) {
        int n = 0;
        for (CopyOnWriteArraySet<StreamSubscription> set : byExecution.values()) {
            for (StreamSubscription s : set) {
                if (!s.isClosed() && userId.equals(s.userId())) {
                    n++;
                }
            }
        }
        return n;
    }

    public Set<Long> watchedExecutionIds() {
        return new HashSet<>(byExecution.keySet());
    }

    private Collection<StreamSubscription> subscribers(Long executionId) {
        CopyOnWriteArraySet<StreamSubscription> set = byExecution.get(executionId);
        return set == null ? Set.of() : set;
    }

    private void pruneIfClosed(StreamSubscription s) {
        if (s.isClosed()) {
            unregister(s);
        }
    }
}
