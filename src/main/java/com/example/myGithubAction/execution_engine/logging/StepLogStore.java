package com.example.myGithubAction.execution_engine.logging;

import com.example.myGithubAction.workflow.repository.WorkFlowExecutionStepRepository;
import com.example.myGithubAction.workflow.repository.WorkFlowExecutionStepRepository.LogState;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Persists step log lines with sequence numbers and the per-step size cap.
 *
 * Relies on the single-writer-per-step rule (all writes go through one sink or LogManager.appendLog
 * from the worker running the step), so reading the state and updating in one transaction is safe.
 */
@Component
public class StepLogStore {

    public static final int MAX_LOG_CHARS = 1_048_576;
    public static final String TRUNCATION_MARKER = "[log truncated]";

    /** Lines actually stored; the first one has sequence number fromSeq. */
    public record Appended(int fromSeq, List<String> lines) {
    }

    private final WorkFlowExecutionStepRepository stepRepository;

    public StepLogStore(WorkFlowExecutionStepRepository stepRepository) {
        this.stepRepository = stepRepository;
    }

    /**
     * Appends the lines (each followed by a newline) in one UPDATE. Lines that would push the
     * stored log over the cap are dropped and replaced by a single truncation marker line.
     *
     * @return the stored lines, or empty when nothing was stored (already truncated / no lines / no step)
     */
    @Transactional
    public Optional<Appended> append(Long stepId, List<String> lines) {
        if (lines == null || lines.isEmpty()) {
            return Optional.empty();
        }
        Optional<LogState> found = stepRepository.findLogState(stepId);
        if (found.isEmpty()) {
            return Optional.empty();
        }
        LogState state = found.get();
        if (Boolean.TRUE.equals(state.getTruncated())) {
            return Optional.empty();
        }

        long used = state.getCharCount() == null ? 0 : state.getCharCount();
        List<String> stored = new ArrayList<>(lines.size() + 1);
        StringBuilder text = new StringBuilder();
        boolean truncated = false;
        for (String line : lines) {
            long cost = (long) line.length() + 1;
            if (used + cost > MAX_LOG_CHARS) {
                stored.add(TRUNCATION_MARKER);
                text.append(TRUNCATION_MARKER).append('\n');
                truncated = true;
                break;
            }
            stored.add(line);
            text.append(line).append('\n');
            used += cost;
        }

        int fromSeq = (state.getLineCount() == null ? 0 : state.getLineCount()) + 1;
        stepRepository.appendLogLines(stepId, text.toString(), stored.size(), truncated, LocalDateTime.now());
        return Optional.of(new Appended(fromSeq, stored));
    }
}
