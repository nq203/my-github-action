package com.example.myGithubAction.workflow.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ExecutionStatusUpdateRequest {
    private String status; // PENDING, RUNNING, SUCCESS, FAILED, CANCELLED
    private String logs;
    private String errorMessage;
}
