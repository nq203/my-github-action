package com.example.myGithubAction.execution_engine.logging;

import com.example.myGithubAction.common.ExecutionState;
import com.example.myGithubAction.workflow.entity.WorkFlowExecution;
import com.example.myGithubAction.workflow.entity.WorkFlowExecutionStep;
import com.example.myGithubAction.workflow.repository.WorkFlowExecutionRepository;
import com.example.myGithubAction.workflow.repository.WorkFlowExecutionStepRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@Import(StepLogStore.class)
class StepLogStoreTest {

    @Autowired
    private StepLogStore store;
    @Autowired
    private WorkFlowExecutionRepository executionRepository;
    @Autowired
    private WorkFlowExecutionStepRepository stepRepository;

    @AfterEach
    void cleanUp() {
        stepRepository.deleteAll();
        executionRepository.deleteAll();
    }

    private Long newStep() {
        Long executionId = executionRepository.save(WorkFlowExecution.builder()
                .workflowId(1L).userId(1L).status(ExecutionState.RUNNING).build()).getId();
        return stepRepository.save(WorkFlowExecutionStep.builder()
                .executionId(executionId).stepId(1L).command("echo").stepOrder(0)
                .status(ExecutionState.RUNNING).build()).getId();
    }

    @Test
    void firstAppendStartsAtSeqOne() {
        Long id = newStep();

        Optional<StepLogStore.Appended> r = store.append(id, List.of("a", "b"));

        assertTrue(r.isPresent());
        assertEquals(1, r.get().fromSeq());
        assertEquals(List.of("a", "b"), r.get().lines());
        WorkFlowExecutionStep step = stepRepository.findById(id).orElseThrow();
        assertEquals("a\nb\n", step.getLogs());
        assertEquals(2, step.getLogLineCount());
        assertFalse(step.isLogTruncated());
    }

    @Test
    void secondAppendContinuesSeq() {
        Long id = newStep();
        store.append(id, List.of("a", "b"));

        Optional<StepLogStore.Appended> r = store.append(id, List.of("c"));

        assertEquals(3, r.orElseThrow().fromSeq());
        assertEquals("a\nb\nc\n", stepRepository.findById(id).orElseThrow().getLogs());
        assertEquals(3, stepRepository.findById(id).orElseThrow().getLogLineCount());
    }

    @Test
    void capKeepsFittingLinesAddsMarkerOnce() {
        Long id = newStep();
        String big = "x".repeat(400_000); // 400_001 chars with newline
        Optional<StepLogStore.Appended> r = store.append(id, List.of(big, big, big, big));

        List<String> stored = r.orElseThrow().lines();
        assertEquals(List.of(big, big, StepLogStore.TRUNCATION_MARKER), stored);
        WorkFlowExecutionStep step = stepRepository.findById(id).orElseThrow();
        assertEquals(3, step.getLogLineCount());
        assertTrue(step.isLogTruncated());
        assertTrue(step.getLogs().endsWith(StepLogStore.TRUNCATION_MARKER + "\n"));
        assertEquals(1, step.getLogs().split(java.util.regex.Pattern.quote(StepLogStore.TRUNCATION_MARKER), -1).length - 1);
    }

    @Test
    void appendAfterTruncationReturnsEmptyAndChangesNothing() {
        Long id = newStep();
        store.append(id, List.of("y".repeat(StepLogStore.MAX_LOG_CHARS)));
        WorkFlowExecutionStep before = stepRepository.findById(id).orElseThrow();
        assertTrue(before.isLogTruncated());

        assertTrue(store.append(id, List.of("more")).isEmpty());

        WorkFlowExecutionStep after = stepRepository.findById(id).orElseThrow();
        assertEquals(before.getLogs(), after.getLogs());
        assertEquals(before.getLogLineCount(), after.getLogLineCount());
    }
}
