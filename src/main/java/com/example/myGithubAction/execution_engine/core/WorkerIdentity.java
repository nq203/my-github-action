package com.example.myGithubAction.execution_engine.core;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.InetAddress;
import java.util.UUID;

/**
 * Identifies this process as the owner of the executions it claims.
 *
 * With {@code app.worker.id} set (e.g. a StatefulSet pod name) the id is stable across
 * restarts, so a restarted worker knows its previous incarnation is dead and can fail that
 * incarnation's executions immediately. Without it the id is unique per JVM start, and the
 * executions of a dead process are found by their stale heartbeat instead.
 */
@Component
public class WorkerIdentity {

    private final String id;
    private final boolean stable;

    public WorkerIdentity(@Value("${app.worker.id:}") String configuredId) {
        this.stable = configuredId != null && !configuredId.isBlank();
        this.id = stable ? configuredId.trim() : hostName() + "-" + UUID.randomUUID().toString().substring(0, 8);
    }

    public String id() {
        return id;
    }

    /** True when the id survives restarts (explicitly configured). */
    public boolean isStable() {
        return stable;
    }

    private static String hostName() {
        try {
            return InetAddress.getLocalHost().getHostName();
        } catch (Exception e) {
            return "worker";
        }
    }
}
