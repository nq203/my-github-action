package com.example.myGithubAction.workflow.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import com.example.myGithubAction.workflow.entity.Step;
import java.util.*;

public interface StepRepository extends JpaRepository<Step, Long> {
    Optional<Step> findById(Long id);

    @Query("SELECT s FROM Step s WHERE s.workflowId = ?1 ORDER BY s.stepOrder ASC")
    List<Step> findByWorkflowId(Long workflowId);
}
