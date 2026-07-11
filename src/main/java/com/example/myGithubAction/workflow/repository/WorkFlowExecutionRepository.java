package com.example.myGithubAction.workflow.repository;

import com.example.myGithubAction.common.ExecutionState;
import com.example.myGithubAction.workflow.entity.WorkFlowExecution;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.*;

public interface WorkFlowExecutionRepository extends JpaRepository<WorkFlowExecution, Long> {
    Optional<WorkFlowExecution> findById(Long id);
    List<WorkFlowExecution> findByWorkflowId(Long workflowId);
    List<WorkFlowExecution> findByUserId(Long userId);
    List<WorkFlowExecution> findByStatus(ExecutionState status);
    List<WorkFlowExecution> findByWorkflowIdAndUserId(Long workflowId, Long userId);
}
