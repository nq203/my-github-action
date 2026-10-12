package com.example.myGithubAction.messaging;

/**
 * How a committed PENDING execution reaches an ExecutionEngine.
 *
 * Two implementations: in-process (@Async thread pool, the original behaviour) and
 * Kafka (durable queue consumed by workers). Selected with app.kafka.enabled.
 */
public interface ExecutionDispatcher {

    /**
     * Hands a committed PENDING execution over for execution.
     *
     * @throws java.util.concurrent.RejectedExecutionException if it could not be accepted;
     *         the caller then marks the execution FAILED instead of leaving it PENDING
     */
    void dispatch(Long executionId);

    /**
     * Best-effort, low-latency signal that an execution (already CANCELLED in the DB)
     * must stop. Never the source of truth: workers also re-check the DB.
     */
    void requestCancel(Long executionId);
}
