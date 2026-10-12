package com.example.myGithubAction.messaging;

import java.util.List;

/** Publishes live-monitor events after the DB write; no method ever throws. */
public interface ExecutionEventPublisher {

    void executionChanged(Long executionId);

    void stepChanged(Long stepId);

    void stepsOfExecutionChanged(Long executionId);

    void logAppended(Long stepId, int fromSeq, List<String> lines);
}
