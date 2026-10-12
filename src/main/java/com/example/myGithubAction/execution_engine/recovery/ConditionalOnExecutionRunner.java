package com.example.myGithubAction.execution_engine.recovery;

import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Beans for an instance that actually runs executions: either in-process (Kafka off) or a
 * Kafka worker. Off for an API-only instance (app.kafka.consumer.enabled=false), which must
 * never heartbeat, reap or sweep: it owns no executions.
 */
@Target({ElementType.TYPE, ElementType.METHOD})
@Retention(RetentionPolicy.RUNTIME)
@Documented
@ConditionalOnExpression("!${app.kafka.enabled:true} or ${app.kafka.consumer.enabled:true}")
public @interface ConditionalOnExecutionRunner {
}
