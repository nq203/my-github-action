package com.example.myGithubAction.execution_engine.recovery;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** Liveness tuning of an instance that runs executions (app.worker.*). */
@Data
@ConfigurationProperties(prefix = "app.worker")
public class WorkerProperties {

    /** How often the worker reports that its running executions are alive. */
    private long heartbeatIntervalMs = 10_000;
    /** A RUNNING execution without a heartbeat for this long is considered lost. */
    private long staleAfterMs = 60_000;
    /** How often lost executions are looked for. */
    private long reaperIntervalMs = 15_000;
    /** How often PENDING executions whose START may have been lost are looked for. */
    private long sweepIntervalMs = 30_000;
    /** A PENDING execution not (re)published for this long gets its START published again. */
    private long pendingRepublishAfterMs = 300_000;
    private int sweepBatchSize = 100;
    /** How often leftover workspaces of finished or lost executions are removed. */
    private long workspaceSweepIntervalMs = 3_600_000;
}
