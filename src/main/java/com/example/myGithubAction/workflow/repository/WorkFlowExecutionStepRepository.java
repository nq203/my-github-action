package com.example.myGithubAction.workflow.repository;

import com.example.myGithubAction.common.ExecutionState;
import com.example.myGithubAction.workflow.entity.WorkFlowExecutionStep;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.*;

public interface WorkFlowExecutionStepRepository extends JpaRepository<WorkFlowExecutionStep, Long> {
    Optional<WorkFlowExecutionStep> findById(Long id);
    List<WorkFlowExecutionStep> findByExecutionId(Long executionId);
    List<WorkFlowExecutionStep> findByStatus(ExecutionState status);
    List<WorkFlowExecutionStep> findByExecutionIdOrderByStepOrder(Long executionId);
}
