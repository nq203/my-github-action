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
    /** Steps of several executions in one query (live-monitor reconcile). */
    List<WorkFlowExecutionStep> findByExecutionIdIn(Collection<Long> executionIds);

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

    /**
     * Atomic append of already newline-terminated text plus the matching line count and truncation
     * flag, in one UPDATE. Concurrent appenders never lose each other's lines.
     */
    @Transactional
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("UPDATE WorkFlowExecutionStep s SET s.logs = CONCAT(COALESCE(s.logs, ''), :text), "
            + "s.logLineCount = s.logLineCount + :addedLines, "
            + "s.logTruncated = CASE WHEN :truncated = true THEN true ELSE s.logTruncated END, "
            + "s.updatedAt = :now WHERE s.id = :id")
    int appendLogLines(@Param("id") Long id, @Param("text") String text, @Param("addedLines") int addedLines,
                       @Param("truncated") boolean truncated, @Param("now") LocalDateTime now);

    /** Current log counters of a step (line count, stored chars, truncated flag). */
    @Query("SELECT s.logLineCount AS lineCount, LENGTH(COALESCE(s.logs, '')) AS charCount, "
            + "s.logTruncated AS truncated FROM WorkFlowExecutionStep s WHERE s.id = :id")
    Optional<LogState> findLogState(@Param("id") Long id);

    interface LogState {
        Integer getLineCount();

        Integer getCharCount();

        Boolean getTruncated();
    }

    @Transactional
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("UPDATE WorkFlowExecutionStep s SET s.logs = :logs, s.updatedAt = :now WHERE s.id = :id")
    int updateLogs(@Param("id") Long id, @Param("logs") String logs, @Param("now") LocalDateTime now);

    /** Fails every step of an execution still marked RUNNING (its worker is gone). */
    @Transactional
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("UPDATE WorkFlowExecutionStep s SET s.status = com.example.myGithubAction.common.ExecutionState.FAILED, "
            + "s.endedAt = :now, s.errorMessage = :message, s.updatedAt = :now "
            + "WHERE s.executionId = :executionId "
            + "AND s.status = com.example.myGithubAction.common.ExecutionState.RUNNING")
    int failRunningSteps(@Param("executionId") Long executionId, @Param("now") LocalDateTime now,
                         @Param("message") String message);
}
