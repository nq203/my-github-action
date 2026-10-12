package com.example.myGithubAction.monitoring;

import com.example.myGithubAction.auth.security.JwtTokenProvider;
import com.example.myGithubAction.common.ExecutionState;
import com.example.myGithubAction.workflow.entity.WorkFlowExecution;
import com.example.myGithubAction.workflow.entity.WorkFlowExecutionStep;
import com.example.myGithubAction.workflow.repository.WorkFlowExecutionRepository;
import com.example.myGithubAction.workflow.repository.WorkFlowExecutionStepRepository;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.test.web.server.LocalServerPort;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Real Tomcat (so the async dispatch of the SseEmitter really goes through the security filter
 * chain) with the in-process event publisher and H2.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "app.stream.keepalive-ms=200")
@ExtendWith(OutputCaptureExtension.class)
class ExecutionStreamIntegrationTest {

    @LocalServerPort
    private int port;
    @Autowired
    private JwtTokenProvider tokens;
    @Autowired
    private WorkFlowExecutionRepository executions;
    @Autowired
    private WorkFlowExecutionStepRepository steps;
    @Autowired
    private ExecutionEventHub hub;

    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    private final ObjectMapper json = new ObjectMapper();

    private Long execution(long userId, ExecutionState status, String logs, int lineCount) {
        WorkFlowExecution e = executions.save(WorkFlowExecution.builder()
                .workflowId(1L).userId(userId).status(status).startedAt(LocalDateTime.now())
                .endedAt(status == ExecutionState.RUNNING ? null : LocalDateTime.now()).build());
        steps.save(WorkFlowExecutionStep.builder()
                .executionId(e.getId()).stepId(1L).command("echo hi").stepOrder(0)
                .status(status).logs(logs).logLineCount(lineCount).build());
        return e.getId();
    }

    private String token(long userId) {
        return tokens.generateToken("user" + userId, userId);
    }

    private HttpRequest stream(Long executionId, String token) {
        HttpRequest.Builder b = HttpRequest.newBuilder(
                        URI.create("http://localhost:" + port + "/api/workflow-executions/" + executionId + "/stream"))
                .header("Accept", "text/event-stream")
                .GET();
        if (token != null) {
            b.header("Authorization", "Bearer " + token);
        }
        return b.build();
    }

    private HttpResponse<String> fetch(HttpRequest request) throws Exception {
        return http.sendAsync(request, HttpResponse.BodyHandlers.ofString()).get(10, TimeUnit.SECONDS);
    }

    private HttpResponse<Stream<String>> open(HttpRequest request) throws Exception {
        return http.sendAsync(request, HttpResponse.BodyHandlers.ofLines()).get(10, TimeUnit.SECONDS);
    }

    private static void await(BooleanSupplier condition, String message) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 10_000;
        while (!condition.getAsBoolean()) {
            if (System.currentTimeMillis() > deadline) {
                throw new AssertionError(message);
            }
            Thread.sleep(20);
        }
    }

    private Map<String, Object> withoutTimestamp(String body) throws Exception {
        Map<String, Object> map = json.readValue(body, new TypeReference<>() { });
        map.remove("timestamp");
        return map;
    }

    @Test
    void ownerReceivesSnapshotThenEnd(CapturedOutput output) throws Exception {
        Long id = execution(101L, ExecutionState.SUCCESS, "line one\nline two\n", 2);

        HttpResponse<String> response = fetch(stream(id, token(101L)));

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.headers().firstValue("Content-Type")).hasValueSatisfying(
                ct -> assertThat(ct).startsWith("text/event-stream"));
        String body = response.body();
        assertThat(body).contains("event:snapshot", "event:end", "line one\\nline two\\n", "\"lineCount\":2");
        assertThat(body.indexOf("event:snapshot")).isLessThan(body.indexOf("event:end"));
        assertThat(body.indexOf("event:snapshot")).isEqualTo(body.lastIndexOf("event:snapshot"));
        assertThat(body.substring(body.indexOf("event:end"))).contains("\"status\":\"SUCCESS\"");
        await(() -> hub.countForUser(101L) == 0, "finished stream must leave the hub");
        // the SseEmitter completion re-dispatches the request (DispatcherType.ASYNC) through the
        // security chain; it must not be denied there
        Thread.sleep(200);
        assertThat(output.getAll()).doesNotContain("AccessDenied", "response is already committed");
    }

    @Test
    void nonOwnerGets404() throws Exception {
        Long id = execution(201L, ExecutionState.RUNNING, "", 0);

        HttpResponse<String> response = fetch(stream(id, token(202L)));

        assertThat(response.statusCode()).isEqualTo(404);
        assertThat(response.body()).doesNotContain("event:");
        assertThat(hub.countForUser(202L)).isZero();
    }

    @Test
    void unknownIdGets404WithSameBody() throws Exception {
        Long id = execution(211L, ExecutionState.RUNNING, "", 0);

        HttpResponse<String> notOwner = fetch(stream(id, token(212L)));
        HttpResponse<String> unknown = fetch(stream(987_654_321L, token(212L)));

        assertThat(unknown.statusCode()).isEqualTo(404);
        assertThat(notOwner.statusCode()).isEqualTo(404);
        assertThat(unknown.headers().firstValue("Content-Type"))
                .isEqualTo(notOwner.headers().firstValue("Content-Type"));
        assertThat(withoutTimestamp(unknown.body())).isEqualTo(withoutTimestamp(notOwner.body()));
    }

    @Test
    void noTokenGets401() throws Exception {
        Long id = execution(221L, ExecutionState.RUNNING, "", 0);

        assertThat(fetch(stream(id, null)).statusCode()).isEqualTo(401);
        assertThat(fetch(stream(id, "not-a-jwt")).statusCode()).isEqualTo(401);
    }

    @Test
    void badIdWithValidTokenGets400Not401() throws Exception {
        HttpRequest request = HttpRequest.newBuilder(
                        URI.create("http://localhost:" + port + "/api/workflow-executions/abc/stream"))
                .header("Accept", "text/event-stream")
                .header("Authorization", "Bearer " + token(231L))
                .GET().build();
        HttpRequest noToken = HttpRequest.newBuilder(
                        URI.create("http://localhost:" + port + "/api/workflow-executions/abc/stream"))
                .header("Accept", "text/event-stream")
                .GET().build();

        HttpResponse<String> response = fetch(request);

        assertThat(response.statusCode()).isEqualTo(400);
        assertThat(response.body()).doesNotContain("event:");
        assertThat(fetch(noToken).statusCode()).isEqualTo(401);
        assertThat(fetch(HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/error")).GET().build())
                .statusCode()).as("the error page itself is not reachable without a token").isEqualTo(401);
    }

    @Test
    void sixthConcurrentStreamGets429() throws Exception {
        Long id = execution(301L, ExecutionState.RUNNING, "started\n", 1);
        List<HttpResponse<Stream<String>>> openStreams = new ArrayList<>();
        try {
            for (int i = 0; i < 5; i++) {
                HttpResponse<Stream<String>> r = open(stream(id, token(301L)));
                assertThat(r.statusCode()).isEqualTo(200);
                openStreams.add(r);
            }
            await(() -> hub.countForUser(301L) == 5, "five streams should be registered");

            HttpResponse<String> sixth = fetch(stream(id, token(301L)));

            assertThat(sixth.statusCode()).isEqualTo(429);
            assertThat(sixth.body()).doesNotContain("event:", "started");
            assertThat(hub.countForUser(301L)).isEqualTo(5);
        } finally {
            openStreams.forEach(r -> r.body().close());
        }
        await(() -> hub.countForUser(301L) == 0, "closed streams should leave the hub");
    }

    @Test
    void disconnectUnregistersSubscription() throws Exception {
        Long id = execution(401L, ExecutionState.RUNNING, "", 0);

        HttpResponse<Stream<String>> response = open(stream(id, token(401L)));
        assertThat(response.statusCode()).isEqualTo(200);
        Iterator<String> lines = response.body().iterator();
        assertThat(lines.next()).isEqualTo("event:snapshot");
        await(() -> hub.countForUser(401L) == 1, "stream should be registered");
        assertThat(hub.watchedExecutionIds()).contains(id);
        String comment = null;
        for (int i = 0; i < 10 && comment == null; i++) {
            String line = lines.next();
            if (line.startsWith(":")) {
                comment = line;
            }
        }
        assertThat(comment).isEqualTo(": keepalive");

        response.body().close();

        await(() -> hub.countForUser(401L) == 0, "disconnected viewer must be unregistered");
        await(() -> !hub.watchedExecutionIds().contains(id), "execution must no longer be watched");
    }
}
