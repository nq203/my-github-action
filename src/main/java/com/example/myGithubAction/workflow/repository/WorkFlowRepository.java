package com.example.myGithubAction.workflow.repository;

import com.example.myGithubAction.workflow.entity.WorkFlow;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import java.util.*;

/*
 * WorkFlowRepository
 */
public interface WorkFlowRepository extends JpaRepository<WorkFlow, Long> {
    Optional<WorkFlow> findById(Long id);
    List<WorkFlow> findByUserId(Long userId);

    @Query("SELECT DISTINCT w FROM WorkFlow w LEFT JOIN FETCH w.steps s WHERE w.id = :id ORDER BY s.stepOrder ASC")
    Optional<WorkFlow> findByIdWithSteps(@Param("id") Long id);

    @Query("SELECT DISTINCT w FROM WorkFlow w LEFT JOIN FETCH w.steps s WHERE w.userId = :userId ORDER BY w.id DESC, s.stepOrder ASC")
    List<WorkFlow> findByUserIdWithSteps(@Param("userId") Long userId);
}

