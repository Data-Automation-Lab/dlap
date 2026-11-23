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

package com.dlap.catalog.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.apache.kafka.clients.CommonClientConfigs;
import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.admin.Config;
import org.apache.kafka.common.config.ConfigResource;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.kafka.core.KafkaAdmin;
import org.springframework.kafka.test.EmbeddedKafkaKraftBroker;

import com.dlap.common.constants.DlapConstants;

/**
 * FU-3B against a real (embedded, in-process KRaft) broker: the {@code NewTopic} beans of da-catalog are
 * created by Spring's {@code KafkaAdmin} and the broker reports the settings the service asked for. A
 * mock cannot show that Kafka accepts {@code cleanup.policy} and {@code retention.ms} on these topics
 * (plan critique item 18). No Docker is needed.
 */
class KafkaTopicsBrokerTest {

    /** The broker's {@code num.partitions}, which a topic with no partition setting gets. */
    private static final int BROKER_PARTITIONS = 3;

    private static EmbeddedKafkaKraftBroker broker;

    /** Partition counts by topic, filled by {@link #provisionAndDescribe}. */
    private final Map<String, Integer> partitions = new LinkedHashMap<>();

    @BeforeAll
    static void startBroker() {
        broker = new EmbeddedKafkaKraftBroker(1, BROKER_PARTITIONS);
        broker.afterPropertiesSet();
    }

    @AfterAll
    static void stopBroker() {
        broker.destroy();
    }

    /** Starts the service's topic configuration with a real {@code KafkaAdmin}, then reads the topics back. */
    private Map<String, Map<String, String>> provisionAndDescribe(String... topics) throws Exception {
        Map<String, Object> adminConfig = Map.of(CommonClientConfigs.BOOTSTRAP_SERVERS_CONFIG,
                broker.getBrokersAsString());
        new ApplicationContextRunner()
                .withUserConfiguration(KafkaTopicsConfiguration.class)
                .withBean(KafkaAdmin.class, () -> {
                    KafkaAdmin admin = new KafkaAdmin(adminConfig);
                    admin.setFatalIfBrokerNotAvailable(true);
                    return admin;
                })
                .run(context -> assertThat(context).hasNotFailed());

        Map<String, Map<String, String>> result = new LinkedHashMap<>();
        try (Admin admin = Admin.create(adminConfig)) {
            List<ConfigResource> resources = List.of(topics).stream()
                    .map(t -> new ConfigResource(ConfigResource.Type.TOPIC, t)).collect(Collectors.toList());
            Map<ConfigResource, Config> described = admin.describeConfigs(resources).all().get();
            described.forEach((resource, config) -> {
                Map<String, String> entries = new LinkedHashMap<>();
                config.entries().forEach(e -> entries.put(e.name(), e.value()));
                result.put(resource.name(), entries);
            });
            admin.describeTopics(List.of(topics)).allTopicNames().get().forEach(
                    (name, description) -> partitions.put(name, description.partitions().size()));
        }
        return result;
    }

    /**
     * Q-380 on a real broker: the retention-due topic keeps 14 days (a setting of the service), the
     * upserted stream keeps the broker's retention, and each has a one-partition dead-letter topic.
     */
    @Test
    @Timeout(180)
    void aRealBrokerAppliesTheRetentionDueRetention() throws Exception {
        Map<String, Map<String, String>> configs = provisionAndDescribe(
                DlapConstants.KafkaTopic.CATALOG_ASSET_RETENTION_DUE,
                DlapConstants.KafkaTopic.CATALOG_ASSET_UPSERTED,
                DlapConstants.KafkaTopic.CATALOG_ASSET_RETENTION_DUE + ".DLT");

        assertThat(configs.get(DlapConstants.KafkaTopic.CATALOG_ASSET_RETENTION_DUE))
                .containsEntry("retention.ms", "1209600000");
        assertThat(configs.get(DlapConstants.KafkaTopic.CATALOG_ASSET_UPSERTED))
                .containsEntry("retention.ms", "604800000");
        assertThat(configs.get(DlapConstants.KafkaTopic.CATALOG_ASSET_RETENTION_DUE + ".DLT"))
                .containsEntry("retention.ms", "1209600000");
        assertThat(partitions).containsEntry(DlapConstants.KafkaTopic.CATALOG_ASSET_RETENTION_DUE + ".DLT", 1)
                .containsEntry(DlapConstants.KafkaTopic.CATALOG_ASSET_RETENTION_DUE, BROKER_PARTITIONS);
    }

    /**
     * Q-482 on a real broker: the binding-status topic is created compacted by da-catalog's configuration, the
     * way da-logingest's configuration creates it, and its dead-letter topic keeps 14 days.
     */
    @Test
    @Timeout(180)
    void aRealBrokerCreatesTheBindingStatusTopicCompacted() throws Exception {
        Map<String, Map<String, String>> configs = provisionAndDescribe(
                DlapConstants.KafkaTopic.LOGINGEST_BINDING_STATUS,
                DlapConstants.KafkaTopic.LOGINGEST_BINDING_STATUS + ".DLT");

        assertThat(configs.get(DlapConstants.KafkaTopic.LOGINGEST_BINDING_STATUS))
                .containsEntry("cleanup.policy", "compact");
        assertThat(configs.get(DlapConstants.KafkaTopic.LOGINGEST_BINDING_STATUS + ".DLT"))
                .containsEntry("retention.ms", "1209600000");
    }
}
