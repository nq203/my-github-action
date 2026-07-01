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
public class StepResponse {
    private Long id;
    private Long workflowId;
    private String name;
    private Integer stepOrder;
    private String type;
    private String command;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
