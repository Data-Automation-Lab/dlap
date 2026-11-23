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

package com.dlap.autolabel.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.apache.kafka.clients.admin.NewTopic;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.assertj.AssertableApplicationContext;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import com.dlap.common.constants.DlapConstants;
import com.dlap.common.kafka.DlapKafkaFactoryBuilder;

/**
 * FU-3B (Q-336, Q-369, Q-380): the {@code NewTopic} beans of da-autolabel exist with the configured settings.
 * No broker is involved; the settings that Kafka itself must accept are checked against a real broker in
 * the broker test of the services that set topic configs.
 */
class KafkaTopicsConfigurationTest {

    /** The topics the service produces. */
    private static final List<String> OWN_TOPICS = List.of(
            DlapConstants.KafkaTopic.LABELING_REQUEST_EVENT);

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(KafkaTopicsConfiguration.class);

    private static NewTopic topic(AssertableApplicationContext context, String name) {
        Map<String, NewTopic> byName = new java.util.LinkedHashMap<>();
        context.getBeansOfType(NewTopic.class).values().forEach(t -> byName.put(t.name(), t));
        assertThat(byName).as("topics declared by the service").containsKey(name);
        return byName.get(name);
    }

    /** Q-336: every own topic has a {@code .DLT} topic, and nothing else is declared. */
    @Test
    void declaresEveryOwnTopicAndItsDeadLetterTopic() {
        List<String> expected = new ArrayList<>(OWN_TOPICS);
        OWN_TOPICS.forEach(t -> expected.add(t + DlapKafkaFactoryBuilder.DEAD_LETTER_SUFFIX));

        runner.run(context -> assertThat(context.getBeansOfType(NewTopic.class).values())
                .extracting(NewTopic::name)
                .containsExactlyInAnyOrderElementsOf(expected));
    }

    /** Defaults: the broker's partition count and replication factor; one partition for a dead-letter topic. */
    @Test
    void usesTheDocumentedDefaults() {
        runner.run(context -> {
            for (String name : OWN_TOPICS) {
                NewTopic own = topic(context, name);
                assertThat(own.numPartitions()).as(name).isEqualTo(-1);
                assertThat(own.replicationFactor()).as(name + " replicas").isEqualTo((short) -1);
                NewTopic dead = topic(context, name + DlapKafkaFactoryBuilder.DEAD_LETTER_SUFFIX);
                assertThat(dead.numPartitions()).as(dead.name()).isEqualTo(1);
                assertThat(dead.replicationFactor()).as(dead.name() + " replicas").isEqualTo((short) -1);
                assertThat(dead.configs()).as(dead.name()).containsEntry("retention.ms", "1209600000")
                        .doesNotContainKey("cleanup.policy");
            }
        });
    }

    /** The partition count, replication factor and dead-letter retention come from {@code dlap.kafka.*}. */
    @Test
    void appliesTheConfiguredSettings() {
        runner.withPropertyValues("dlap.kafka.topic-partitions=6", "dlap.kafka.topic-replication-factor=3",
                "dlap.kafka.dlt-partitions=2", "dlap.kafka.dlt-retention=P7D").run(context -> {
            for (String name : OWN_TOPICS) {
                NewTopic own = topic(context, name);
                assertThat(own.numPartitions()).as(name).isEqualTo(6);
                assertThat(own.replicationFactor()).as(name + " replicas").isEqualTo((short) 3);
                NewTopic dead = topic(context, name + DlapKafkaFactoryBuilder.DEAD_LETTER_SUFFIX);
                assertThat(dead.numPartitions()).as(dead.name()).isEqualTo(2);
                assertThat(dead.replicationFactor()).as(dead.name() + " replicas").isEqualTo((short) 3);
                assertThat(dead.configs()).as(dead.name()).containsEntry("retention.ms", "604800000");
            }
        });
    }

    /** {@code dlap.kafka.provision-topics=false} declares nothing, for a cluster managed outside the service. */
    @Test
    void declaresNothingWhenProvisioningIsOff() {
        runner.withPropertyValues("dlap.kafka.provision-topics=false")
                .run(context -> assertThat(context.getBeansOfType(NewTopic.class)).isEmpty());
    }

    /** The switch is on when the property is absent, which is what a service without a setting gets. */
    @Test
    void provisioningIsOnByDefault() {
        runner.run(context -> assertThat(context.getBeansOfType(NewTopic.class)).isNotEmpty());
    }

    /** Zero partitions would be refused by the broker; the service says so at start-up, by name. */
    @Test
    void refusesAPartitionCountBelowOne() {
        runner.withPropertyValues("dlap.kafka.topic-partitions=0").run(context -> {
            assertThat(context).hasFailed();
            assertThat(context.getStartupFailure()).hasStackTraceContaining("dlap.kafka.topic-partitions");
        });
    }

    /** A zero retention would delete a record at once; the service refuses it, naming the property. */
    @Test
    void refusesANonPositiveRetention() {
        runner.withPropertyValues("dlap.kafka.dlt-retention=PT0S").run(context -> {
            assertThat(context).hasFailed();
            assertThat(context.getStartupFailure()).hasStackTraceContaining("dlap.kafka.dlt-retention");
        });
    }
}
