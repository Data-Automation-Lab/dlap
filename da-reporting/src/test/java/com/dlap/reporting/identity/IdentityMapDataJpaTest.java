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

package com.dlap.reporting.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

/**
 * The identity map on PostgreSQL: {@code V4__principal_identity_map.sql} applied by Flyway and
 * validated against {@link PrincipalIdentityMapping} ({@code ddl-auto=validate}), the derived
 * lookup query, the seed sync and the table constraints. Docker must be running.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Testcontainers
class IdentityMapDataJpaTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:15-alpine");

    private static final Instant T0 = Instant.parse("2026-10-20T09:00:00Z");

    @Autowired
    private PrincipalIdentityMapRepository repository;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Autowired
    private JdbcTemplate jdbc;

    @TempDir
    Path dir;

    private IdentityMapSeeder seeder(String seedFile, Instant at) {
        StaticListableBeanFactory beans = new StaticListableBeanFactory();
        beans.addBean("clock", Clock.fixed(at, ZoneOffset.UTC));
        ObjectProvider<Clock> clocks = beans.getBeanProvider(Clock.class);
        return new IdentityMapSeeder(repository, transactionManager, clocks, seedFile);
    }

    private Map<String, PrincipalIdentityMapping> byKey() {
        return repository.findAll().stream()
                .collect(Collectors.toMap(m -> m.getProvider() + "|" + m.getPrincipalKey(), m -> m));
    }

    private Path file(String content) throws IOException {
        Path p = dir.resolve("identity-map.csv");
        Files.writeString(p, content, StandardCharsets.UTF_8);
        return p;
    }

    @Test
    void seedRun_insertsTheFile_andTheResolverFindsItOnPostgres() throws IOException {
        Path seed = file("provider,principal,subject\n"
                + "GCP,serviceAccount:agent@acme-prod.iam.gserviceaccount.com,svc-marketing-agent\n"
                + "AWS,arn:aws:iam::123456789012:role/analytics-reader,svc-analytics\n");

        seeder(seed.toString(), T0).run(new DefaultApplicationArguments());

        Map<String, PrincipalIdentityMapping> rows = byKey();
        assertThat(rows).containsOnlyKeys("GCP|agent@acme-prod.iam.gserviceaccount.com",
                "AWS|arn:aws:iam::123456789012:role/analytics-reader");
        PrincipalIdentityMapping gcp = rows.get("GCP|agent@acme-prod.iam.gserviceaccount.com");
        assertThat(gcp.getSource()).isEqualTo(PrincipalIdentityMapping.Source.SEED);
        assertThat(gcp.getCreatedAt()).isEqualTo(T0);
        assertThat(gcp.getUpdatedAt()).isEqualTo(T0);

        PrincipalIdentityResolver resolver = new PrincipalIdentityResolver(repository, new SimpleMeterRegistry());
        assertThat(resolver.resolve(IdentityProviderScope.GCP, "Agent@acme-prod.iam.gserviceaccount.com")
                .orElseThrow().subjectId()).isEqualTo("svc-marketing-agent");
        assertThat(resolver.resolve(IdentityProviderScope.AWS,
                "arn:aws:sts::123456789012:assumed-role/analytics-reader/session-1").orElseThrow().subjectId())
                .isEqualTo("svc-analytics");
        assertThat(resolver.resolve(IdentityProviderScope.AZURE, "agent@acme-prod.iam.gserviceaccount.com")
                .orElseThrow().mapped()).as("a GCP entry does not apply to an Azure log").isFalse();
    }

    @Test
    void resync_updatesChangedSubjects_removesDroppedSeedRows_andKeepsManualRows() {
        jdbc.update("INSERT INTO dlap_reporting.principal_identity_map (provider, principal_key, subject_id) VALUES (?, ?, ?)",
                "ANY", "ops@example.com", "ops-team");
        jdbc.update("INSERT INTO dlap_reporting.principal_identity_map (provider, principal_key, subject_id) VALUES (?, ?, ?)",
                "ANY", "bob@example.com", "bob-manual");
        IdentityMapSeeder first = seeder("unused", T0);
        first.sync(List.of(
                new IdentityMapSeedFile.Entry(IdentityProviderScope.GCP, "a@x.com", "alice"),
                new IdentityMapSeedFile.Entry(IdentityProviderScope.GCP, "c@x.com", "carol")));

        IdentityMapSeeder.SyncResult second = seeder("unused", T0.plusSeconds(60)).sync(List.of(
                new IdentityMapSeedFile.Entry(IdentityProviderScope.GCP, "a@x.com", "alice-2"),
                new IdentityMapSeedFile.Entry(IdentityProviderScope.ANY, "bob@example.com", "bob")));

        assertThat(second).isEqualTo(new IdentityMapSeeder.SyncResult(0, 2, 1, 0));
        Map<String, PrincipalIdentityMapping> rows = byKey();
        assertThat(rows).containsOnlyKeys("GCP|a@x.com", "ANY|bob@example.com", "ANY|ops@example.com");
        assertThat(rows.get("GCP|a@x.com").getSubjectId()).isEqualTo("alice-2");
        assertThat(rows.get("GCP|a@x.com").getCreatedAt()).isEqualTo(T0);
        assertThat(rows.get("GCP|a@x.com").getUpdatedAt()).isEqualTo(T0.plusSeconds(60));
        assertThat(rows.get("ANY|bob@example.com").getSource())
                .as("the file owns every key it names").isEqualTo(PrincipalIdentityMapping.Source.SEED);
        assertThat(rows.get("ANY|ops@example.com").getSource()).isEqualTo(PrincipalIdentityMapping.Source.MANUAL);

        IdentityMapSeeder.SyncResult third = seeder("unused", T0.plusSeconds(120)).sync(List.of(
                new IdentityMapSeedFile.Entry(IdentityProviderScope.GCP, "a@x.com", "alice-2"),
                new IdentityMapSeedFile.Entry(IdentityProviderScope.ANY, "bob@example.com", "bob")));
        assertThat(third).isEqualTo(new IdentityMapSeeder.SyncResult(0, 0, 0, 2));
    }

    @Test
    void run_withoutASeedFile_writesNothing() {
        seeder("", T0).run(new DefaultApplicationArguments());

        assertThat(repository.count()).isZero();
    }

    @Test
    void run_withAMissingSeedFile_failsTheStart() {
        IdentityMapSeeder seeder = seeder(dir.resolve("absent.csv").toString(), T0);

        assertThatThrownBy(() -> seeder.run(new DefaultApplicationArguments()))
                .isInstanceOf(UncheckedIOException.class)
                .hasMessageContaining(IdentityMapSeeder.SEED_FILE_PROPERTY);
    }

    @Test
    void run_withAnInvalidSeedFile_failsTheStart() throws IOException {
        IdentityMapSeeder seeder = seeder(file("provider,principal,subject\nMARS,a@x.com,s\n").toString(), T0);

        assertThatThrownBy(() -> seeder.run(new DefaultApplicationArguments()))
                .isInstanceOf(IdentityMapSeedFile.InvalidSeedFileException.class);
        assertThat(repository.count()).isZero();
    }

    @Test
    void table_allowsOneKeyInTwoScopes_butRejectsADuplicateInOneScope() {
        jdbc.update("INSERT INTO dlap_reporting.principal_identity_map (provider, principal_key, subject_id) VALUES ('GCP', 'k', 's')");
        jdbc.update("INSERT INTO dlap_reporting.principal_identity_map (provider, principal_key, subject_id) VALUES ('ANY', 'k', 's')");

        assertThatThrownBy(() -> jdbc.update(
                "INSERT INTO dlap_reporting.principal_identity_map (provider, principal_key, subject_id) VALUES ('GCP', 'k', 't')"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void table_rejectsAnUnknownProvider() {
        assertThatThrownBy(() -> jdbc.update(
                "INSERT INTO dlap_reporting.principal_identity_map (provider, principal_key, subject_id) VALUES ('MARS', 'k2', 't')"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void table_rejectsAnEmptyKey() {
        assertThatThrownBy(() -> jdbc.update(
                "INSERT INTO dlap_reporting.principal_identity_map (provider, principal_key, subject_id) VALUES ('GCP', '', 't')"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }
}
