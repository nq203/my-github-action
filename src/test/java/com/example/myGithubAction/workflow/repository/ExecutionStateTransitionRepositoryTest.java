package com.example.myGithubAction.workflow.repository;

import com.example.myGithubAction.common.ExecutionState;
import com.example.myGithubAction.workflow.entity.WorkFlowExecution;
import com.example.myGithubAction.workflow.entity.WorkFlowExecutionStep;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Runs the compare-and-set queries against a real (H2) database. Tests are not wrapped in a
 * transaction so every repository call commits, like the engine and the HTTP threads do.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class ExecutionStateTransitionRepositoryTest {

    @Autowired
    private WorkFlowExecutionRepository executionRepository;
    @Autowired
    private WorkFlowExecutionStepRepository stepRepository;

    @AfterEach
    void cleanUp() {
        stepRepository.deleteAll();
        executionRepository.deleteAll();
    }

    private Long newExecution(ExecutionState status) {
        return executionRepository.save(WorkFlowExecution.builder()
                .workflowId(1L).userId(1L).status(status).build()).getId();
    }

    private Long newStep(Long executionId, ExecutionState status) {
        return stepRepository.save(WorkFlowExecutionStep.builder()
                .executionId(executionId).stepId(1L).command("echo").stepOrder(0).status(status).build()).getId();
    }

    private ExecutionState status(Long id) {
        return executionRepository.findById(id).orElseThrow().getStatus();
    }

    @Test
    void claimPendingSucceedsOnceThenReturnsZero() {
        Long id = newExecution(ExecutionState.PENDING);

        assertEquals(1, executionRepository.claimPending(id, "w1", LocalDateTime.now()));
        assertEquals(0, executionRepository.claimPending(id, "w1", LocalDateTime.now()));
        assertEquals(ExecutionState.RUNNING, status(id));
        WorkFlowExecution claimed = executionRepository.findById(id).orElseThrow();
        assertNotNull(claimed.getStartedAt());
        assertEquals("w1", claimed.getWorkerId());
        assertNotNull(claimed.getHeartbeatAt());
    }

    @Test
    void cancelledWhilePendingCannotBeClaimed() {
        Long id = newExecution(ExecutionState.PENDING);

        assertEquals(1, executionRepository.transition(
                id, ExecutionState.CANCELLABLE, ExecutionState.CANCELLED, LocalDateTime.now(), null));

        assertEquals(0, executionRepository.claimPending(id, "w1", LocalDateTime.now()));
        assertEquals(ExecutionState.CANCELLED, status(id));
    }

    @Test
    void cancelWinsOverEngineCompletionAndFailure() {
        Long id = newExecution(ExecutionState.RUNNING);
        EnumSet<ExecutionState> running = EnumSet.of(ExecutionState.RUNNING);

        executionRepository.transition(id, ExecutionState.CANCELLABLE, ExecutionState.CANCELLED, LocalDateTime.now(), null);

        // The engine's late writes (race #1) are now harmless no-ops.
        assertEquals(0, executionRepository.transition(id, running, ExecutionState.FAILED, LocalDateTime.now(), "Cancelled"));
        assertEquals(0, executionRepository.transition(id, running, ExecutionState.SUCCESS, LocalDateTime.now(), null));
        assertEquals(ExecutionState.CANCELLED, status(id));
    }

    @Test
    void terminalStateIsNeverOverwrittenByCancel() {
        Long id = newExecution(ExecutionState.RUNNING);
        executionRepository.transition(id, EnumSet.of(ExecutionState.RUNNING), ExecutionState.SUCCESS, LocalDateTime.now(), null);

        assertEquals(0, executionRepository.transition(
                id, ExecutionState.CANCELLABLE, ExecutionState.CANCELLED, LocalDateTime.now(), null));
        assertEquals(ExecutionState.SUCCESS, status(id));
    }

    @Test
    void concurrentClaimsHaveExactlyOneWinner() throws Exception {
        Long id = newExecution(ExecutionState.PENDING);
        int workers = 8;
        ExecutorService pool = Executors.newFixedThreadPool(workers);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger winners = new AtomicInteger();
        List<Future<?>> futures = new ArrayList<>();
        for (int i = 0; i < workers; i++) {
            futures.add(pool.submit((Callable<Void>) () -> {
                start.await();
                winners.addAndGet(executionRepository.claimPending(id, "w1", LocalDateTime.now()));
                return null;
            }));
        }
        start.countDown();
        for (Future<?> f : futures) {
            f.get();
        }
        pool.shutdown();

        assertEquals(1, winners.get());
        assertEquals(ExecutionState.RUNNING, status(id));
    }

    @Test
    void stepTransitionDoesNotTouchLogs() {
        Long executionId = newExecution(ExecutionState.RUNNING);
        Long stepId = newStep(executionId, ExecutionState.PENDING);

        stepRepository.markRunning(stepId, LocalDateTime.now());
        stepRepository.appendLogLines(stepId, "Executing: echo hi\n", 1, false, LocalDateTime.now());
        // Race #3: the old engine save()d a stale entity here and wiped the logs.
        stepRepository.transition(stepId, EnumSet.of(ExecutionState.RUNNING), ExecutionState.SUCCESS,
                LocalDateTime.now(), 5L, null);

        WorkFlowExecutionStep step = stepRepository.findById(stepId).orElseThrow();
        assertEquals(ExecutionState.SUCCESS, step.getStatus());
        assertEquals("Executing: echo hi\n", step.getLogs());
        assertEquals(5L, step.getDuration());
    }

    @Test
    void concurrentLogAppendsLoseNothing() throws Exception {
        Long executionId = newExecution(ExecutionState.RUNNING);
        Long stepId = newStep(executionId, ExecutionState.RUNNING);
        int threads = 4;
        int linesPerThread = 25;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<?>> futures = new ArrayList<>();
        for (int t = 0; t < threads; t++) {
            int tid = t;
            futures.add(pool.submit((Callable<Void>) () -> {
                start.await();
                for (int i = 0; i < linesPerThread; i++) {
                    stepRepository.appendLogLines(stepId, "t" + tid + "-" + i + "\n", 1, false, LocalDateTime.now());
                }
                return null;
            }));
        }
        start.countDown();
        for (Future<?> f : futures) {
            f.get();
        }
        pool.shutdown();

        String logs = stepRepository.findById(stepId).orElseThrow().getLogs();
        assertEquals(threads * linesPerThread, logs.split("\n").length);
    }

    @Test
    void markRunningDoesNotReviveFinishedStep() {
        Long executionId = newExecution(ExecutionState.RUNNING);
        Long stepId = newStep(executionId, ExecutionState.SUCCESS);

        assertEquals(0, stepRepository.markRunning(stepId, LocalDateTime.now()));
        assertEquals(ExecutionState.SUCCESS, stepRepository.findById(stepId).orElseThrow().getStatus());
    }
}
