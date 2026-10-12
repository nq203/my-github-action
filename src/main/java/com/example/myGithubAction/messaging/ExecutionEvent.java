package com.example.myGithubAction.messaging;

import java.util.List;

/**
 * Live-monitor event published to the events topic. Timestamps are ISO-8601 strings because the
 * codec's ObjectMapper has no JavaTime module. Unused fields for a given type are null.
 */
public record ExecutionEvent(
        int version,
        Type type,
        Long executionId,
        Long stepId,
        String status,
        String startedAt,
        String endedAt,
        Long duration,
        String errorMessage,
        Integer fromSeq,
        List<String> lines) {

    public static final int VERSION = 1;

    public enum Type { EXECUTION_STATUS, STEP_STATUS, LOG }
}
