package com.example.myGithubAction.workflow.repository;

import com.example.myGithubAction.workflow.entity.WorkFlow;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.*;

/*
 * WorkFlowRepository
 */
public interface WorkFlowRepository extends JpaRepository<WorkFlow, Long> {
    Optional<WorkFlow> findById(Long id);
    List<WorkFlow> findByUserId(Long userId);
    
}
