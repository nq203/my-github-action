package com.example.myGithubAction.workflow.dto;

import java.util.List;

import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class CreateWorkFlowRequest {

    @NotBlank(message = "Workflow name is required")
    private String name;

    private String description;
    // create step request list
    private List<CreateWorkFlowStepRequest> workFlowSteps;
}
