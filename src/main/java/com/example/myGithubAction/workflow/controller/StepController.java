package com.example.myGithubAction.workflow.controller;

import com.example.myGithubAction.workflow.dto.CreateStepRequest;
import com.example.myGithubAction.workflow.dto.StepResponse;
import com.example.myGithubAction.workflow.service.WorkFlowService;
import com.example.myGithubAction.auth.entity.UserPrincipal;
import jakarta.validation.Valid;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
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
     *
     * Security: Requires JWT authentication - userId extracted from token
     */
    @PostMapping
    public ResponseEntity<Map<String, Object>> addStep(
            @AuthenticationPrincipal UserPrincipal userPrincipal,
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
     *
     * Security: Requires JWT authentication - userId extracted from token
     */
    @GetMapping("/workflow/{workflowId}")
    public ResponseEntity<Map<String, Object>> getStepsByWorkflowId(
            @AuthenticationPrincipal UserPrincipal userPrincipal,
            @PathVariable Long workflowId) {
        Long userId = userPrincipal.getUserId();
        log.info("User {} getting steps for workflow id: {}", userId, workflowId);
        List<StepResponse> response = workFlowService.getStepsByWorkflowId(workflowId);

        Map<String, Object> result = new HashMap<>();
        result.put("success", true);
        result.put("message", "Retrieved " + response.size() + " steps");
        result.put("data", response);

        return ResponseEntity.ok(result);
    }

    /**
     * Get a specific step
     *
     * Security: Requires JWT authentication - userId extracted from token
     */
    @GetMapping("/{stepId}")
    public ResponseEntity<Map<String, Object>> getStep(
            @AuthenticationPrincipal UserPrincipal userPrincipal,
            @PathVariable Long stepId) {
        Long userId = userPrincipal.getUserId();
        log.info("User {} getting step with id: {}", userId, stepId);
        StepResponse response = workFlowService.getStep(stepId);

        Map<String, Object> result = new HashMap<>();
        result.put("success", true);
        result.put("data", response);

        return ResponseEntity.ok(result);
    }

    /**
     * Update a step
     *
     * Security: Requires JWT authentication - userId extracted from token
     */
    @PutMapping("/{stepId}")
    public ResponseEntity<Map<String, Object>> updateStep(
            @AuthenticationPrincipal UserPrincipal userPrincipal,
            @PathVariable Long stepId,
            @Valid @RequestBody CreateStepRequest request) {
        log.info("Updating step with id: {}", stepId);
        StepResposnse response = workFlowService.updateStep(stepId, request);

        Map<String, Object> result = new HashMap<>();
        result.put("success", true);
        result.put("message", "Step updated successfully");
        result.put("data", response);

        return ResponseEntity.ok(result);
    }

    /**
     * Remove (delete) a step
     *
     * Security: Requires JWT authentication - userId extracted from token
     */
    @DeleteMapping("/{stepId}")
    public ResponseEntity<Map<String, Object>> removeStep(
            @AuthenticationPrincipal UserPrincipal userPrincipal,
            @PathVariable Long stepId) {
        Long userId = userPrincipal.getUserId();
        log.info("User {} removing step with id: {}", userId, stepId);
        workFlowService.removeStep(stepId);

        Map<String, Object> result = new HashMap<>();
        result.put("success", true);
        result.put("message", "Step removed successfully");

        return ResponseEntity.ok(result);
    }

    /**
     * Move a step to a new position
     *
     * Security: Requires JWT authentication - userId extracted from token
     */
    @PostMapping("/{stepId}/move")
    public ResponseEntity<Map<String, Object>> moveStep(
            @AuthenticationPrincipal UserPrincipal userPrincipal,
            @PathVariable Long stepId,
            @RequestParam Integer position) {
        Long userId = userPrincipal.getUserId();
        log.info("User {} moving step id: {} to position: {}", userId, stepId, position);
        List<StepResponse> response = workFlowService.moveStep(stepId, position);

        Map<String, Object> result = new HashMap<>();
        result.put("success", true);
        result.put("message", "Step moved successfully");
        result.put("data", response);

        return ResponseEntity.ok(result);
    }

    /**
     * Clone a step
     *
     * Security: Requires JWT authentication - userId extracted from token
     */
    @PostMapping("/{stepId}/clone")
    public ResponseEntity<Map<String, Object>> cloneStep(
            @AuthenticationPrincipal UserPrincipal userPrincipal,
            @PathVariable Long stepId) {
        Long userId = userPrincipal.getUserId();
        log.info("User {} cloning step with id: {}", userId, stepId);
        StepResponse response = workFlowService.cloneStep(stepId);

        Map<String, Object> result = new HashMap<>();
        result.put("success", true);
        result.put("message", "Step cloned successfully");
        result.put("data", response);

        return ResponseEntity.status(HttpStatus.CREATED).body(result);
    }
}
