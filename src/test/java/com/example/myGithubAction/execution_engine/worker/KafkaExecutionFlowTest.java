package com.example.myGithubAction.execution_engine.worker;

import com.example.myGithubAction.messaging.ExecutionKafkaProperties;
import com.example.myGithubAction.common.ExecutionState;
import com.example.myGithubAction.workflow.dto.WorkFlowExecutionRequest;
import com.example.myGithubAction.workflow.dto.WorkFlowExecutionResponse;
import com.example.myGithubAction.workflow.entity.Step;
import com.example.myGithubAction.workflow.entity.WorkFlow;
import com.example.myGithubAction.workflow.entity.WorkFlowExecution;
import com.example.myGithubAction.workflow.entity.WorkFlowExecutionStep;
import com.example.myGithubAction.workflow.repository.StepRepository;
import com.example.myGithubAction.workflow.repository.WorkFlowExecutionRepository;
import com.example.myGithubAction.workflow.repository.WorkFlowExecutionStepRepository;
import com.example.myGithubAction.workflow.repository.WorkFlowRepository;
import com.example.myGithubAction.workflow.service.WorkFlowExecutionService;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.test.EmbeddedKafkaBroker;
import org.springframework.kafka.test.context.EmbeddedKafka;
import org.springframework.kafka.test.utils.KafkaTestUtils;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Full path through a real (embedded) broker and a real H2 database:
 * API publishes START after commit → worker claims and runs the shell steps → status.
 */
@SpringBootTest(properties = {
        "app.kafka.enabled=true",
        "app.kafka.consumer.enabled=true",
        "app.kafka.worker.cancel-poll-ms=500",
        "spring.kafka.bootstrap-servers=${spring.embedded.kafka.brokers}"
})
@EmbeddedKafka(partitions = 1, kraft = true)
class KafkaExecutionFlowTest {

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
    private KafkaTemplate<String, String> kafkaTemplate;
    @Autowired
    private EmbeddedKafkaBroker broker;
    @Autowired
    private ExecutionKafkaProperties properties;

    private Long workflowWithCommands(String... commands) {
        WorkFlow workflow = workflowRepository.save(WorkFlow.builder().userId(1L).name("wf").build());
        for (int i = 0; i < commands.length; i++) {
            stepRepository.save(Step.builder().workflowId(workflow.getId()).name("s" + i)
                    .stepOrder(i).command(commands[i]).build());
        }
        return workflow.getId();
    }

    private WorkFlowExecutionResponse start(Long workflowId) {
        return service.createExecution(1L, WorkFlowExecutionRequest.builder().workflowId(workflowId).build());
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

    @Test
    void executionFlowsThroughKafkaToSuccess() throws Exception {
        Long executionId = start(workflowWithCommands("echo hello-kafka", "echo second")).getId();

        awaitStatus(executionId, ExecutionState.SUCCESS);

        List<WorkFlowExecutionStep> steps = executionStepRepository.findByExecutionIdOrderByStepOrder(executionId);
        assertEquals(2, steps.size());
        steps.forEach(step -> assertEquals(ExecutionState.SUCCESS, step.getStatus()));
        assertTrue(steps.get(0).getLogs().contains("hello-kafka"));
    }

    @Test
    void failingStepFailsTheExecution() throws Exception {
        Long executionId = start(workflowWithCommands("echo ok", "false", "echo never")).getId();

        awaitStatus(executionId, ExecutionState.FAILED);

        List<WorkFlowExecutionStep> steps = executionStepRepository.findByExecutionIdOrderByStepOrder(executionId);
        assertEquals(ExecutionState.SUCCESS, steps.get(0).getStatus());
        assertEquals(ExecutionState.FAILED, steps.get(1).getStatus());
        assertEquals(ExecutionState.PENDING, steps.get(2).getStatus());
    }

    @Test
    void duplicateStartMessageDoesNotRunTheExecutionTwice() throws Exception {
        Long executionId = start(workflowWithCommands("echo once")).getId();
        awaitStatus(executionId, ExecutionState.SUCCESS);
        String logsAfterFirstRun = executionStepRepository.findByExecutionId(executionId).get(0).getLogs();

        // Redelivery / duplicate publish of the same START
        kafkaTemplate.send(properties.getTopics().getRequested(), executionId.toString(),
                "{\"version\":1,\"executionId\":" + executionId + ",\"requestedAtEpochMs\":1}").get();
        // A marker execution queued behind it proves the duplicate has been consumed already
        Long markerId = start(workflowWithCommands("echo marker")).getId();
        awaitStatus(markerId, ExecutionState.SUCCESS);

        assertEquals(ExecutionState.SUCCESS, status(executionId));
        assertEquals(logsAfterFirstRun, executionStepRepository.findByExecutionId(executionId).get(0).getLogs());
    }

    @Test
    void cancelStopsARunningExecutionAndStaysCancelled() throws Exception {
        Long executionId = start(workflowWithCommands("sleep 60", "echo never")).getId();
        awaitStatus(executionId, ExecutionState.RUNNING);
        await(() -> executionStepRepository.findByExecutionId(executionId).stream()
                        .anyMatch(s -> s.getStatus() == ExecutionState.RUNNING),
                "first step should be running");

        service.cancelExecution(executionId);

        // The 60s sleep is killed promptly; the worker must not overwrite CANCELLED with FAILED.
        await(() -> executionStepRepository.findByExecutionId(executionId).stream()
                        .noneMatch(s -> s.getStatus() == ExecutionState.RUNNING),
                "running step should be stopped after cancel");
        assertEquals(ExecutionState.CANCELLED, status(executionId));
        Thread.sleep(1000);
        assertEquals(ExecutionState.CANCELLED, status(executionId));
        assertTrue(executionStepRepository.findByExecutionId(executionId).stream()
                .noneMatch(s -> s.getStatus() == ExecutionState.SUCCESS));
    }

    @Test
    void executionCancelledBeforeItIsClaimedNeverRuns() throws Exception {
        WorkFlowExecution execution = executionRepository.save(WorkFlowExecution.builder()
                .workflowId(workflowWithCommands("echo should-not-run")).userId(1L)
                .status(ExecutionState.CANCELLED).build());

        kafkaTemplate.send(properties.getTopics().getRequested(), execution.getId().toString(),
                "{\"version\":1,\"executionId\":" + execution.getId() + ",\"requestedAtEpochMs\":1}").get();
        Long markerId = start(workflowWithCommands("echo marker")).getId();
        awaitStatus(markerId, ExecutionState.SUCCESS);

        assertEquals(ExecutionState.CANCELLED, status(execution.getId()));
    }

    @Test
    void malformedMessageGoesToDeadLetterTopic() {
        kafkaTemplate.send(properties.getTopics().getRequested(), "999999", "not json");

        Map<String, Object> consumerProps = KafkaTestUtils.consumerProps("dlt-test", "true", broker);
        consumerProps.put("auto.offset.reset", "earliest");
        try (Consumer<String, String> consumer = new DefaultKafkaConsumerFactory<>(
                consumerProps, new StringDeserializer(), new StringDeserializer()).createConsumer()) {
            consumer.subscribe(List.of(properties.getTopics().deadLetter()));
            ConsumerRecord<String, String> record = null;
            long deadline = System.currentTimeMillis() + 30_000;
            while (record == null && System.currentTimeMillis() < deadline) {
                ConsumerRecords<String, String> records = consumer.poll(Duration.ofMillis(500));
                for (ConsumerRecord<String, String> r : records) {
                    if ("999999".equals(r.key())) {
                        record = r;
                    }
                }
            }
            assertNotNull(record, "malformed START should be dead-lettered");
            assertEquals("not json", record.value());
        }
    }
}
