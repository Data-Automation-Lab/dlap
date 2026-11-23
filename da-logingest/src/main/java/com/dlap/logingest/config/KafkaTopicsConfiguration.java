/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package com.dlap.logingest.config;

import java.time.Duration;
import java.util.Map;
import java.util.Optional;

import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.common.config.TopicConfig;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.dlap.common.constants.DlapConstants;
import com.dlap.common.kafka.DlapKafkaFactoryBuilder;

/**
 * Provisions the Kafka topics da-logingest produces, and their dead-letter topics, when the service starts.
 *
 * <p>The topics are the normalized log-event stream, the quarantine topic and the per-binding status topic.
 * Each one has a {@code <topic>.DLT} topic, because
 * {@link DlapKafkaFactoryBuilder} publishes a record that a consumer gives up on there, and a cluster
 * with {@code auto.create.topics.enable=false} rejects that publish when the topic does not exist
 * (Q-336). Names come from {@link DlapConstants.KafkaTopic}. Spring's {@code KafkaAdmin} creates the
 * topics that are missing and adds partitions to a topic that has fewer than declared. It never lowers a
 * partition count and it does not change the settings of a topic that already exists, so a topic created
 * earlier by auto-create keeps its settings until an operator alters them (docs/guides/upgrade.md,
 * "Kafka topics"). A broker that is down at start-up is logged and does not stop the service.
 *
 * <p>The beans are on unless {@code dlap.kafka.provision-topics=false}. Turn them off where the cluster's
 * topics are managed outside the service (deployment/PRODUCTION.md, "Kafka topics").
 *
 * <table>
 *   <caption>Settings under {@code dlap.kafka}</caption>
 *   <tr><th>Property</th><th>Default</th><th>Meaning</th></tr>
 *   <tr><td>{@code provision-topics}</td><td>{@code true}</td><td>declare the beans at all</td></tr>
 *   <tr><td>{@code topic-partitions}</td><td>-1</td><td>partitions of a topic; below 1 means the broker's {@code num.partitions}</td></tr>
 *   <tr><td>{@code topic-replication-factor}</td><td>-1</td><td>replicas; below 1 means the broker's {@code default.replication.factor}</td></tr>
 *   <tr><td>{@code dlt-partitions}</td><td>1</td><td>partitions of a dead-letter topic (the producer picks the partition)</td></tr>
 *   <tr><td>{@code dlt-retention}</td><td>P14D</td><td>how long a dead-letter record is kept</td></tr>
 *   <tr><td>{@code quarantine-retention}</td><td>P30D</td><td>how long a quarantine record is kept</td></tr>
 * </table>
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "dlap.kafka", name = "provision-topics", havingValue = "true",
        matchIfMissing = true)
@EnableConfigurationProperties(KafkaTopicsConfiguration.Settings.class)
public class KafkaTopicsConfiguration {

    /**
     * The topic settings, bound from {@code dlap.kafka.*}. Other keys under that prefix belong to other
     * classes and are ignored here.
     *
     * @param topicPartitions partitions of a topic, or -1 for the broker default
     * @param topicReplicationFactor replicas of a topic, or below 1 for the broker default
     * @param dltPartitions partitions of a dead-letter topic
     * @param dltRetention retention of a dead-letter topic
     * @param quarantineRetention retention of the quarantine topic
     */
    @ConfigurationProperties(prefix = "dlap.kafka")
    public record Settings(
            @DefaultValue("-1") int topicPartitions,
            @DefaultValue("-1") int topicReplicationFactor,
            @DefaultValue("1") int dltPartitions,
            @DefaultValue("P14D") Duration dltRetention,
            @DefaultValue("P30D") Duration quarantineRetention) {

        /** Rejects a setting that Kafka would refuse, so a typo stops the start-up with its name. */
        public Settings {
            if (topicPartitions == 0 || topicPartitions < -1) {
                throw new IllegalArgumentException("dlap.kafka.topic-partitions must be at least 1, or -1 for the "
                        + "broker default, was " + topicPartitions);
            }
            if (dltPartitions < 1) {
                throw new IllegalArgumentException("dlap.kafka.dlt-partitions must be at least 1, was "
                        + dltPartitions);
            }
            requirePositive("dlap.kafka.dlt-retention", dltRetention);
            requirePositive("dlap.kafka.quarantine-retention", quarantineRetention);
        }
    }

    /**
     * {@code DlapConstants.KafkaTopic.LOG_EVENT_NORMALIZED} with the broker's default retention.
     *
     * @param settings the bound settings
     * @return the topic
     */
    @Bean
    public NewTopic logEventNormalizedTopic(Settings settings) {
        return topic(DlapConstants.KafkaTopic.LOG_EVENT_NORMALIZED, settings.topicPartitions(),
                settings.topicReplicationFactor(),
                Map.of());
    }

    /**
     * Dead-letter topic of {@code DlapConstants.KafkaTopic.LOG_EVENT_NORMALIZED}: a consumer that gives up on a record
     * publishes it to {@code <topic>.DLT} (Q-336).
     *
     * @param settings the bound settings
     * @return the topic
     */
    @Bean
    public NewTopic logEventNormalizedDltTopic(Settings settings) {
        return deadLetterTopic(DlapConstants.KafkaTopic.LOG_EVENT_NORMALIZED, settings);
    }

    /**
     * {@code DlapConstants.KafkaTopic.LOG_EVENT_QUARANTINE}: quarantined records are kept for
     * {@code dlap.kafka.quarantine-retention} (30 days by default, Q-369), then deleted.
     *
     * @param settings the bound settings
     * @return the topic
     */
    @Bean
    public NewTopic logEventQuarantineTopic(Settings settings) {
        return topic(DlapConstants.KafkaTopic.LOG_EVENT_QUARANTINE, settings.topicPartitions(),
                settings.topicReplicationFactor(),
                Map.of(TopicConfig.RETENTION_MS_CONFIG, millis(settings.quarantineRetention())));
    }

    /**
     * Dead-letter topic of {@code DlapConstants.KafkaTopic.LOG_EVENT_QUARANTINE}: a consumer that gives up on a record
     * publishes it to {@code <topic>.DLT} (Q-336).
     *
     * @param settings the bound settings
     * @return the topic
     */
    @Bean
    public NewTopic logEventQuarantineDltTopic(Settings settings) {
        return deadLetterTopic(DlapConstants.KafkaTopic.LOG_EVENT_QUARANTINE, settings);
    }

    /**
     * {@code DlapConstants.KafkaTopic.LOGINGEST_BINDING_STATUS}: the latest message per key is the current state, so
     * the topic is compacted ({@code cleanup.policy=compact}) and a tombstone ends a binding (Q-369).
     *
     * @param settings the bound settings
     * @return the topic
     */
    @Bean
    public NewTopic logingestBindingStatusTopic(Settings settings) {
        return topic(DlapConstants.KafkaTopic.LOGINGEST_BINDING_STATUS, settings.topicPartitions(),
                settings.topicReplicationFactor(),
                Map.of(TopicConfig.CLEANUP_POLICY_CONFIG, TopicConfig.CLEANUP_POLICY_COMPACT));
    }

    /**
     * Dead-letter topic of {@code DlapConstants.KafkaTopic.LOGINGEST_BINDING_STATUS}: a consumer that gives up on a record
     * publishes it to {@code <topic>.DLT} (Q-336).
     *
     * @param settings the bound settings
     * @return the topic
     */
    @Bean
    public NewTopic logingestBindingStatusDltTopic(Settings settings) {
        return deadLetterTopic(DlapConstants.KafkaTopic.LOGINGEST_BINDING_STATUS, settings);
    }

    private static NewTopic topic(String name, int partitions, int replicationFactor,
            Map<String, String> configs) {
        return new NewTopic(name, partitions(partitions), replicas(replicationFactor)).configs(configs);
    }

    private static NewTopic deadLetterTopic(String source, Settings settings) {
        return new NewTopic(source + DlapKafkaFactoryBuilder.DEAD_LETTER_SUFFIX,
                Optional.of(settings.dltPartitions()), replicas(settings.topicReplicationFactor()))
                .configs(Map.of(TopicConfig.RETENTION_MS_CONFIG, millis(settings.dltRetention())));
    }

    private static Optional<Integer> partitions(int partitions) {
        return partitions > 0 ? Optional.of(partitions) : Optional.empty();
    }

    private static Optional<Short> replicas(int replicationFactor) {
        return replicationFactor > 0 ? Optional.of((short) replicationFactor) : Optional.empty();
    }

    private static String millis(Duration duration) {
        return Long.toString(duration.toMillis());
    }

    private static void requirePositive(String property, Duration value) {
        if (value == null || value.isZero() || value.isNegative()) {
            throw new IllegalArgumentException(property + " must be a positive duration, was " + value);
        }
    }
}
