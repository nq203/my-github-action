package com.example.myGithubAction.workflow.controller;

import com.example.myGithubAction.workflow.dto.CreateStepRequest;
import com.example.myGithubAction.workflow.dto.StepResponse;
import com.example.myGithubAction.workflow.service.WorkFlowService;
import jakarta.validation.Valid;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/steps")
@Slf4j
public class StepController {

    private final WorkFlowService workFlowService;

    public StepController(WorkFlowService workFlowService) {
        this.workFlowService = workFlowService;
    }

    /**
     * Add a step to a workflow
     */
    @PostMapping
    public ResponseEntity<Map<String, Object>> addStep(
            @Valid @RequestBody CreateStepRequest request) {
        log.info("Adding step to workflow id: {}", request.getWorkflowId());
        StepResponse response = workFlowService.addStep(request);

        Map<String, Object> result = new HashMap<>();
        result.put("success", true);
        result.put("message", "Step added successfully");
        result.put("data", response);

        return ResponseEntity.status(HttpStatus.CREATED).body(result);
    }

    /**
     * Get all steps for a workflow
     */
    @GetMapping("/workflow/{workflowId}")
    public ResponseEntity<Map<String, Object>> getStepsByWorkflowId(@PathVariable Long workflowId) {
        log.info("Getting steps for workflow id: {}", workflowId);
        List<StepResponse> response = workFlowService.getStepsByWorkflowId(workflowId);

        Map<String, Object> result = new HashMap<>();
        result.put("success", true);
        result.put("message", "Retrieved " + response.size() + " steps");
        result.put("data", response);

        return ResponseEntity.ok(result);
    }

    /**
     * Get a specific step
     */
    @GetMapping("/{stepId}")
    public ResponseEntity<Map<String, Object>> getStep(@PathVariable Long stepId) {
        log.info("Getting step with id: {}", stepId);
        StepResponse response = workFlowService.getStep(stepId);

        Map<String, Object> result = new HashMap<>();
        result.put("success", true);
        result.put("data", response);

        return ResponseEntity.ok(result);
    }

    /**
     * Update a step
     */
    @PutMapping("/{stepId}")
    public ResponseEntity<Map<String, Object>> updateStep(
            @PathVariable Long stepId,
            @Valid @RequestBody CreateStepRequest request) {
        log.info("Updating step with id: {}", stepId);
        StepResponse response = workFlowService.updateStep(stepId, request);

        Map<String, Object> result = new HashMap<>();
        result.put("success", true);
        result.put("message", "Step updated successfully");
        result.put("data", response);

        return ResponseEntity.ok(result);
    }

    /**
     * Remove (delete) a step
     */
    @DeleteMapping("/{stepId}")
    public ResponseEntity<Map<String, Object>> removeStep(@PathVariable Long stepId) {
        log.info("Removing step with id: {}", stepId);
        workFlowService.removeStep(stepId);

        Map<String, Object> result = new HashMap<>();
        result.put("success", true);
        result.put("message", "Step removed successfully");

        return ResponseEntity.ok(result);
    }

    /**
     * Move a step to a new position
     * Request body: {"position": 2}
     */
    @PostMapping("/{stepId}/move")
    public ResponseEntity<Map<String, Object>> moveStep(
            @PathVariable Long stepId,
            @RequestParam Integer position) {
        log.info("Moving step id: {} to position: {}", stepId, position);
        List<StepResponse> response = workFlowService.moveStep(stepId, position);

        Map<String, Object> result = new HashMap<>();
        result.put("success", true);
        result.put("message", "Step moved successfully");
        result.put("data", response);

        return ResponseEntity.ok(result);
    }

    /**
     * Clone a step
     */
    @PostMapping("/{stepId}/clone")
    public ResponseEntity<Map<String, Object>> cloneStep(@PathVariable Long stepId) {
        log.info("Cloning step with id: {}", stepId);
        StepResponse response = workFlowService.cloneStep(stepId);

        Map<String, Object> result = new HashMap<>();
        result.put("success", true);
        result.put("message", "Step cloned successfully");
        result.put("data", response);

        return ResponseEntity.status(HttpStatus.CREATED).body(result);
    }
}
