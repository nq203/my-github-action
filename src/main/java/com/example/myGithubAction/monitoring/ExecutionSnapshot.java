package com.example.myGithubAction.monitoring;

import java.util.List;

/** Consistent DB view of an execution; step {@code logs} and {@code lineCount} come from one read. */
public record ExecutionSnapshot(ExecutionView execution, List<StepView> steps) { }
