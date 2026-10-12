package com.example.myGithubAction.execution_engine.worker;

import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Beans that consume from Kafka and run executions. Off for an API-only instance
 * (app.kafka.consumer.enabled=false) and when Kafka is disabled altogether.
 */
@Target({ElementType.TYPE, ElementType.METHOD})
@Retention(RetentionPolicy.RUNTIME)
@Documented
@ConditionalOnExpression("${app.kafka.enabled:true} and ${app.kafka.consumer.enabled:true}")
public @interface ConditionalOnKafkaWorker {
}
