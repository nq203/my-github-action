package com.example.myGithubAction.workflow.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import java.time.LocalDateTime;
import java.util.List;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class WorkFlowExecutionResponse {
    private Long id;
    private Long workflowId;
    private Long userId;
    private String status;
    private LocalDateTime startedAt;
    private LocalDateTime endedAt;
    private String logs;
    private String errorMessage;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    private List<WorkFlowExecutionStepResponse> steps;
}
