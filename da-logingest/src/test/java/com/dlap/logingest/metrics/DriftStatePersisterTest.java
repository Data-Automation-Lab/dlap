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

package com.dlap.logingest.metrics;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.dlap.logingest.metrics.LogHealthFixtures.MutableClock;
import com.dlap.logingest.metrics.ParseTelemetry.DriftKey;
import com.dlap.logingest.metrics.ParseTelemetry.FillField;
import com.dlap.logingest.metrics.ParseTelemetry.FillReading;
import com.dlap.logingest.metrics.ParseTelemetry.UnknownPath;
import com.dlap.logingest.parse.core.DefaultParserRegistry;
import com.dlap.logingest.repository.LogIngestPostgres;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.util.List;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;

/**
 * Q-364 acceptance: the unknown JSON paths and the fill baselines survive a restart, so the weekly
 * drift report is whole (Testcontainers PostgreSQL with Flyway V5; requires Docker). "Restart"
 * is a new {@link ParseTelemetry} and a new persister on the same database, which is exactly what a
 * new JVM starts with. Records go through the real normalized-family registry.
 */
class DriftStatePersisterTest {

    private static final DriftKey KEY = new DriftKey(LogHealthFixtures.KIND, LogHealthFixtures.ENVELOPE, "v1");
    private static final String EXTRA = ",\"surprise\":\"secret-value-1\",\"another\":{\"deep\":\"secret-value-2\"}";

    private final DefaultParserRegistry registry = LogHealthFixtures.registry();
    private final MutableClock clock = new MutableClock(LogHealthFixtures.T0);
    private final LogIngestMetricsProperties props = LogIngestMetricsProperties.defaults();

    private DataSource ds;
    private JdbcTemplate jdbc;
    private DriftStateRepository repository;

    @BeforeEach
    void freshDatabase() {
        ds = LogIngestPostgres.migratedDatabase();
        jdbc = new JdbcTemplate(ds);
        repository = new DriftStateRepository(jdbc, LogIngestPostgres.SCHEMA);
    }

    private ParseTelemetry newTelemetry() {
        return new ParseTelemetry(clock, props);
    }

    private DriftStatePersister persister(ParseTelemetry telemetry, Duration retention) {
        return new DriftStatePersister(telemetry, repository, new DataSourceTransactionManager(ds), clock, retention);
    }

    private void feed(ParseTelemetry t, String prefix, int events, int withoutRegion, boolean unknownMembers) {
        for (int i = 0; i < events; i++) {
            String text = LogHealthFixtures.write(prefix + i);
            if (i < withoutRegion) {
                text = text.replace(",\"targetRegion\":\"us-east-1\"", "");
            }
            if (unknownMembers) {
                text = text.replace("}", EXTRA + "}");
            }
            t.recordParse(LogHealthFixtures.parse(registry, LogHealthFixtures.BINDING, text), "v1");
        }
    }

    @Test
    void unknownPathsSurviveARestart() {
        ParseTelemetry before = newTelemetry();
        feed(before, "a", 3, 0, true);
        List<UnknownPath> seen = before.unknownPaths(10);
        assertThat(seen).hasSize(2).allSatisfy(p -> assertThat(p.count()).isEqualTo(3));
        persister(before, Duration.ofDays(30)).flush();

        ParseTelemetry after = newTelemetry();
        assertThat(after.unknownPaths(10)).as("a new instance starts empty").isEmpty();
        int restored = persister(after, Duration.ofDays(30)).restore();

        assertThat(restored).isEqualTo(2);
        assertThat(after.unknownPaths(10)).containsExactlyInAnyOrderElementsOf(seen);
        assertThat(after.unknownPaths(10)).allSatisfy(p -> assertThat(p.path()).doesNotContain("secret"));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM dlap_logingest.unknown_path_observation",
                Integer.class)).isEqualTo(2);
    }

    @Test
    void aSecondFlushAddsOnlyWhatIsNewAndARestartDoesNotDoubleCount() {
        ParseTelemetry t = newTelemetry();
        DriftStatePersister p = persister(t, Duration.ofDays(30));
        feed(t, "a", 3, 0, true);
        p.flush();
        assertThat(p.flush()).as("nothing new, nothing written").isZero();
        feed(t, "b", 2, 0, true);
        p.flush();

        assertThat(jdbc.queryForList("SELECT observed_count FROM dlap_logingest.unknown_path_observation",
                Long.class)).containsExactly(5L, 5L);

        // Restart, restore, see two more records, flush: the stored 5 plus the 2 new, not 5 + 5 + 2.
        ParseTelemetry restarted = newTelemetry();
        DriftStatePersister p2 = persister(restarted, Duration.ofDays(30));
        p2.restore();
        feed(restarted, "c", 2, 0, true);
        assertThat(restarted.unknownPaths(10)).allSatisfy(x -> assertThat(x.count()).isEqualTo(7));
        p2.flush();

        assertThat(jdbc.queryForList("SELECT observed_count FROM dlap_logingest.unknown_path_observation",
                Long.class)).containsExactly(7L, 7L);
    }

    @Test
    void twoReplicasFlushingTheSamePathSumTheirCounts() {
        ParseTelemetry a = newTelemetry();
        ParseTelemetry b = newTelemetry();
        feed(a, "a", 3, 0, true);
        feed(b, "b", 4, 0, true);

        persister(a, Duration.ofDays(30)).flush();
        persister(b, Duration.ofDays(30)).flush();

        assertThat(jdbc.queryForList("SELECT observed_count FROM dlap_logingest.unknown_path_observation",
                Long.class)).containsExactly(7L, 7L);
    }

    @Test
    void aFillBaselineSurvivesARestartAndStillFlagsTheDrop() {
        ParseTelemetry before = newTelemetry();
        feed(before, "a", 100, 0, false);
        assertThat(before.fillDrops()).isEmpty();
        persister(before, Duration.ofDays(30)).flush();
        assertThat(jdbc.queryForObject("SELECT baseline FROM dlap_logingest.fill_baseline WHERE field = ?",
                Double.class, FillField.TARGET_REGION.label())).isEqualTo(1.0);

        // The restart: the instance forgot the good hour. 100 events lose the region field.
        ParseTelemetry after = newTelemetry();
        persister(after, Duration.ofDays(30)).restore();
        feed(after, "b", 100, 100, false);

        List<FillReading> drops = after.fillDrops();
        assertThat(drops).as("without the stored baseline this drop was invisible after a restart")
                .anySatisfy(r -> {
                    assertThat(r.field()).isEqualTo(FillField.TARGET_REGION.label());
                    assertThat(r.baseline()).isEqualTo(1.0);
                    assertThat(r.ratio()).isZero();
                });
        ParseTelemetry withoutStore = newTelemetry();
        feed(withoutStore, "c", 100, 100, false);
        assertThat(withoutStore.fillDrops()).as("control: no store, no baseline, no drop").isEmpty();
    }

    @Test
    void aBaselineIsNeverLoweredByAnotherReplica() {
        ParseTelemetry high = newTelemetry();
        feed(high, "a", 100, 0, false);
        persister(high, Duration.ofDays(30)).flush();

        ParseTelemetry low = newTelemetry();
        feed(low, "b", 100, 40, false);
        persister(low, Duration.ofDays(30)).flush();

        assertThat(jdbc.queryForObject("SELECT baseline FROM dlap_logingest.fill_baseline WHERE field = ?",
                Double.class, FillField.TARGET_REGION.label())).isEqualTo(1.0);
    }

    @Test
    void rowsNotSeenWithinTheRetentionWindowAreNotRestoredAndArePruned() {
        ParseTelemetry old = newTelemetry();
        feed(old, "a", 60, 0, true);
        persister(old, Duration.ofDays(30)).flush();
        clock.advance(Duration.ofDays(31));

        ParseTelemetry later = newTelemetry();
        DriftStatePersister p = persister(later, Duration.ofDays(30));
        assertThat(p.restore()).as("everything is older than the window").isZero();
        assertThat(later.unknownPaths(10)).isEmpty();
        p.flush();

        assertThat(jdbc.queryForObject("SELECT count(*) FROM dlap_logingest.unknown_path_observation",
                Integer.class)).as("the flush pruned the old rows").isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM dlap_logingest.fill_baseline", Integer.class)).isZero();
    }

    @Test
    void aHealthyFieldKeepsItsBaselineFreshButALowOneAgesOut() {
        ParseTelemetry t = newTelemetry();
        DriftStatePersister p = persister(t, Duration.ofDays(30));
        feed(t, "a", 100, 0, false);
        p.flush();

        // 20 days on, the field is still healthy: the flush refreshes updated_at.
        clock.advance(Duration.ofDays(20));
        feed(t, "b", 60, 0, false);
        p.flush();
        // 20 more days (40 since the first flush): still there, because it was refreshed at day 20.
        clock.advance(Duration.ofDays(20));
        p.flush();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM dlap_logingest.fill_baseline WHERE field = ?",
                Integer.class, FillField.TARGET_REGION.label()))
                .as("refreshed at day 20, so still inside the window at day 40").isEqualTo(1);

        // From now on the region field is gone and no healthy reading refreshes the row.
        clock.advance(Duration.ofDays(1));
        feed(t, "c", 100, 100, false);
        p.flush();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM dlap_logingest.fill_baseline WHERE field = ?",
                Integer.class, FillField.TARGET_REGION.label())).isEqualTo(1);
        clock.advance(Duration.ofDays(31));
        p.flush();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM dlap_logingest.fill_baseline WHERE field = ?",
                Integer.class, FillField.TARGET_REGION.label()))
                .as("30 days without a healthy reading: the baseline retires").isZero();
    }

    @Test
    void aFailedFlushPutsThePathCountsBack() {
        ParseTelemetry t = newTelemetry();
        feed(t, "a", 3, 0, true);
        DriftStatePersister broken = new DriftStatePersister(t, new DriftStateRepository(jdbc, "no_such_schema"),
                new DataSourceTransactionManager(ds), clock, Duration.ofDays(30));

        assertThatThrownBy(broken::flush).isInstanceOf(RuntimeException.class);
        persister(t, Duration.ofDays(30)).flush();

        assertThat(jdbc.queryForList("SELECT observed_count FROM dlap_logingest.unknown_path_observation",
                Long.class)).as("the failed flush lost nothing").containsExactly(3L, 3L);
    }

    @Test
    void everyFailedFlushCountsOnTheFlushFailureMeterAndAGoodFlushDoesNot() {
        SimpleMeterRegistry meters = new SimpleMeterRegistry();
        ParseTelemetry t = newTelemetry();
        feed(t, "a", 3, 0, true);
        DriftStatePersister broken = new DriftStatePersister(t, new DriftStateRepository(jdbc, "no_such_schema"),
                new DataSourceTransactionManager(ds), clock, meters, Duration.ofDays(30));
        assertThat(meters.get(DriftStatePersister.FLUSH_FAILURES).counter().count())
                .as("registered at 0 at start, so the series exists before the first failure").isZero();

        assertThatThrownBy(broken::flush).isInstanceOf(RuntimeException.class);
        broken.scheduledFlush();
        broken.flushOnShutdown();

        assertThat(meters.get(DriftStatePersister.FLUSH_FAILURES).counter().count())
                .as("the direct, scheduled and shutdown flushes each failed once").isEqualTo(3.0);

        DriftStatePersister good = new DriftStatePersister(t, repository, new DataSourceTransactionManager(ds), clock,
                meters, Duration.ofDays(30));
        assertThat(good.flush()).isPositive();
        assertThat(meters.get(DriftStatePersister.FLUSH_FAILURES).counter().count())
                .as("a flush that succeeds adds nothing").isEqualTo(3.0);
        assertThat(DriftStatePersister.FLUSH_FAILURES).isEqualTo("dlap.logingest.drift.state.flush.failures");
    }

    @Test
    void restoreAndFlushQuietlyNeverBreakTheService() {
        ParseTelemetry t = newTelemetry();
        DriftStatePersister broken = new DriftStatePersister(t, new DriftStateRepository(jdbc, "no_such_schema"),
                new DataSourceTransactionManager(ds), clock, Duration.ofDays(30));

        broken.restoreOnStart();
        broken.scheduledFlush();
        broken.flushOnShutdown();

        assertThat(t.unknownPaths(10)).isEmpty();
    }

    @Test
    void keysWiderThanTheColumnsAreSkippedNotFatal() {
        int written = repository.addUnknownPaths(List.of(
                new ParseTelemetry.UnknownPathDelta(new DriftKey("K".repeat(200), "e", "v1"), "/wide", 1),
                new ParseTelemetry.UnknownPathDelta(KEY, "/ok", 2)), LogHealthFixtures.T0);

        assertThat(written).isEqualTo(1);
        assertThat(jdbc.queryForList("SELECT path FROM dlap_logingest.unknown_path_observation", String.class))
                .containsExactly("/ok");
    }

    @Test
    void theRetentionMustBePositive() {
        assertThatThrownBy(() -> persister(newTelemetry(), Duration.ZERO)).isInstanceOf(IllegalArgumentException.class);
    }
}
