package com.example.myGithubAction.monitoring;

public record ExecutionView(Long id, Long workflowId, String status, String startedAt, String endedAt,
                            String errorMessage) { }
