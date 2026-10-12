package com.example.myGithubAction.messaging;

import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;

/**
 * Kafka topics and settings shared by every instance (API publishes to them, workers consume).
 * Topic creation is idempotent; on a managed cluster let infrastructure own it instead.
 */
@Configuration
@EnableConfigurationProperties(ExecutionKafkaProperties.class)
@ConditionalOnProperty(name = "app.kafka.enabled", havingValue = "true", matchIfMissing = true)
public class KafkaConfig {

    @Bean
    public NewTopic executionRequestedTopic(ExecutionKafkaProperties properties) {
        ExecutionKafkaProperties.Topics topics = properties.getTopics();
        return TopicBuilder.name(topics.getRequested())
                .partitions(topics.getPartitions())
                .replicas(topics.getReplicationFactor())
                .build();
    }

    @Bean
    public NewTopic executionCancelTopic(ExecutionKafkaProperties properties) {
        ExecutionKafkaProperties.Topics topics = properties.getTopics();
        return TopicBuilder.name(topics.getCancel())
                .partitions(1)
                .replicas(topics.getReplicationFactor())
                .config("retention.ms", String.valueOf(60 * 60 * 1000L))
                .build();
    }

    @Bean
    public NewTopic executionRequestedDeadLetterTopic(ExecutionKafkaProperties properties) {
        ExecutionKafkaProperties.Topics topics = properties.getTopics();
        return TopicBuilder.name(topics.deadLetter())
                .partitions(1)
                .replicas(topics.getReplicationFactor())
                .build();
    }
}
