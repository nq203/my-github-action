package com.example.myGithubAction.execution_engine.recovery;

import jakarta.annotation.PostConstruct;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

@Configuration
@EnableScheduling
@EnableConfigurationProperties(WorkerProperties.class)
@ConditionalOnExecutionRunner
public class ExecutionRunnerConfig {

    private final WorkerProperties properties;

    public ExecutionRunnerConfig(WorkerProperties properties) {
        this.properties = properties;
    }

    /** A single late heartbeat (GC pause, slow DB) must not get a healthy execution reaped. */
    @PostConstruct
    void validate() {
        if (properties.getStaleAfterMs() < 3 * properties.getHeartbeatIntervalMs()) {
            throw new IllegalStateException("app.worker.stale-after-ms (" + properties.getStaleAfterMs()
                    + ") must be at least 3 x app.worker.heartbeat-interval-ms ("
                    + properties.getHeartbeatIntervalMs() + ")");
        }
    }
}
