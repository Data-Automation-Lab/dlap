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

package com.dlap.reporting.kafka;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import org.awaitility.Awaitility;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.test.context.EmbeddedKafka;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import com.dlap.common.constants.DlapConstants;
import com.dlap.reporting.dto.NotificationRequestDTO;
import com.dlap.reporting.service.INotificationService;

/**
 * IDEM-01 (Q-855): {@link PolicyActionConsumer} records a policy fire once. The same {@code fireId} is
 * published to {@code dlap.policies.actions} three times (a redelivered source event makes the policy engine
 * republish the fire); the real listener, the real recorder and a real PostgreSQL (Flyway V5, unique index on
 * {@code fire_id}) leave one {@code policy_action_events} row and send one notification. A sentinel fire is
 * published after the copies on the same single-partition topic, so once it is stored the copies have been
 * processed. Docker must be running; the broker runs inside the test JVM.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "spring.kafka.bootstrap-servers=${spring.embedded.kafka.brokers}")
@Testcontainers
@EmbeddedKafka(partitions = 1, topics = DlapConstants.KafkaTopic.POLICY_ACTION_EVENT)
class PolicyActionConsumerDuplicateContainerTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:15-alpine");

    private static final Duration WAIT = Duration.ofSeconds(30);

    @Autowired
    private KafkaTemplate<String, Object> kafkaTemplate;

    @Autowired
    private JdbcTemplate jdbc;

    @MockitoBean
    private INotificationService notifications;

    private static Map<String, Object> fire(String fireId, String assetId, String actionType) {
        Map<String, Object> e = new LinkedHashMap<>();
        e.put("schemaVersion", "1");
        e.put("policyId", "pii-alert");
        if (fireId != null) {
            e.put("fireId", fireId);
        }
        e.put("assetId", assetId);
        e.put("actionType", actionType);
        e.put("actionParameters", Map.of());
        return e;
    }

    private int rowsOfFire(String fireId) {
        Integer n = jdbc.queryForObject(
                "SELECT count(*) FROM dlap_reporting.policy_action_events WHERE fire_id = ?", Integer.class, fireId);
        return n == null ? 0 : n;
    }

    private int rowsOfAsset(String assetId) {
        Integer n = jdbc.queryForObject(
                "SELECT count(*) FROM dlap_reporting.policy_action_events WHERE asset_id = ?", Integer.class,
                assetId);
        return n == null ? 0 : n;
    }

    @Test
    void aRepeatedFireIdStoresOneRowAndSendsOneNotification() {
        String fireId = UUID.randomUUID().toString();
        String asset = "asset-" + UUID.randomUUID();
        String sentinelFire = UUID.randomUUID().toString();
        Map<String, Object> notify = fire(fireId, asset, "NOTIFY");

        kafkaTemplate.send(DlapConstants.KafkaTopic.POLICY_ACTION_EVENT, asset, notify);
        kafkaTemplate.send(DlapConstants.KafkaTopic.POLICY_ACTION_EVENT, asset, notify);
        kafkaTemplate.send(DlapConstants.KafkaTopic.POLICY_ACTION_EVENT, asset, notify);
        kafkaTemplate.send(DlapConstants.KafkaTopic.POLICY_ACTION_EVENT, asset,
                fire(sentinelFire, asset, "addLabel"));

        Awaitility.await().atMost(WAIT).untilAsserted(() -> assertThat(rowsOfFire(sentinelFire)).isEqualTo(1));

        assertThat(rowsOfFire(fireId)).as("rows for the repeated fire").isEqualTo(1);
        verify(notifications, times(1)).sendNotification(any(NotificationRequestDTO.class));
        assertThat(jdbc.queryForObject(
                "SELECT notification_dispatched FROM dlap_reporting.policy_action_events WHERE fire_id = ?",
                Boolean.class, fireId)).isTrue();
    }

    @Test
    void anActionWithoutAFireIdIsStillRecordedEachTimeAndNeverNotifiesUnlessItNotifies() {
        String asset = "asset-" + UUID.randomUUID();
        String sentinelFire = UUID.randomUUID().toString();
        Map<String, Object> noFire = fire(null, asset, "addLabel");

        kafkaTemplate.send(DlapConstants.KafkaTopic.POLICY_ACTION_EVENT, asset, noFire);
        kafkaTemplate.send(DlapConstants.KafkaTopic.POLICY_ACTION_EVENT, asset, noFire);
        kafkaTemplate.send(DlapConstants.KafkaTopic.POLICY_ACTION_EVENT, asset,
                fire(sentinelFire, asset, "addLabel"));

        Awaitility.await().atMost(WAIT).untilAsserted(() -> assertThat(rowsOfFire(sentinelFire)).isEqualTo(1));

        assertThat(rowsOfAsset(asset)).as("two id-less rows plus the sentinel").isEqualTo(3);
        verify(notifications, never()).sendNotification(any(NotificationRequestDTO.class));
    }
}
