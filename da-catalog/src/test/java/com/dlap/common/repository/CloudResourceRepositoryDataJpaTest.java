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

package com.dlap.common.repository;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.hibernate.SessionFactory;
import org.hibernate.cfg.Configuration;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import com.dlap.common.model.entity.CloudResource;

/**
 * N4 / ADR-0028: verifies the shared {@link CloudResource} entity is
 * Hibernate-mappable (jsonb columns, hierarchy fields, timestamps) and
 * round-trips through a real Postgres engine (Testcontainers).
 *
 * <p>Uses a bare {@link SessionFactory} registering only
 * {@code CloudResource} (no Spring context), so the test stays isolated
 * from sibling entities/repositories. Hibernate creates the
 * {@code dlap_common} schema ({@code create_namespaces=true}) and the
 * table from the entity, exercising the same DDL shipped in
 * {@code 02-create-common-schema.sql}.
 */
@Testcontainers
class CloudResourceRepositoryDataJpaTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:15-alpine");

    private static SessionFactory sessionFactory;

    @BeforeAll
    static void bootstrap() {
        Configuration cfg = new Configuration();
        cfg.setProperty("hibernate.connection.url", POSTGRES.getJdbcUrl());
        cfg.setProperty("hibernate.connection.username", POSTGRES.getUsername());
        cfg.setProperty("hibernate.connection.password", POSTGRES.getPassword());
        cfg.setProperty("hibernate.dialect", "org.hibernate.dialect.PostgreSQLDialect");
        cfg.setProperty("hibernate.hbm2ddl.auto", "create");
        cfg.setProperty("hibernate.default_schema", "dlap_common");
        cfg.setProperty("hibernate.hbm2ddl.create_namespaces", "true");
        cfg.addAnnotatedClass(CloudResource.class);
        sessionFactory = cfg.buildSessionFactory();
    }

    @AfterAll
    static void shutdown() {
        if (sessionFactory != null) {
            sessionFactory.close();
        }
    }

    @Test
    void roundTrip_persistsRicherFieldsThroughPostgres() {
        UUID parentId = sessionFactory.fromTransaction(session -> {
            CloudResource parent = CloudResource.builder()
                    .cloudProvider("AWS")
                    .serviceId("s3")
                    .resourceType("s3_bucket")
                    .nativeResourceId("arn:aws:s3:::dlap-demo")
                    .name("dlap-demo")
                    .accountId("123456789012")
                    .region("us-east-1")
                    .hasChildren(true)
                    .replicaRegions(List.of("us-west-2", "eu-west-1"))
                    .tags(Map.of("env", "prod"))
                    .providerNativeJson(Map.of("versioning", "Enabled"))
                    .build();
            session.persist(parent);

            CloudResource child = CloudResource.builder()
                    .cloudProvider("AWS")
                    .serviceId("s3")
                    .resourceType("s3_prefix")
                    .nativeResourceId("arn:aws:s3:::dlap-demo/logs/")
                    .parentResourceId(parent.getCloudResourceId())
                    .accountId("123456789012")
                    .region("us-east-1")
                    .build();
            session.persist(child);

            // @PrePersist populated id + timestamps
            assertThat(parent.getCloudResourceId()).isNotNull();
            assertThat(parent.getCreatedTs()).isNotNull();
            return parent.getCloudResourceId();
        });

        sessionFactory.inTransaction(session -> {
            // jsonb columns round-trip
            CloudResource loaded = session.createQuery(
                            "from CloudResource where nativeResourceId = :n", CloudResource.class)
                    .setParameter("n", "arn:aws:s3:::dlap-demo")
                    .getSingleResult();
            assertThat(loaded.getReplicaRegions()).containsExactlyInAnyOrder("us-west-2", "eu-west-1");
            assertThat(loaded.getTags()).containsEntry("env", "prod");
            assertThat(loaded.getProviderNativeJson()).containsEntry("versioning", "Enabled");
            assertThat(loaded.getHasChildren()).isTrue();

            // hierarchy query
            List<CloudResource> children = session.createQuery(
                            "from CloudResource where parentResourceId = :p", CloudResource.class)
                    .setParameter("p", parentId)
                    .getResultList();
            assertThat(children).hasSize(1);
            assertThat(children.get(0).getParentResourceId()).isEqualTo(parentId);

            Long count = session.createQuery(
                            "select count(*) from CloudResource where accountId = :a", Long.class)
                    .setParameter("a", "123456789012")
                    .getSingleResult();
            assertThat(count).isEqualTo(2L);
        });
    }
}
