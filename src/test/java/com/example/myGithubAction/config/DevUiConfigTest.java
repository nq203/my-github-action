package com.example.myGithubAction.config;

import com.example.myGithubAction.auth.security.JwtTokenProvider;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

/** The dev monitor page is served (without a token) only with app.dev-ui.enabled=true. */
class DevUiConfigTest {

    private static final String PAGE = "/dev/execution-monitor.html";

    abstract static class Base {
        @LocalServerPort
        int port;
        @Autowired
        JwtTokenProvider tokens;

        final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

        HttpResponse<String> get(String path, String token) throws Exception {
            HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path)).GET();
            if (token != null) {
                b.header("Authorization", "Bearer " + token);
            }
            return http.send(b.build(), HttpResponse.BodyHandlers.ofString());
        }
    }

    @Nested
    @SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
    class Disabled extends Base {

        @Test
        void pageIsNotServedWhenDisabled() throws Exception {
            HttpResponse<String> r = get(PAGE, null);
            assertThat(r.statusCode()).isIn(401, 403);
            assertThat(r.body()).doesNotContain("execution-monitor");
        }

        @Test
        void authenticatedRequestDoesNotGetThePageWhenDisabled() throws Exception {
            HttpResponse<String> r = get(PAGE, tokens.generateToken("user1", 1L));
            // The page is not on the classpath's static locations. Any unmapped path ends up in
            // GlobalExceptionHandler's catch-all (500, existing behaviour), not 404; either way no page.
            assertThat(r.statusCode()).isIn(404, 500);
            assertThat(r.body()).doesNotContain("execution-monitor");
        }
    }

    @Nested
    @SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
            properties = "app.dev-ui.enabled=true")
    class Enabled extends Base {

        @Test
        void pageIsServedWithoutTokenWhenEnabled() throws Exception {
            HttpResponse<String> r = get(PAGE, null);
            assertThat(r.statusCode()).isEqualTo(200);
            assertThat(r.body()).contains("execution-monitor");
        }

        @Test
        void otherPathsStillNeedAuthWhenEnabled() throws Exception {
            assertThat(get("/api/workflow-executions/1", null).statusCode()).isEqualTo(401);
        }
    }
}
