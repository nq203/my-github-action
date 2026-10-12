package com.example.myGithubAction.workflow.repository;

import com.example.myGithubAction.common.ExecutionState;
import com.example.myGithubAction.workflow.entity.WorkFlowExecution;
import com.example.myGithubAction.workflow.entity.WorkFlowExecutionStep;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.data.domain.PageRequest;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/** Heartbeat / reaper / sweeper queries against a real (H2) database. */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class ExecutionLivenessRepositoryTest {

    private static final LocalDateTime NOW = LocalDateTime.now();

    @Autowired
    private WorkFlowExecutionRepository executionRepository;
    @Autowired
    private WorkFlowExecutionStepRepository stepRepository;

    @AfterEach
    void cleanUp() {
        stepRepository.deleteAll();
        executionRepository.deleteAll();
    }

    private WorkFlowExecution running(String workerId, LocalDateTime startedAt, LocalDateTime heartbeatAt) {
        return executionRepository.save(WorkFlowExecution.builder().workflowId(1L).userId(1L)
                .status(ExecutionState.RUNNING).workerId(workerId)
                .startedAt(startedAt).heartbeatAt(heartbeatAt).build());
    }

    private WorkFlowExecution pending(LocalDateTime lastDispatchedAt) {
        return executionRepository.save(WorkFlowExecution.builder().workflowId(1L).userId(1L)
                .status(ExecutionState.PENDING).lastDispatchedAt(lastDispatchedAt).build());
    }

    private ExecutionState status(Long id) {
        return executionRepository.findById(id).orElseThrow().getStatus();
    }

    // ---------- heartbeat ----------

    @Test
    void heartbeatRenewsOnlyExecutionsRunningUnderThatWorker() {
        WorkFlowExecution mine = running("w1", NOW.minusMinutes(5), NOW.minusMinutes(5));
        WorkFlowExecution others = running("w2", NOW.minusMinutes(5), NOW.minusMinutes(5));

        int renewed = executionRepository.heartbeat(Set.of(mine.getId(), others.getId()), "w1", NOW);

        assertEquals(1, renewed);
        assertTrue(executionRepository.findById(mine.getId()).orElseThrow().getHeartbeatAt().isAfter(NOW.minusSeconds(1)));
        assertTrue(executionRepository.findById(others.getId()).orElseThrow().getHeartbeatAt().isBefore(NOW.minusMinutes(1)));
    }

    @Test
    void heartbeatNeverRevivesAnExecutionThatIsNoLongerRunning() {
        WorkFlowExecution lost = running("w1", NOW.minusMinutes(5), NOW.minusMinutes(5));
        executionRepository.failIfStale(lost.getId(), NOW.minusMinutes(1), NOW, "lost");

        assertEquals(0, executionRepository.heartbeat(Set.of(lost.getId()), "w1", NOW));
        assertEquals(ExecutionState.FAILED, status(lost.getId()));
        assertEquals(List.of(), executionRepository.findOwnedRunningIds(Set.of(lost.getId()), "w1"));
    }

    // ---------- reaper ----------

    @Test
    void staleDetectionUsesHeartbeatAndFallsBackToStartTime() {
        LocalDateTime cutoff = NOW.minusMinutes(1);
        WorkFlowExecution deadWorker = running("w1", NOW.minusMinutes(10), NOW.minusMinutes(5));
        WorkFlowExecution alive = running("w2", NOW.minusMinutes(10), NOW);
        WorkFlowExecution legacyOld = running(null, NOW.minusMinutes(10), null);
        WorkFlowExecution legacyFresh = running(null, NOW, null);

        List<Long> stale = executionRepository.findStaleRunningIds(cutoff);

        assertTrue(stale.contains(deadWorker.getId()));
        assertTrue(stale.contains(legacyOld.getId()));
        assertFalse(stale.contains(alive.getId()), "a long-running job with a fresh heartbeat is not stale");
        assertFalse(stale.contains(legacyFresh.getId()));
    }

    @Test
    void failIfStaleLosesToALateHeartbeat() {
        LocalDateTime cutoff = NOW.minusMinutes(1);
        WorkFlowExecution execution = running("w1", NOW.minusMinutes(10), NOW.minusMinutes(5));
        assertTrue(executionRepository.findStaleRunningIds(cutoff).contains(execution.getId()));

        // The heartbeat lands between the SELECT and the UPDATE
        executionRepository.heartbeat(Set.of(execution.getId()), "w1", NOW);

        assertEquals(0, executionRepository.failIfStale(execution.getId(), cutoff, NOW, "lost"));
        assertEquals(ExecutionState.RUNNING, status(execution.getId()));
    }

    @Test
    void failIfStaleFailsOnceAndNeverTouchesFinishedExecutions() {
        LocalDateTime cutoff = NOW.minusMinutes(1);
        WorkFlowExecution execution = running("w1", NOW.minusMinutes(10), NOW.minusMinutes(5));

        assertEquals(1, executionRepository.failIfStale(execution.getId(), cutoff, NOW, "Worker lost"));
        assertEquals(0, executionRepository.failIfStale(execution.getId(), cutoff, NOW, "Worker lost"));

        WorkFlowExecution stored = executionRepository.findById(execution.getId()).orElseThrow();
        assertEquals(ExecutionState.FAILED, stored.getStatus());
        assertEquals("Worker lost", stored.getErrorMessage());
        assertNotNull(stored.getEndedAt());
    }

    @Test
    void failRunningStepsOnlyAffectsRunningStepsOfThatExecution() {
        WorkFlowExecution execution = running("w1", NOW, NOW);
        WorkFlowExecution other = running("w1", NOW, NOW);
        Long runningStep = step(execution.getId(), ExecutionState.RUNNING);
        Long doneStep = step(execution.getId(), ExecutionState.SUCCESS);
        Long pendingStep = step(execution.getId(), ExecutionState.PENDING);
        Long otherStep = step(other.getId(), ExecutionState.RUNNING);

        assertEquals(1, stepRepository.failRunningSteps(execution.getId(), NOW, "Worker lost"));

        assertEquals(ExecutionState.FAILED, stepRepository.findById(runningStep).orElseThrow().getStatus());
        assertEquals(ExecutionState.SUCCESS, stepRepository.findById(doneStep).orElseThrow().getStatus());
        assertEquals(ExecutionState.PENDING, stepRepository.findById(pendingStep).orElseThrow().getStatus());
        assertEquals(ExecutionState.RUNNING, stepRepository.findById(otherStep).orElseThrow().getStatus());
    }

    private Long step(Long executionId, ExecutionState status) {
        return stepRepository.save(WorkFlowExecutionStep.builder().executionId(executionId).stepId(1L)
                .command("echo").stepOrder(0).status(status).build()).getId();
    }

    // ---------- sweeper ----------

    @Test
    void staleDispatchedPendingIsFoundButRecentOrNonPendingIsNot() {
        LocalDateTime cutoff = NOW.minusMinutes(5);
        WorkFlowExecution lost = pending(NOW.minusMinutes(30));
        WorkFlowExecution queued = pending(NOW.minusSeconds(10));
        WorkFlowExecution cancelled = executionRepository.save(WorkFlowExecution.builder().workflowId(1L).userId(1L)
                .status(ExecutionState.CANCELLED).lastDispatchedAt(NOW.minusMinutes(30)).build());

        List<Long> stale = executionRepository.findStalePendingIds(cutoff, PageRequest.of(0, 10));

        assertTrue(stale.contains(lost.getId()));
        assertFalse(stale.contains(queued.getId()));
        assertFalse(stale.contains(cancelled.getId()));
    }

    @Test
    void neverDispatchedPendingFallsBackToCreationTime() {
        WorkFlowExecution fresh = pending(null); // createdAt = now

        assertFalse(executionRepository.findStalePendingIds(NOW.minusMinutes(5), PageRequest.of(0, 10))
                .contains(fresh.getId()));
        assertTrue(executionRepository.findStalePendingIds(LocalDateTime.now().plusMinutes(1), PageRequest.of(0, 10))
                .contains(fresh.getId()));
    }

    @Test
    void sweeperBatchIsBounded() {
        for (int i = 0; i < 5; i++) {
            pending(NOW.minusMinutes(30));
        }

        assertEquals(3, executionRepository.findStalePendingIds(NOW.minusMinutes(5), PageRequest.of(0, 3)).size());
    }

    @Test
    void onlyOneOfManySweepersWinsTheRepublish() throws Exception {
        WorkFlowExecution lost = pending(NOW.minusMinutes(30));
        LocalDateTime cutoff = NOW.minusMinutes(5);
        int sweepers = 8;
        ExecutorService pool = Executors.newFixedThreadPool(sweepers);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger winners = new AtomicInteger();
        List<Future<?>> futures = new ArrayList<>();
        for (int i = 0; i < sweepers; i++) {
            futures.add(pool.submit((Callable<Void>) () -> {
                start.await();
                winners.addAndGet(executionRepository.claimRedispatch(lost.getId(), cutoff, LocalDateTime.now()));
                return null;
            }));
        }
        start.countDown();
        for (Future<?> f : futures) {
            f.get();
        }
        pool.shutdown();

        assertEquals(1, winners.get());
    }

    @Test
    void redispatchIsNotPossibleOnceClaimed() {
        WorkFlowExecution execution = pending(NOW.minusMinutes(30));
        executionRepository.claimPending(execution.getId(), "w1", NOW);

        assertEquals(0, executionRepository.claimRedispatch(execution.getId(), NOW.minusMinutes(5), NOW));
        assertEquals(0, executionRepository.markDispatched(execution.getId(), NOW));
    }
}
