package com.example.myGithubAction.workflow.controller;

import org.springframework.data.repository.query.Param;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import com.example.myGithubAction.auth.entity.UserPrincipal;
import com.example.myGithubAction.common.ExecutionState;
import com.example.myGithubAction.workflow.dto.WorkFlowExecutionRequest;
import com.example.myGithubAction.workflow.dto.WorkFlowExecutionResponse;
import com.example.myGithubAction.workflow.dto.WorkFlowExecutionStepResponse;
import com.example.myGithubAction.workflow.dto.ExecutionStatusUpdateRequest;
import com.example.myGithubAction.workflow.service.WorkFlowExecutionService;

import java.util.List;

@RestController
@RequestMapping("/api/workflow-executions")
public class WorkFlowExecutionController {

    private final WorkFlowExecutionService executionService;

    public WorkFlowExecutionController(WorkFlowExecutionService executionService) {
        this.executionService = executionService;
    }

    // ==================== EXECUTION ENDPOINTS ====================

    /**
     * Get all executions for current user
     * GET /api/workflow-executions (alias for frontend /api/executions)
     */
    @GetMapping
    public ResponseEntity<List<WorkFlowExecutionResponse>> getAllExecutions(
            @AuthenticationPrincipal UserPrincipal userPrincipal) {
        List<WorkFlowExecutionResponse> response = executionService.getExecutionsByUserId(userPrincipal.getUserId());
        return ResponseEntity.ok(response);
    }

    /**
     * Create and start a new workflow execution
     * POST /api/workflow-executions/start
     *
     * This endpoint:
     * 1. Creates a new execution record (status: PENDING)
     * 2. Creates execution steps for all workflow steps
     * 3. Triggers ExecutionEngine to run the execution
     */
    @PostMapping("/start")
    public ResponseEntity<WorkFlowExecutionResponse> startExecution(
            @AuthenticationPrincipal UserPrincipal userPrincipal,
            @RequestBody WorkFlowExecutionRequest request) {
        WorkFlowExecutionResponse response = executionService.createExecution(userPrincipal.getUserId(), request);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    /**
     * Get execution by ID
     * GET /api/workflow-executions/{id}
     */
    @GetMapping("/{id}")
    public ResponseEntity<WorkFlowExecutionResponse> getExecution(@PathVariable Long id) {
        WorkFlowExecutionResponse response = executionService.getExecution(id);
        return ResponseEntity.ok(response);
    }

    /**
     * Get all executions for a workflow
     * GET /api/workflow-executions/workflow/{workflowId}
     */
    @GetMapping("/workflow/{workflowId}")
    public ResponseEntity<List<WorkFlowExecutionResponse>> getExecutionsByWorkflow(@PathVariable Long workflowId) {
        List<WorkFlowExecutionResponse> response = executionService.getExecutionsByWorkflowId(workflowId);
        return ResponseEntity.ok(response);
    }

    /**
     * Get all executions for current user
     * GET /api/workflow-executions/user/me
     */
    @GetMapping("/user/me")
    public ResponseEntity<List<WorkFlowExecutionResponse>> getMyExecutions(@AuthenticationPrincipal UserPrincipal userPrincipal) {
        List<WorkFlowExecutionResponse> response = executionService.getExecutionsByUserId(userPrincipal.getUserId());
        return ResponseEntity.ok(response);
    }

    /**
     * Get executions by status
     * GET /api/workflow-executions/status/{status}
     */
    @GetMapping("/status/{status}")
    public ResponseEntity<List<WorkFlowExecutionResponse>> getExecutionsByStatus(@PathVariable ExecutionState status) {
        List<WorkFlowExecutionResponse> response = executionService.getExecutionsByStatus(status);
        return ResponseEntity.ok(response);
    }

    /**
     * Update execution status
     * PATCH /api/workflow-executions/{id}/status
     */
    @PatchMapping("/{id}/status")
    public ResponseEntity<WorkFlowExecutionResponse> updateExecutionStatus(
            @PathVariable Long id,
            @RequestBody ExecutionStatusUpdateRequest request) {
        WorkFlowExecutionResponse response = executionService.updateExecutionStatus(id, request);
        return ResponseEntity.ok(response);
    }

    /**
     * Cancel an execution
     * POST /api/workflow-executions/{id}/cancel
     */
    @PostMapping("/{id}/cancel")
    public ResponseEntity<WorkFlowExecutionResponse> cancelExecution(@PathVariable Long id) {
        WorkFlowExecutionResponse response = executionService.cancelExecution(id);
        return ResponseEntity.ok(response);
    }

    /**
     * Delete an execution
     * DELETE /api/workflow-executions/{id}
     */
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deleteExecution(@PathVariable Long id) {
        executionService.deleteExecution(id);
        return ResponseEntity.noContent().build();
    }

    // ==================== EXECUTION STEP ENDPOINTS ====================

    /**
     * Get execution steps for an execution
     * GET /api/workflow-executions/{id}/steps
     */
    @GetMapping("/{id}/steps")
    public ResponseEntity<List<WorkFlowExecutionStepResponse>> getExecutionSteps(@PathVariable Long id) {
        List<WorkFlowExecutionStepResponse> response = executionService.getExecutionSteps(id);
        return ResponseEntity.ok(response);
    }

    /**
     * Get a specific execution step
     * GET /api/workflow-executions/steps/{stepId}
     */
    @GetMapping("/steps/{stepId}")
    public ResponseEntity<WorkFlowExecutionStepResponse> getExecutionStep(@PathVariable Long stepId) {
        WorkFlowExecutionStepResponse response = executionService.getExecutionStep(stepId);
        return ResponseEntity.ok(response);
    }

    /**
     * Update execution step status
     * PATCH /api/workflow-executions/steps/{stepId}/status
     */
    @PatchMapping("/steps/{stepId}/status")
    public ResponseEntity<WorkFlowExecutionStepResponse> updateExecutionExecutionState(
            @PathVariable Long stepId,
            @RequestBody ExecutionStatusUpdateRequest request) {
        WorkFlowExecutionStepResponse response = executionService.updateExecutionExecutionState(stepId, request);
        return ResponseEntity.ok(response);
    }

    /**
     * Get execution steps by status
     * GET /api/workflow-executions/steps/status/{status}
     */
    @GetMapping("/steps/status/{status}")
    public ResponseEntity<List<WorkFlowExecutionStepResponse>> getExecutionStepsByStatus(@PathVariable ExecutionState status) {
        List<WorkFlowExecutionStepResponse> response = executionService.getExecutionStepsByStatus(status);
        return ResponseEntity.ok(response);
    }
}
