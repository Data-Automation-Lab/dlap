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

package com.dlap.logingest.backfill;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.dlap.logingest.model.BindingState;
import com.dlap.logingest.model.ConfigOrigin;
import com.dlap.logingest.model.SourceBindingEntity;
import com.dlap.logingest.repository.LogIngestPostgres;
import com.dlap.logingest.repository.SourceBindingRepository;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * The job table on the V3 schema, through JPA with {@code ddl-auto=validate} (Testcontainers; requires
 * Docker): the entity matches the table, the partial unique index allows one active job per binding, and
 * every transition is a guarded update that a cancel cannot lose to a progress write.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(JpaBackfillJobs.class)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class JpaBackfillJobsTest {

    private static final DataSource DB = LogIngestPostgres.newDatabase();
    private static final Instant T0 = Instant.parse("2026-10-01T00:00:00Z");

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
    private JpaBackfillJobs jobs;

    @Autowired
    private BackfillJobRepository repository;

    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void bindings() {
        repository.deleteAll();
        for (String id : List.of("gcp-1", "gcp-2")) {
            if (bindings.existsById(id)) {
                continue;
            }
            SourceBindingEntity b = new SourceBindingEntity();
            b.setBindingId(id);
            b.setSourceId("gcp");
            b.setSourceKind("GCP_CLOUD_AUDIT");
            b.setConfigOrigin(ConfigOrigin.FILE);
            b.setState(BindingState.ACTIVE);
            b.setEnabled(true);
            b.setScope(Map.of());
            b.setEffectiveConfig(Map.of());
            b.setConfigHash("0".repeat(64));
            b.setFirstSyncedAt(T0);
            b.setLastSyncedAt(T0);
            bindings.save(b);
        }
    }

    private BackfillJob insert(String binding, Instant at) {
        return jobs.insert(UUID.randomUUID(), binding, "logging-api", T0, T0.plusSeconds(86400), "ops-admin", at);
    }

    @Test
    void aNewJobIsPendingWithEmptyProgress() {
        BackfillJob j = insert("gcp-1", T0);

        BackfillJob back = jobs.find(j.id()).orElseThrow();

        assertThat(back).isEqualTo(j);
        assertThat(back.status()).isEqualTo(BackfillStatus.PENDING);
        assertThat(back.from()).isEqualTo(T0);
        assertThat(back.to()).isEqualTo(T0.plusSeconds(86400));
        assertThat(back.requestedBy()).isEqualTo("ops-admin");
        assertThat(back.progress()).isEqualTo(BackfillProgress.EMPTY);
        assertThat(back.startedAt()).isNull();
        assertThat(back.finishedAt()).isNull();
    }

    @Test
    void aBindingCanHaveOnlyOneActiveJobAtTheDatabaseToo() {
        BackfillJob first = insert("gcp-1", T0);

        assertThatThrownBy(() -> insert("gcp-1", T0.plusSeconds(1)))
                .isInstanceOf(BackfillJobs.ActiveJobExistsException.class);
        assertThat(insert("gcp-2", T0).bindingId()).as("another binding is independent").isEqualTo("gcp-2");

        // A racing request passes the application check and meets the partial unique index of V3.
        assertThatThrownBy(() -> jdbc.update("INSERT INTO dlap_logingest.backfill_job (id, binding_id, stream_id, "
                        + "from_ts, to_ts, status, requested_by, requested_at) VALUES (?, 'gcp-1', 's', now() - "
                        + "interval '2 day', now() - interval '1 day', 'RUNNING', 'x', now())", UUID.randomUUID()))
                .isInstanceOf(DataIntegrityViolationException.class);

        jobs.markRunning(first.id(), T0);
        jobs.finish(first.id(), BackfillStatus.FAILED, T0, "stopped", BackfillProgress.EMPTY);
        assertThat(insert("gcp-1", T0.plusSeconds(2)).status()).as("an ended job frees the binding")
                .isEqualTo(BackfillStatus.PENDING);
    }

    @Test
    void progressIsStoredAsJsonAndSurvivesTheRoundTrip() {
        BackfillJob j = insert("gcp-1", T0);
        assertThat(jobs.markRunning(j.id(), T0.plusSeconds(5))).isTrue();
        BackfillProgress p = BackfillProgress.EMPTY
                .withPartition(new BackfillProgress.Partition("east", T0.plusSeconds(60), T0.plusSeconds(90),
                        Map.of("pageToken", "abc", "n", 12), false))
                .withPartition(new BackfillProgress.Partition("", T0, null, Map.of(), true))
                .afterBatch(500, 3, 7, T0.plusSeconds(61));

        assertThat(jobs.saveProgress(j.id(), p)).isTrue();

        BackfillJob back = jobs.find(j.id()).orElseThrow();
        assertThat(back.status()).isEqualTo(BackfillStatus.RUNNING);
        assertThat(back.startedAt()).isEqualTo(T0.plusSeconds(5));
        assertThat(back.progress().batches()).isEqualTo(1);
        assertThat(back.progress().records()).isEqualTo(500);
        assertThat(back.progress().quarantined()).isEqualTo(3);
        assertThat(back.progress().duplicates()).isEqualTo(7);
        assertThat(back.progress().heartbeat()).isEqualTo(T0.plusSeconds(61));
        BackfillProgress.Partition east = back.progress().partition("east").orElseThrow();
        assertThat(east.low()).isEqualTo(T0.plusSeconds(60));
        assertThat(east.high()).isEqualTo(T0.plusSeconds(90));
        assertThat(east.state()).containsEntry("pageToken", "abc").containsEntry("n", 12);
        assertThat(back.progress().partition("").orElseThrow().done()).isTrue();
        assertThat(jdbc.queryForObject("SELECT jsonb_array_length(progress -> 'partitions') FROM "
                + "dlap_logingest.backfill_job WHERE id = ?", Integer.class, j.id())).isEqualTo(2);
    }

    @Test
    void markRunningKeepsTheFirstStartTime() {
        BackfillJob j = insert("gcp-1", T0);
        jobs.markRunning(j.id(), T0.plusSeconds(5));

        assertThat(jobs.markRunning(j.id(), T0.plusSeconds(500))).isTrue();

        assertThat(jobs.find(j.id()).orElseThrow().startedAt()).isEqualTo(T0.plusSeconds(5));
    }

    @Test
    void aCancelCannotBeOverwrittenByAProgressWriteOrAFinish() {
        BackfillJob j = insert("gcp-1", T0);
        jobs.markRunning(j.id(), T0);
        assertThat(jobs.cancel(j.id(), T0.plusSeconds(9), "cancelled by ops-admin")).isTrue();

        assertThat(jobs.saveProgress(j.id(), BackfillProgress.EMPTY.afterBatch(1, 0, 0, T0))).isFalse();
        assertThat(jobs.finish(j.id(), BackfillStatus.SUCCEEDED, T0, "", BackfillProgress.EMPTY)).isFalse();
        assertThat(jobs.markRunning(j.id(), T0)).as("a cancelled job is never revived").isFalse();
        assertThat(jobs.cancel(j.id(), T0, "again")).isFalse();

        BackfillJob back = jobs.find(j.id()).orElseThrow();
        assertThat(back.status()).isEqualTo(BackfillStatus.CANCELLED);
        assertThat(back.errorDetail()).isEqualTo("cancelled by ops-admin");
        assertThat(back.finishedAt()).isEqualTo(T0.plusSeconds(9));
        assertThat(back.progress().batches()).isZero();
    }

    @Test
    void finishNeedsATerminalStateAndAPendingJobCannotBeFinishedOrGetProgress() {
        BackfillJob j = insert("gcp-1", T0);

        assertThat(jobs.saveProgress(j.id(), BackfillProgress.EMPTY)).as("only a running job takes progress")
                .isFalse();
        assertThat(jobs.finish(j.id(), BackfillStatus.SUCCEEDED, T0, "", BackfillProgress.EMPTY)).isFalse();
        assertThatThrownBy(() -> jobs.finish(j.id(), BackfillStatus.RUNNING, T0, "", BackfillProgress.EMPTY))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(jobs.cancel(j.id(), T0, "pending is cancellable")).isTrue();
    }

    @Test
    void anErrorDetailIsBoundedToTheColumn() {
        BackfillJob j = insert("gcp-1", T0);
        jobs.markRunning(j.id(), T0);

        jobs.finish(j.id(), BackfillStatus.FAILED, T0, "x".repeat(5000), BackfillProgress.EMPTY);

        assertThat(jobs.find(j.id()).orElseThrow().errorDetail()).hasSize(1000);
    }

    @Test
    void listCountAndRunnableFilterAndOrder() {
        BackfillJob a = insert("gcp-1", T0);
        jobs.markRunning(a.id(), T0);
        jobs.finish(a.id(), BackfillStatus.SUCCEEDED, T0, "", BackfillProgress.EMPTY);
        BackfillJob b = insert("gcp-1", T0.plusSeconds(10));
        BackfillJob c = insert("gcp-2", T0.plusSeconds(20));
        jobs.markRunning(c.id(), T0);

        assertThat(jobs.list(null, null, 10, 0)).extracting(BackfillJob::id).containsExactly(c.id(), b.id(), a.id());
        assertThat(jobs.list("gcp-1", null, 10, 0)).extracting(BackfillJob::id).containsExactly(b.id(), a.id());
        assertThat(jobs.list(null, BackfillStatus.RUNNING, 10, 0)).extracting(BackfillJob::id).containsExactly(c.id());
        assertThat(jobs.list("gcp-1", BackfillStatus.PENDING, 10, 0)).extracting(BackfillJob::id)
                .containsExactly(b.id());
        assertThat(jobs.list(null, null, 1, 1)).extracting(BackfillJob::id).containsExactly(b.id());
        assertThat(jobs.count(null, null)).isEqualTo(3);
        assertThat(jobs.count("gcp-1", BackfillStatus.SUCCEEDED)).isEqualTo(1);
        assertThat(jobs.runnable(10)).extracting(BackfillJob::id).containsExactly(b.id(), c.id());
        assertThat(jobs.runnable(1)).extracting(BackfillJob::id).containsExactly(b.id());
        assertThat(jobs.activeFor("gcp-1")).get().extracting(BackfillJob::id).isEqualTo(b.id());
        assertThat(jobs.activeFor("nobody")).isEmpty();
        assertThatThrownBy(() -> jobs.list(null, null, 10, 5)).isInstanceOf(IllegalArgumentException.class);
    }
}
