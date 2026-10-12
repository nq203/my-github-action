package com.example.myGithubAction.workflow.repository;

import com.example.myGithubAction.common.ExecutionState;
import com.example.myGithubAction.workflow.entity.WorkFlowExecutionStep;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;
import java.time.LocalDateTime;
import java.util.*;

public interface WorkFlowExecutionStepRepository extends JpaRepository<WorkFlowExecutionStep, Long> {
    Optional<WorkFlowExecutionStep> findById(Long id);
    List<WorkFlowExecutionStep> findByExecutionId(Long executionId);
    List<WorkFlowExecutionStep> findByStatus(ExecutionState status);
    List<WorkFlowExecutionStep> findByExecutionIdOrderByStepOrder(Long executionId);

    // ==================== TARGETED UPDATES ====================
    // The engine never save()s a loaded step: that would overwrite columns
    // (notably logs) written concurrently. Each method touches only its columns.

    /** PENDING|RUNNING → RUNNING (re-entered on retry). Returns 0 if the step already finished. */
    @Transactional
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("UPDATE WorkFlowExecutionStep s SET s.status = com.example.myGithubAction.common.ExecutionState.RUNNING, "
            + "s.startedAt = :now, s.endedAt = NULL, s.duration = NULL, s.updatedAt = :now "
            + "WHERE s.id = :id AND s.status IN (com.example.myGithubAction.common.ExecutionState.PENDING, "
            + "com.example.myGithubAction.common.ExecutionState.RUNNING)")
    int markRunning(@Param("id") Long id, @Param("now") LocalDateTime now);

    @Transactional
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("UPDATE WorkFlowExecutionStep s SET s.status = :to, s.endedAt = :endedAt, s.duration = :duration, "
            + "s.errorMessage = :errorMessage, s.updatedAt = :endedAt "
            + "WHERE s.id = :id AND s.status IN :from")
    int transition(@Param("id") Long id,
                   @Param("from") Collection<ExecutionState> from,
                   @Param("to") ExecutionState to,
                   @Param("endedAt") LocalDateTime endedAt,
                   @Param("duration") Long duration,
                   @Param("errorMessage") String errorMessage);

    /** Atomic append: concurrent appenders never lose each other's lines. */
    @Transactional
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("UPDATE WorkFlowExecutionStep s SET s.logs = CONCAT(COALESCE(s.logs, ''), :line), "
            + "s.updatedAt = :now WHERE s.id = :id")
    int appendLog(@Param("id") Long id, @Param("line") String line, @Param("now") LocalDateTime now);

    @Transactional
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("UPDATE WorkFlowExecutionStep s SET s.logs = :logs, s.updatedAt = :now WHERE s.id = :id")
    int updateLogs(@Param("id") Long id, @Param("logs") String logs, @Param("now") LocalDateTime now);
}
