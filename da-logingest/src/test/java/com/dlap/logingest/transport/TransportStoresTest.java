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

package com.dlap.logingest.transport;

import static org.assertj.core.api.Assertions.assertThat;

import com.dlap.logingest.model.BindingState;
import com.dlap.logingest.model.ConfigOrigin;
import com.dlap.logingest.model.ProcessedObjectStatus;
import com.dlap.logingest.model.SourceBindingEntity;
import com.dlap.logingest.repository.LogIngestPostgres;
import com.dlap.logingest.repository.ProcessedObjectRepository;
import com.dlap.logingest.repository.SourceBindingRepository;
import com.dlap.logingest.transport.cursor.JpaStreamCursorStore;
import com.dlap.logingest.transport.objects.JpaProcessedObjectStore;
import java.time.Instant;
import java.util.Map;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * The cursor and processed-object stores on the V3 tables, through JPA with
 * {@code ddl-auto=validate} (Testcontainers; requires Docker), so the entities are checked against
 * the migrated schema too.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({JpaStreamCursorStore.class, JpaProcessedObjectStore.class})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class TransportStoresTest {

    private static final DataSource DB = LogIngestPostgres.newDatabase();

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", () -> LogIngestPostgres.url(DB));
        r.add("spring.datasource.username", LogIngestPostgres::user);
        r.add("spring.datasource.password", LogIngestPostgres::password);
        r.add("spring.jpa.hibernate.ddl-auto", () -> "validate");
    }

    @Autowired
    private SourceBindingRepository bindings;

    @Autowired
    private JpaStreamCursorStore cursors;

    @Autowired
    private JpaProcessedObjectStore objects;

    @Autowired
    private ProcessedObjectRepository objectRows;

    @BeforeEach
    void binding() {
        if (bindings.existsById("aws-111111111111")) {
            return;
        }
        SourceBindingEntity b = new SourceBindingEntity();
        b.setBindingId("aws-111111111111");
        b.setSourceId("aws-org");
        b.setSourceKind("AWS_CLOUDTRAIL");
        b.setConfigOrigin(ConfigOrigin.FILE);
        b.setState(BindingState.ACTIVE);
        b.setEnabled(true);
        b.setScope(Map.of("account-id", "111111111111"));
        b.setEffectiveConfig(Map.of("id", "aws-111111111111"));
        b.setConfigHash("0".repeat(64));
        b.setFirstSyncedAt(Instant.parse("2026-10-19T00:00:00Z"));
        b.setLastSyncedAt(Instant.parse("2026-10-19T00:00:00Z"));
        bindings.save(b);
    }

    @Test
    void cursorsRoundTripAndTheHighWatermarkNeverMovesBack() {
        Instant t1 = Instant.parse("2026-10-19T10:00:00Z");
        Instant t2 = Instant.parse("2026-10-19T11:00:00Z");
        assertThat(cursors.load("aws-111111111111", "lookup-events", "us-east-1")).isEmpty();

        cursors.commit("aws-111111111111", "lookup-events", "us-east-1",
                new CursorPosition(t1, t2, Map.of("nextToken", "abc")));
        cursors.commit("aws-111111111111", "lookup-events", "us-east-1",
                new CursorPosition(t1.plusSeconds(60), t1, Map.of()));

        CursorPosition p = cursors.load("aws-111111111111", "lookup-events", "us-east-1").orElseThrow();
        assertThat(p.lowWatermark()).isEqualTo(t1.plusSeconds(60));
        assertThat(p.highWatermark()).as("high watermark kept at the newest seen").isEqualTo(t2);
        assertThat(p.state()).isEmpty();
        assertThat(cursors.load("aws-111111111111", "lookup-events", "eu-west-1")).isEmpty();
    }

    @Test
    void objectsAreCheckpointedPerEtagAndLongKeysFit() {
        String longKey = "AWSLogs/o-abc/111111111111/CloudTrail/us-east-1/2026/10/19/" + "Ã©".repeat(900);

        assertThat(objects.isDone("aws-111111111111", "s3-file", longKey, "etag-1")).isFalse();
        objects.markFailed("aws-111111111111", "s3-file", longKey, "etag-1");
        assertThat(objects.isDone("aws-111111111111", "s3-file", longKey, "etag-1")).isFalse();
        objects.markDone("aws-111111111111", "s3-file", longKey, "etag-1", 12, 1);

        assertThat(objects.isDone("aws-111111111111", "s3-file", longKey, "etag-1")).isTrue();
        assertThat(objects.isDone("aws-111111111111", "s3-file", longKey, "etag-2")).as("a rewritten object").isFalse();
        var row = objectRows.findAll().stream().filter(r -> r.getEtag().equals("etag-1")).findFirst().orElseThrow();
        assertThat(row.getStatus()).isEqualTo(ProcessedObjectStatus.DONE);
        assertThat(row.getAttempts()).isEqualTo(2);
        assertThat(objects.attempts("aws-111111111111", "s3-file", longKey, "etag-1")).isEqualTo(2);
        assertThat(objects.attempts("aws-111111111111", "s3-file", longKey, "etag-2")).isZero();
        objects.markFailed("aws-111111111111", "s3-file", longKey, "etag-2");
        objects.markFailed("aws-111111111111", "s3-file", longKey, "etag-2");
        assertThat(objects.attempts("aws-111111111111", "s3-file", longKey, "etag-2")).isEqualTo(2);
        assertThat(row.getRecords()).isEqualTo(12);
        assertThat(row.getObjectKey()).isEqualTo(longKey);
    }
}
