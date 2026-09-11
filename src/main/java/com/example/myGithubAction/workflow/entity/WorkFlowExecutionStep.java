package com.example.myGithubAction.workflow.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import java.time.LocalDateTime;

import com.example.myGithubAction.common.ExecutionState;

@Entity
@Table(name = "workflow_execution_steps")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class WorkFlowExecutionStep {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "execution_id", nullable = false)
    private Long executionId;

    @Column(name = "step_id", nullable = false)
    private Long stepId;

    @Column(name = "command", nullable = false)
    private String command;

    @Column(nullable = false)
    @Enumerated(EnumType.STRING)
    private ExecutionState status;

    @Column(name = "step_order", nullable = false)
    private Integer stepOrder;

    @Column(name = "started_at")
    private LocalDateTime startedAt;

    @Column(name = "ended_at")
    private LocalDateTime endedAt;

    @Column(columnDefinition = "TEXT")
    private String logs;

    @Column(columnDefinition = "TEXT")
    private String errorMessage;

    @Column
    private Long duration; // duration in milliseconds

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
        updatedAt = LocalDateTime.now();
        if (status == null) {
            status = ExecutionState.PENDING;
        }
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
    }
}
