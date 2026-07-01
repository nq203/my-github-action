package com.example.myGithubAction.workflow.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import java.time.LocalDateTime;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class WorkFlowExecutionStepResponse {
    private Long id;
    private Long executionId;
    private Long stepId;
    private String status;
    private Integer stepOrder;
    private LocalDateTime startedAt;
    private LocalDateTime endedAt;
    private String logs;
    private String errorMessage;
    private Long duration;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
