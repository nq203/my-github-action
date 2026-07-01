package com.example.myGithubAction.workflow.controller;

import com.example.myGithubAction.auth.security.JwtTokenProvider;
import com.example.myGithubAction.workflow.dto.CreateWorkFlowRequest;
import com.example.myGithubAction.workflow.dto.WorkFlowResponse;
import com.example.myGithubAction.workflow.service.WorkFlowService;
import jakarta.validation.Valid;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import com.example.myGithubAction.auth.entity.UserPrincipal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/workflows")
@Slf4j
public class WorkFlowController {

    private final WorkFlowService workFlowService;

    private final JwtTokenProvider jwtTokenProvider;

    public WorkFlowController(WorkFlowService workFlowService, JwtTokenProvider jwtTokenProvider) {
        this.jwtTokenProvider = jwtTokenProvider;
        this.workFlowService = workFlowService;
    }

    /**
     * Create a new workflow
     * get userId from  token and pass it to the service layer
     */
    @PostMapping
    public ResponseEntity<Map<String, Object>> createWorkflow(
            @AuthenticationPrincipal UserPrincipal userPrincipal,
            @RequestParam Long userId,
            @Valid @RequestBody CreateWorkFlowRequest request) {
        // Long userId = userPrincipal.getUserId();
        if(userId == null) {
            log.error("User ID is null in the token");
            Map<String, Object> errorResult = new HashMap<>();
            errorResult.put("success", false);
            errorResult.put("message", "User ID is missing in the token");
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(errorResult);
        }
        log.info("Creating workflow for userId: {}", userId);
        WorkFlowResponse response = workFlowService.createWorkflow(userId, request);

        Map<String, Object> result = new HashMap<>();
        result.put("success", true);
        result.put("message", "Workflow created successfully");
        result.put("data", response);

        return ResponseEntity.status(HttpStatus.CREATED).body(result);
    }

    /**
     * Get workflow by ID
     */
    @GetMapping("/{id}")
    public ResponseEntity<Map<String, Object>> getWorkflow(@PathVariable Long id) {
        log.info("Getting workflow with id: {}", id);
        WorkFlowResponse response = workFlowService.getWorkflow(id);

        Map<String, Object> result = new HashMap<>();
        result.put("success", true);
        result.put("data", response);

        return ResponseEntity.ok(result);
    }

    /**
     * Get all workflows for a user
     */
    @GetMapping("/user/{userId}")
    public ResponseEntity<Map<String, Object>> getWorkflowsByUserId(@PathVariable Long userId) {
        log.info("Getting workflows for userId: {}", userId);
        List<WorkFlowResponse> response = workFlowService.getWorkflowsByUserId(userId);

        Map<String, Object> result = new HashMap<>();
        result.put("success", true);
        result.put("message", "Retrieved " + response.size() + " workflows");
        result.put("data", response);

        return ResponseEntity.ok(result);
    }

    /**
     * Update a workflow
     */
    @PutMapping("/{id}")
    public ResponseEntity<Map<String, Object>> updateWorkflow(
            @PathVariable Long id,
            @Valid @RequestBody CreateWorkFlowRequest request) {
        log.info("Updating workflow with id: {}", id);
        WorkFlowResponse response = workFlowService.updateWorkflow(id, request);

        Map<String, Object> result = new HashMap<>();
        result.put("success", true);
        result.put("message", "Workflow updated successfully");
        result.put("data", response);

        return ResponseEntity.ok(result);
    }

    /**
     * Delete a workflow
     */
    @DeleteMapping("/{id}")
    public ResponseEntity<Map<String, Object>> deleteWorkflow(@PathVariable Long id) {
        log.info("Deleting workflow with id: {}", id);
        workFlowService.deleteWorkflow(id);

        Map<String, Object> result = new HashMap<>();
        result.put("success", true);
        result.put("message", "Workflow deleted successfully");

        return ResponseEntity.ok(result);
    }
}
