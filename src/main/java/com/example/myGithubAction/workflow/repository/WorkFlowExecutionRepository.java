package com.example.myGithubAction.workflow.repository;

import com.example.myGithubAction.common.ExecutionState;
import com.example.myGithubAction.workflow.entity.WorkFlowExecution;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import java.util.*;

public interface WorkFlowExecutionRepository extends JpaRepository<WorkFlowExecution, Long> {
    Optional<WorkFlowExecution> findById(Long id);
    List<WorkFlowExecution> findByWorkflowId(Long workflowId);
    List<WorkFlowExecution> findByUserId(Long userId);
    List<WorkFlowExecution> findByStatus(ExecutionState status);
    List<WorkFlowExecution> findByWorkflowIdAndUserId(Long workflowId, Long userId);

    @Query("SELECT DISTINCT e FROM WorkFlowExecution e LEFT JOIN FETCH e.executionSteps es WHERE e.id = :id ORDER BY es.stepOrder ASC")
    Optional<WorkFlowExecution> findByIdWithSteps(@Param("id") Long id);

    @Query("SELECT DISTINCT e FROM WorkFlowExecution e LEFT JOIN FETCH e.executionSteps es WHERE e.workflowId = :workflowId ORDER BY e.id DESC, es.stepOrder ASC")
    List<WorkFlowExecution> findByWorkflowIdWithSteps(@Param("workflowId") Long workflowId);

    @Query("SELECT DISTINCT e FROM WorkFlowExecution e LEFT JOIN FETCH e.executionSteps es WHERE e.userId = :userId ORDER BY e.id DESC, es.stepOrder ASC")
    List<WorkFlowExecution> findByUserIdWithSteps(@Param("userId") Long userId);

    @Query("SELECT DISTINCT e FROM WorkFlowExecution e LEFT JOIN FETCH e.executionSteps es WHERE e.status = :status ORDER BY e.id DESC, es.stepOrder ASC")
    List<WorkFlowExecution> findByStatusWithSteps(@Param("status") ExecutionState status);
}
