package com.example.myGithubAction.monitoring;

public record StepView(Long id, Integer order, String command, String status, String startedAt, String endedAt,
                       Long duration, String errorMessage, String logs, int lineCount) { }
