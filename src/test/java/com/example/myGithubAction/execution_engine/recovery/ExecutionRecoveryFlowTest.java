package com.example.myGithubAction.execution_engine.recovery;

import com.example.myGithubAction.common.ExecutionState;
import com.example.myGithubAction.execution_engine.core.WorkspaceManager;
import com.example.myGithubAction.workflow.dto.WorkFlowExecutionRequest;
import com.example.myGithubAction.workflow.entity.Step;
import com.example.myGithubAction.workflow.entity.WorkFlow;
import com.example.myGithubAction.workflow.entity.WorkFlowExecution;
import com.example.myGithubAction.workflow.entity.WorkFlowExecutionStep;
import com.example.myGithubAction.workflow.repository.StepRepository;
import com.example.myGithubAction.workflow.repository.WorkFlowExecutionRepository;
import com.example.myGithubAction.workflow.repository.WorkFlowExecutionStepRepository;
import com.example.myGithubAction.workflow.repository.WorkFlowRepository;
import com.example.myGithubAction.workflow.service.WorkFlowExecutionService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.kafka.test.context.EmbeddedKafka;

import java.nio.file.Files;
import java.time.LocalDateTime;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Failure scenarios of a multi-worker deployment, with short timeouts:
 * a worker that dies, a START that is lost, a job that outlives the stale timeout,
 * and a process the system has already declared lost.
 */
@SpringBootTest(properties = {
        "app.kafka.enabled=true",
        "app.kafka.consumer.enabled=true",
        "app.kafka.worker.cancel-poll-ms=60000",
        "app.worker.heartbeat-interval-ms=300",
        "app.worker.stale-after-ms=3000",
        "app.worker.reaper-interval-ms=300",
        "app.worker.sweep-interval-ms=300",
        "app.worker.pending-republish-after-ms=1000",
        "app.worker.workspace-sweep-interval-ms=500",
        "spring.kafka.bootstrap-servers=${spring.embedded.kafka.brokers}"
})
@EmbeddedKafka(partitions = 1, kraft = true)
class ExecutionRecoveryFlowTest {

    @Autowired
    private WorkFlowExecutionService service;
    @Autowired
    private WorkFlowRepository workflowRepository;
    @Autowired
    private StepRepository stepRepository;
    @Autowired
    private WorkFlowExecutionRepository executionRepository;
    @Autowired
    private WorkFlowExecutionStepRepository executionStepRepository;
    @Autowired
    private WorkspaceManager workspaceManager;

    private Long workflowWithCommands(String... commands) {
        WorkFlow workflow = workflowRepository.save(WorkFlow.builder().userId(1L).name("wf").build());
        for (int i = 0; i < commands.length; i++) {
            stepRepository.save(Step.builder().workflowId(workflow.getId()).name("s" + i)
                    .stepOrder(i).command(commands[i]).build());
        }
        return workflow.getId();
    }

    private Long start(Long workflowId) {
        return service.createExecution(1L, WorkFlowExecutionRequest.builder().workflowId(workflowId).build()).getId();
    }

    private ExecutionState status(Long executionId) {
        return executionRepository.findById(executionId).orElseThrow().getStatus();
    }

    private void awaitStatus(Long executionId, ExecutionState expected) throws InterruptedException {
        await(() -> status(executionId) == expected,
                "execution " + executionId + " should reach " + expected + " but is " + status(executionId));
    }

    private void await(Supplier<Boolean> condition, String failureMessage) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 30_000;
        while (System.currentTimeMillis() < deadline) {
            if (condition.get()) {
                return;
            }
            Thread.sleep(100);
        }
        fail(failureMessage);
    }

    private ExecutionState stepStatus(Long executionId) {
        return executionStepRepository.findByExecutionIdOrderByStepOrder(executionId).get(0).getStatus();
    }

    @Test
    void executionOfADeadWorkerIsFailedWhileALiveWorkersExecutionSurvives() throws Exception {
        LocalDateTime longAgo = LocalDateTime.now().minusHours(1);
        WorkFlowExecution dead = executionRepository.save(WorkFlowExecution.builder().workflowId(1L).userId(1L)
                .status(ExecutionState.RUNNING).workerId("dead-worker").startedAt(longAgo).heartbeatAt(longAgo).build());
        executionStepRepository.save(WorkFlowExecutionStep.builder().executionId(dead.getId()).stepId(1L)
                .command("sleep 60").stepOrder(0).status(ExecutionState.RUNNING).build());
        WorkFlowExecution alive = executionRepository.save(WorkFlowExecution.builder().workflowId(1L).userId(1L)
                .status(ExecutionState.RUNNING).workerId("other-live-worker")
                .startedAt(longAgo).heartbeatAt(LocalDateTime.now()).build());
        workspaceManager.createWorkspace(dead.getId());
        workspaceManager.createWorkspace(alive.getId());

        // The other live worker keeps heartbeating its execution, like ExecutionHeartbeat does.
        AtomicBoolean otherWorkerUp = new AtomicBoolean(true);
        Thread otherWorker = new Thread(() -> {
            while (otherWorkerUp.get()) {
                executionRepository.heartbeat(Set.of(alive.getId()), "other-live-worker", LocalDateTime.now());
                try {
                    Thread.sleep(300);
                } catch (InterruptedException e) {
                    return;
                }
            }
        });
        otherWorker.start();
        try {
            awaitStatus(dead.getId(), ExecutionState.FAILED);
            await(() -> !Files.exists(workspaceManager.resolveWorkspacePath(dead.getId())),
                    "workspace of the lost execution should be cleaned up");
            Thread.sleep(1500); // several reaper + janitor passes after the dead one was handled

            WorkFlowExecution failed = executionRepository.findById(dead.getId()).orElseThrow();
            assertTrue(failed.getErrorMessage().contains("Worker lost"));
            assertEquals(ExecutionState.FAILED, stepStatus(dead.getId()));
            assertEquals(ExecutionState.RUNNING, status(alive.getId()), "a live worker's job must not be touched");
            assertTrue(Files.exists(workspaceManager.resolveWorkspacePath(alive.getId())));
        } finally {
            otherWorkerUp.set(false);
            otherWorker.join();
        }
    }

    @Test
    void startThatWasNeverPublishedIsRepublishedAndRuns() throws Exception {
        Long workflowId = workflowWithCommands("echo recovered");
        Step definition = stepRepository.findByWorkflowId(workflowId).get(0);
        // Committed PENDING execution whose START was lost (process died before publishing)
        WorkFlowExecution lost = executionRepository.save(WorkFlowExecution.builder().workflowId(workflowId).userId(1L)
                .status(ExecutionState.PENDING).lastDispatchedAt(LocalDateTime.now().minusHours(1)).build());
        executionStepRepository.save(WorkFlowExecutionStep.builder().executionId(lost.getId())
                .stepId(definition.getId()).command("echo recovered").stepOrder(0)
                .status(ExecutionState.PENDING).build());

        awaitStatus(lost.getId(), ExecutionState.SUCCESS);
    }

    @Test
    void freshlyQueuedExecutionIsNotRepublishedImmediately() throws Exception {
        // Dispatched a moment ago, nobody has claimed it yet: the sweeper must leave it alone.
        WorkFlowExecution queued = executionRepository.save(WorkFlowExecution.builder().workflowId(1L).userId(1L)
                .status(ExecutionState.PENDING).lastDispatchedAt(LocalDateTime.now().plusMinutes(10)).build());

        Thread.sleep(2000);

        assertEquals(queued.getLastDispatchedAt().withNano(0),
                executionRepository.findById(queued.getId()).orElseThrow().getLastDispatchedAt().withNano(0));
    }

    @Test
    void longRunningJobIsKeptAliveByItsHeartbeat() throws Exception {
        // The job outlives app.worker.stale-after-ms (3s): only heartbeats keep it from being reaped.
        Long executionId = start(workflowWithCommands("sleep 6"));

        awaitStatus(executionId, ExecutionState.SUCCESS);

        WorkFlowExecution finished = executionRepository.findById(executionId).orElseThrow();
        assertNull(finished.getErrorMessage());
        assertNotNull(finished.getWorkerId());
        assertNotNull(finished.getLastDispatchedAt(), "dispatch time must be recorded after commit");
        assertTrue(finished.getHeartbeatAt().isAfter(finished.getStartedAt().plusSeconds(3)),
                "heartbeat must have advanced while the job ran");
    }

    @Test
    void workerStopsAnExecutionThatTheSystemAlreadyDeclaredLost() throws Exception {
        Long executionId = start(workflowWithCommands("sleep 60"));
        awaitStatus(executionId, ExecutionState.RUNNING);
        await(() -> stepStatus(executionId) == ExecutionState.RUNNING, "step should be running");

        // What a reaper on another instance would do after a network partition
        executionRepository.transition(executionId, Set.of(ExecutionState.RUNNING), ExecutionState.FAILED,
                LocalDateTime.now(), "Worker lost: declared by another instance");

        await(() -> stepStatus(executionId) != ExecutionState.RUNNING,
                "the 60s process must be killed once its execution is no longer RUNNING under this worker");
        Thread.sleep(1000);
        WorkFlowExecution stored = executionRepository.findById(executionId).orElseThrow();
        assertEquals(ExecutionState.FAILED, stored.getStatus());
        assertEquals("Worker lost: declared by another instance", stored.getErrorMessage());
    }
}
