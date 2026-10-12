package com.example.myGithubAction.monitoring;

import com.example.myGithubAction.auth.entity.UserPrincipal;
import org.springframework.http.MediaType;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * Live monitor of one execution as Server-Sent Events: {@code snapshot}, then {@code execution},
 * {@code step}, {@code log} as they happen, {@code end} once finished; {@code : keepalive}
 * comments in between. Auth only via {@code Authorization: Bearer} (read the stream with
 * fetch, not EventSource). 404 for missing and not-owned alike, 429 above 5 streams per user.
 */
@RestController
public class ExecutionStreamController {

    private final ExecutionStreamService streams;

    public ExecutionStreamController(ExecutionStreamService streams) {
        this.streams = streams;
    }

    @GetMapping(value = "/api/workflow-executions/{id}/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream(@PathVariable Long id, @AuthenticationPrincipal UserPrincipal user) {
        return streams.open(id, user == null ? null : user.getUserId());
    }
}
