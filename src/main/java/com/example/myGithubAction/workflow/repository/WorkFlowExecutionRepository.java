package com.example.myGithubAction.workflow.repository;

import com.example.myGithubAction.common.ExecutionState;
import com.example.myGithubAction.workflow.entity.WorkFlowExecution;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;
import java.time.LocalDateTime;
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

    // ==================== COMPARE-AND-SET STATE TRANSITIONS ====================
    // Each method returns the number of rows updated: 0 means the execution was
    // not in an expected state (lost the race) and nothing was changed.

    /** PENDING → RUNNING, owned by {@code workerId}. Only the caller that gets 1 owns the execution. */
    @Transactional
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("UPDATE WorkFlowExecution e SET e.status = com.example.myGithubAction.common.ExecutionState.RUNNING, "
            + "e.startedAt = :now, e.heartbeatAt = :now, e.workerId = :workerId, e.updatedAt = :now "
            + "WHERE e.id = :id AND e.status = com.example.myGithubAction.common.ExecutionState.PENDING")
    int claimPending(@Param("id") Long id, @Param("workerId") String workerId, @Param("now") LocalDateTime now);

    /** Moves an execution to {@code to} only if it is currently in one of the {@code from} states. */
    @Transactional
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("UPDATE WorkFlowExecution e SET e.status = :to, e.endedAt = :endedAt, "
            + "e.errorMessage = :errorMessage, e.updatedAt = :endedAt "
            + "WHERE e.id = :id AND e.status IN :from")
    int transition(@Param("id") Long id,
                   @Param("from") Collection<ExecutionState> from,
                   @Param("to") ExecutionState to,
                   @Param("endedAt") LocalDateTime endedAt,
                   @Param("errorMessage") String errorMessage);

    @Transactional
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("UPDATE WorkFlowExecution e SET e.logs = :logs, e.updatedAt = :now WHERE e.id = :id")
    int updateLogs(@Param("id") Long id, @Param("logs") String logs, @Param("now") LocalDateTime now);

    /** Row-locks the execution so a concurrent transition waits until the caller's transaction ends. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT e FROM WorkFlowExecution e WHERE e.id = :id")
    Optional<WorkFlowExecution> findByIdForUpdate(@Param("id") Long id);

    // ==================== LIVENESS (heartbeat / reaper / sweeper) ====================

    /** Renews the heartbeat of the given executions that are still RUNNING under {@code workerId}. */
    @Transactional
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("UPDATE WorkFlowExecution e SET e.heartbeatAt = :now "
            + "WHERE e.id IN :ids AND e.workerId = :workerId "
            + "AND e.status = com.example.myGithubAction.common.ExecutionState.RUNNING")
    int heartbeat(@Param("ids") Collection<Long> ids, @Param("workerId") String workerId,
                  @Param("now") LocalDateTime now);

    /** Which of {@code ids} are still RUNNING under {@code workerId}. */
    @Query("SELECT e.id FROM WorkFlowExecution e WHERE e.id IN :ids AND e.workerId = :workerId "
            + "AND e.status = com.example.myGithubAction.common.ExecutionState.RUNNING")
    List<Long> findOwnedRunningIds(@Param("ids") Collection<Long> ids, @Param("workerId") String workerId);

    @Query("SELECT e.id FROM WorkFlowExecution e WHERE e.workerId = :workerId "
            + "AND e.status = com.example.myGithubAction.common.ExecutionState.RUNNING")
    List<Long> findRunningIdsByWorker(@Param("workerId") String workerId);

    /** RUNNING executions whose last sign of life (heartbeat, else start) is older than {@code cutoff}. */
    @Query("SELECT e.id FROM WorkFlowExecution e "
            + "WHERE e.status = com.example.myGithubAction.common.ExecutionState.RUNNING "
            + "AND COALESCE(e.heartbeatAt, e.startedAt) < :cutoff")
    List<Long> findStaleRunningIds(@Param("cutoff") LocalDateTime cutoff);

    /** RUNNING → FAILED, but only if it is still stale at the moment of the update (a late heartbeat wins). */
    @Transactional
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("UPDATE WorkFlowExecution e SET e.status = com.example.myGithubAction.common.ExecutionState.FAILED, "
            + "e.endedAt = :now, e.errorMessage = :message, e.updatedAt = :now "
            + "WHERE e.id = :id AND e.status = com.example.myGithubAction.common.ExecutionState.RUNNING "
            + "AND COALESCE(e.heartbeatAt, e.startedAt) < :cutoff")
    int failIfStale(@Param("id") Long id, @Param("cutoff") LocalDateTime cutoff,
                    @Param("now") LocalDateTime now, @Param("message") String message);

    /** PENDING executions whose START was last published (or created) before {@code cutoff}. */
    @Query("SELECT e.id FROM WorkFlowExecution e "
            + "WHERE e.status = com.example.myGithubAction.common.ExecutionState.PENDING "
            + "AND COALESCE(e.lastDispatchedAt, e.createdAt) < :cutoff ORDER BY e.id")
    List<Long> findStalePendingIds(@Param("cutoff") LocalDateTime cutoff, Pageable pageable);

    /** Records that a START was published. Only while PENDING. */
    @Transactional
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("UPDATE WorkFlowExecution e SET e.lastDispatchedAt = :now "
            + "WHERE e.id = :id AND e.status = com.example.myGithubAction.common.ExecutionState.PENDING")
    int markDispatched(@Param("id") Long id, @Param("now") LocalDateTime now);

    /**
     * Wins the right to re-publish a stale PENDING execution: of several sweepers, exactly one
     * gets 1 and re-publishes, so a backlog is not amplified by the number of workers.
     */
    @Transactional
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("UPDATE WorkFlowExecution e SET e.lastDispatchedAt = :now "
            + "WHERE e.id = :id AND e.status = com.example.myGithubAction.common.ExecutionState.PENDING "
            + "AND COALESCE(e.lastDispatchedAt, e.createdAt) < :cutoff")
    int claimRedispatch(@Param("id") Long id, @Param("cutoff") LocalDateTime cutoff, @Param("now") LocalDateTime now);
}
