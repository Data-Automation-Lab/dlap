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

package com.dlap.catalog.schema;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jpa.test.autoconfigure.TestEntityManager;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;
import org.testcontainers.utility.MountableFile;

import com.dlap.catalog.model.AssetLineage;
import com.dlap.common.model.entity.Asset;
import com.dlap.common.model.entity.CloudResource;
import com.dlap.common.model.entity.User;

import jakarta.persistence.EntityManagerFactory;
import jakarta.persistence.metamodel.EntityType;

/**
 * DB-01 acceptance: the schema the platform really ships satisfies
 * {@code hibernate.ddl-auto=validate} for every entity da-catalog maps.
 *
 * <p>da-catalog is the one service that spans two schemas. The shared {@code dlap_common}
 * tables (da-protos entities) come from the raw SQL init scripts, and its own
 * {@code dlap_catalog} table comes from Flyway. This test therefore builds the database the
 * way compose does: it runs the real {@code 00} to {@code 03} scripts from
 * {@code deployment/platform/database/init-scripts} on {@code postgres:15.1}, connects as
 * {@code da_catalog_user} to the {@code dlap_common} database (the URL compose passes), lets
 * Flyway apply the catalog migrations, and lets Hibernate validate the result.
 *
 * <p>The test does not override {@code spring.jpa.hibernate.ddl-auto},
 * {@code spring.flyway.*} or the default schema: it inherits them from the module's
 * {@code application.properties}, so it proves the shipped defaults. Until DB-01 the default
 * was {@code update}, on the belief that validate was fragile for {@code User.roles}
 * ({@code text[]}) and the jsonb columns; the round trip below puts both through a real
 * PostgreSQL.
 *
 * <p>Re-run this test whenever an entity in {@code da-protos} or any init script or
 * {@code upgrades/} delta changes. The next such change is CAT-01 (asset time, size and
 * retention columns), which must keep it green.
 *
 * <p>Requires Docker.
 */
@DataJpaTest
@ContextConfiguration(classes = CommonSchemaValidateTest.JpaOnlyConfig.class)
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Testcontainers
class CommonSchemaValidateTest {

    /**
     * The database list compose passes to the init script
     * ({@code deployment/environments/dev/docker-compose.yml}, POSTGRES_MULTIPLE_DATABASES).
     * Script 01 grants to a user of every service, so each must exist for the scripts to run.
     */
    private static final String DATABASES = "dlap_common,da_discovery,da_catalog,da_policyengine,da_reporting,"
            + "da_autolabel,da_autoarchival,da_autodelete,da_autocompliance,da_admin,da_logingest";

    /** Local configuration so the JPA slice does not bootstrap Kafka, Feign, Redis or security. */
    @SpringBootConfiguration
    @EnableAutoConfiguration
    @EntityScan({"com.dlap.catalog.model", "com.dlap.common.model.entity"})
    static class JpaOnlyConfig {
    }

    @Container
    static final PostgreSQLContainer<?> POSTGRES = newPostgres();

    /** Same connection compose gives da-catalog: the shared dlap_common database, as the catalog user. */
    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> "jdbc:postgresql://" + POSTGRES.getHost() + ":"
                + POSTGRES.getMappedPort(PostgreSQLContainer.POSTGRESQL_PORT) + "/dlap_common");
        registry.add("spring.datasource.username", () -> "da_catalog_user");
        registry.add("spring.datasource.password", () -> "da_catalog_pass");
    }

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private TestEntityManager entityManager;

    @Autowired
    private EntityManagerFactory entityManagerFactory;

    @Autowired
    private Environment environment;

    /** Builds the PostgreSQL container with the real init scripts copied into the entrypoint folder. */
    private static PostgreSQLContainer<?> newPostgres() {
        PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>(DockerImageName.parse("postgres:15.1"))
                .withDatabaseName("dlap")
                .withUsername("dlap")
                .withPassword("dlap")
                .withEnv("POSTGRES_MULTIPLE_DATABASES", DATABASES);
        try (Stream<Path> scripts = Files.list(initScriptsDir())) {
            scripts.filter(Files::isRegularFile).sorted().forEach(script -> {
                String name = script.getFileName().toString();
                int mode = name.endsWith(".sh") ? 0755 : 0644;
                postgres.withCopyFileToContainer(
                        MountableFile.forHostPath(script, mode), "/docker-entrypoint-initdb.d/" + name);
            });
        } catch (IOException e) {
            throw new UncheckedIOException("cannot read the platform init scripts", e);
        }
        return postgres;
    }

    /** Walks up from the working directory to the repository's shared init-scripts folder. */
    private static Path initScriptsDir() {
        Path dir = Path.of(System.getProperty("user.dir")).toAbsolutePath();
        while (dir != null) {
            Path candidate = dir.resolve("deployment/platform/database/init-scripts");
            if (Files.isDirectory(candidate)) {
                return candidate;
            }
            dir = dir.getParent();
        }
        throw new IllegalStateException(
                "deployment/platform/database/init-scripts not found above " + System.getProperty("user.dir"));
    }

    /** The shipped default must be validate (CLAUDE.md section 6), both as configured and as applied. */
    @Test
    void ddlAutoDefaultIsValidate() {
        assertThat(environment.getProperty("spring.jpa.hibernate.ddl-auto"))
                .as("effective spring.jpa.hibernate.ddl-auto (unset JPA_DDL_AUTO to test the default)")
                .isEqualTo("validate");
        assertThat(entityManagerFactory.getProperties().get("hibernate.hbm2ddl.auto"))
                .as("hibernate.hbm2ddl.auto applied to the EntityManagerFactory")
                .isEqualTo("validate");
    }

    /**
     * Context startup already ran validate; this stops it from passing vacuously. If a mapped
     * entity dropped out of the scan, validate would check nothing for it.
     */
    @Test
    void validateCoversEverySharedAndCatalogEntity() {
        Set<String> mapped = entityManagerFactory.getMetamodel().getEntities().stream()
                .map(EntityType::getName).collect(Collectors.toSet());

        assertThat(mapped).containsExactlyInAnyOrder(
                "Asset", "AssetLabelAssignment", "CloudResource", "Label", "User", "AssetLineage");
    }

    /** Flyway ran the catalog's own migrations into dlap_catalog, in sequence. */
    @Test
    void catalogFlywayMigrationsApplied() {
        List<Integer> versions = jdbcTemplate.queryForList(
                "SELECT version FROM dlap_catalog.flyway_schema_history "
                        + "WHERE success AND version IS NOT NULL ORDER BY installed_rank",
                String.class).stream().map(Integer::valueOf).toList();

        assertThat(versions).as("applied dlap_catalog Flyway versions").startsWith(1, 2);
        for (int i = 0; i < versions.size(); i++) {
            assertThat(versions.get(i)).as("version %d is sequential", i + 1).isEqualTo(i + 1);
        }
    }

    /**
     * The init scripts loaded: default labels from 02 and the dev seed from 03. Script 03 runs
     * with ON_ERROR_STOP off by design, so a schema change that breaks it would otherwise
     * go unnoticed until the demo stack comes up empty.
     */
    @Test
    void initScriptsCreatedSchemaAndSeedData() {
        assertThat(count("dlap_common.dlap_labels")).as("default labels (02)").isPositive();
        assertThat(count("dlap_common.dlap_users")).as("seed users (03)").isPositive();
        assertThat(count("dlap_common.dlap_assets")).as("seed assets (03)").isPositive();
        assertThat(count("dlap_common.dlap_asset_label_assignments")).as("seed label assignments (03)").isPositive();
    }

    /**
     * The shared array and jsonb entities that the old comment called fragile, plus the
     * cross-schema foreign keys, written and read through the catalog's own database user.
     */
    @Test
    void sharedEntitiesRoundTripThroughPostgres() {
        User user = User.builder()
                .keycloakSubjectId("kc-schema-test-" + UUID.randomUUID())
                .email("schema-test-" + UUID.randomUUID() + "@dalab.com")
                .fullName("Schema Test")
                .roles(new String[] {"ADMIN", "DATA_ENGINEER"})
                .build();
        entityManager.persist(user);

        Asset upstream = new Asset("gs://schema-test/" + UUID.randomUUID() + "/in", "GCP", "GCS_BUCKET", "in");
        upstream.setTechnicalMetadata(Map.of("size_gb", 1.5, "storage_class", "STANDARD"));
        upstream.setDlapUsageMetrics(Map.of("reads", 3));
        Asset downstream = new Asset(
                "bq://schema-test/" + UUID.randomUUID() + "/out", "GCP", "BIGQUERY_TABLE", "out");
        entityManager.persist(upstream);
        entityManager.persist(downstream);

        CloudResource resource = CloudResource.builder()
                .assetId(upstream.getAssetId())
                .cloudProvider("GCP")
                .nativeResourceId("projects/p/buckets/in")
                .replicaRegions(List.of("us-east1", "us-west1"))
                .providerNativeJson(Map.of("versioning", "Enabled"))
                .build();
        entityManager.persist(resource);

        AssetLineage edge = new AssetLineage();
        edge.setUpstreamAsset(upstream);
        edge.setDownstreamAsset(downstream);
        edge.setRelationshipType("TRANSFORMED_FROM");
        edge.setDetailsJson(Map.of("job", "nightly-load"));
        entityManager.persist(edge);

        entityManager.flush();
        entityManager.clear();

        assertThat(entityManager.find(User.class, user.getUserId()).getRoles())
                .containsExactly("ADMIN", "DATA_ENGINEER");
        Asset loaded = entityManager.find(Asset.class, upstream.getAssetId());
        assertThat(loaded.getTechnicalMetadata()).containsEntry("storage_class", "STANDARD");
        assertThat(loaded.getDlapUsageMetrics()).containsEntry("reads", 3);
        assertThat(entityManager.find(CloudResource.class, resource.getCloudResourceId()).getReplicaRegions())
                .containsExactlyInAnyOrder("us-east1", "us-west1");
        AssetLineage loadedEdge = entityManager.find(AssetLineage.class, edge.getLineageId());
        assertThat(loadedEdge.getRelationshipType()).isEqualTo("TRANSFORMED_FROM");
        assertThat(loadedEdge.getDetailsJson()).containsEntry("job", "nightly-load");
    }

    private int count(String table) {
        Integer rows = jdbcTemplate.queryForObject("SELECT count(*) FROM " + table, Integer.class);
        return rows == null ? 0 : rows;
    }
}
