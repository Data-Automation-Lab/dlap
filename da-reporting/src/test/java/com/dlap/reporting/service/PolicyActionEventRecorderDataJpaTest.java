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

package com.dlap.reporting.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import com.dlap.reporting.model.PolicyActionEventRecord;
import com.dlap.reporting.repository.PolicyActionEventRecordRepository;

/**
 * IDEM-01 (Q-855) on PostgreSQL: Flyway V5 adds {@code fire_id} with a partial unique index, the entity
 * validates against it ({@code ddl-auto=validate}), and {@link PolicyActionEventRecorder} records a fire once.
 * Docker must be running.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(PolicyActionEventRecorder.class)
@Testcontainers
class PolicyActionEventRecorderDataJpaTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:15-alpine");

    @Autowired
    private PolicyActionEventRecorder recorder;

    @Autowired
    private PolicyActionEventRecordRepository repository;

    @Autowired
    private JdbcTemplate jdbc;

    private static Map<String, Object> event(String fireId) {
        Map<String, Object> e = new LinkedHashMap<>();
        e.put("assetId", "asset-1");
        e.put("policyId", "policy-1");
        e.put("actionType", "NOTIFY");
        if (fireId != null) {
            e.put("fireId", fireId);
        }
        return e;
    }

    @Test
    void aRepeatedFireIdWritesOneRowAndDispatchesOnce() {
        String fireId = UUID.randomUUID().toString();
        AtomicInteger dispatched = new AtomicInteger();

        boolean first = recorder.recordOnce(event(fireId), () -> {
            dispatched.incrementAndGet();
            return true;
        });
        boolean second = recorder.recordOnce(event(fireId), () -> {
            dispatched.incrementAndGet();
            return true;
        });

        assertThat(first).isTrue();
        assertThat(second).isFalse();
        assertThat(dispatched).hasValue(1);
        Integer rows = jdbc.queryForObject("SELECT count(*) FROM dlap_reporting.policy_action_events WHERE fire_id = ?",
                Integer.class, fireId);
        assertThat(rows).isEqualTo(1);
        Boolean flag = jdbc.queryForObject(
                "SELECT notification_dispatched FROM dlap_reporting.policy_action_events WHERE fire_id = ?", Boolean.class, fireId);
        assertThat(flag).isTrue();
        assertThat(recorder.isRecorded(fireId)).isTrue();
        assertThat(recorder.isRecorded(UUID.randomUUID().toString())).isFalse();
    }

    @Test
    void theDatabaseItselfRefusesASecondRowForOneFireId() {
        String fireId = UUID.randomUUID().toString();
        PolicyActionEventRecord a = new PolicyActionEventRecord();
        a.setFireId(fireId);
        repository.saveAndFlush(a);
        PolicyActionEventRecord b = new PolicyActionEventRecord();
        b.setFireId(fireId);

        assertThatThrownBy(() -> repository.saveAndFlush(b)).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void rowsWithoutAFireIdAreUnlimitedAndOldRowsKeepWorking() {
        // a row as written before V5 (no fire_id column value), plus actions that carry no usable id
        jdbc.update("INSERT INTO dlap_reporting.policy_action_events (id, received_at, notification_dispatched, action_type)"
                + " VALUES (?, now(), false, 'NOTIFY')", UUID.randomUUID());
        recorder.recordOnce(event(null), () -> false);
        recorder.recordOnce(event(null), () -> false);
        recorder.recordOnce(event("  "), () -> false);

        Integer nulls = jdbc.queryForObject(
                "SELECT count(*) FROM dlap_reporting.policy_action_events WHERE fire_id IS NULL", Integer.class);
        assertThat(nulls).isEqualTo(4);
    }

    @Test
    void aFireIdTooLongForTheColumnIsNotUsedAsAKey() {
        String tooLong = "x".repeat(PolicyActionEventRecorder.FIRE_ID_MAX_LENGTH + 1);

        assertThat(PolicyActionEventRecorder.fireIdOf(event(tooLong))).isNull();
        assertThat(PolicyActionEventRecorder.fireIdOf(event("f-1"))).isEqualTo("f-1");
        assertThat(PolicyActionEventRecorder.fireIdOf(event(null))).isNull();
    }
}
