package com.example.myGithubAction.workflow.controller;

import com.example.myGithubAction.auth.security.JwtTokenProvider;
import com.example.myGithubAction.common.ExecutionState;
import com.example.myGithubAction.workflow.entity.WorkFlowExecution;
import com.example.myGithubAction.workflow.entity.WorkFlowExecutionStep;
import com.example.myGithubAction.workflow.repository.WorkFlowExecutionRepository;
import com.example.myGithubAction.workflow.repository.WorkFlowExecutionStepRepository;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/** Real server + real JWT filter chain: reading an execution (or its steps) is owner-only. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ExecutionReadAccessTest {

    @LocalServerPort
    private int port;
    @Autowired
    private JwtTokenProvider tokens;
    @Autowired
    private WorkFlowExecutionRepository executions;
    @Autowired
    private WorkFlowExecutionStepRepository steps;

    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    private final ObjectMapper json = new ObjectMapper();

    private long[] execution(long userId) {
        WorkFlowExecution e = executions.save(WorkFlowExecution.builder()
                .workflowId(1L).userId(userId).status(ExecutionState.SUCCESS)
                .startedAt(LocalDateTime.now()).endedAt(LocalDateTime.now()).build());
        WorkFlowExecutionStep s = steps.save(WorkFlowExecutionStep.builder()
                .executionId(e.getId()).stepId(1L).command("echo secret").stepOrder(0)
                .status(ExecutionState.SUCCESS).logs("secret log").logLineCount(1).build());
        return new long[] {e.getId(), s.getId()};
    }

    private HttpResponse<String> get(String path, long userId) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .header("Authorization", "Bearer " + tokens.generateToken("user" + userId, userId))
                .GET().build();
        return http.sendAsync(request, HttpResponse.BodyHandlers.ofString()).get(10, TimeUnit.SECONDS);
    }

    private Map<String, Object> withoutTimestamp(String body) throws Exception {
        Map<String, Object> map = json.readValue(body, new TypeReference<>() { });
        map.remove("timestamp");
        return map;
    }

    @Test
    void ownerCanReadExecutionStepsAndSingleStep() throws Exception {
        long[] ids = execution(501L);

        HttpResponse<String> one = get("/api/workflow-executions/" + ids[0], 501L);
        HttpResponse<String> list = get("/api/workflow-executions/" + ids[0] + "/steps", 501L);
        HttpResponse<String> step = get("/api/workflow-executions/steps/" + ids[1], 501L);

        assertThat(one.statusCode()).isEqualTo(200);
        assertThat(list.statusCode()).isEqualTo(200);
        assertThat(list.body()).contains("secret log");
        assertThat(step.statusCode()).isEqualTo(200);
        assertThat(step.body()).contains("secret log");
    }

    @Test
    void nonOwnerGets404OnAllThree() throws Exception {
        long[] ids = execution(511L);
        long other = 512L;

        HttpResponse<String> one = get("/api/workflow-executions/" + ids[0], other);
        HttpResponse<String> list = get("/api/workflow-executions/" + ids[0] + "/steps", other);
        HttpResponse<String> step = get("/api/workflow-executions/steps/" + ids[1], other);
        HttpResponse<String> missingExec = get("/api/workflow-executions/987654321", other);
        HttpResponse<String> missingExecSteps = get("/api/workflow-executions/987654321/steps", other);

        for (HttpResponse<String> r : new HttpResponse[] {one, list, step}) {
            assertThat(r.statusCode()).isEqualTo(404);
            assertThat(r.body()).doesNotContain("secret");
        }
        assertThat(missingExec.statusCode()).isEqualTo(404);
        assertThat(withoutTimestamp(one.body())).isEqualTo(withoutTimestamp(missingExec.body()));
        assertThat(withoutTimestamp(list.body())).isEqualTo(withoutTimestamp(missingExecSteps.body()));
    }

    @Test
    void missingStepGets404() throws Exception {
        long[] ids = execution(521L);

        HttpResponse<String> missing = get("/api/workflow-executions/steps/987654321", 522L);
        HttpResponse<String> foreign = get("/api/workflow-executions/steps/" + ids[1], 522L);

        assertThat(missing.statusCode()).isEqualTo(404);
        assertThat(foreign.statusCode()).isEqualTo(404);
        assertThat(withoutTimestamp(missing.body())).isEqualTo(withoutTimestamp(foreign.body()));
    }
}
