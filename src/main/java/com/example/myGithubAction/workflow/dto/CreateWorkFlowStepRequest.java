package com.example.myGithubAction.workflow.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class CreateWorkFlowStepRequest {

    @NotBlank(message = "Step name is required")
    private String name;

    @NotNull(message = "Step order is required")
    private Integer stepOrder;

    @NotBlank(message = "Step type is required")
    private String type; // BUILD, TEST, DEPLOY

    private String command;
}
