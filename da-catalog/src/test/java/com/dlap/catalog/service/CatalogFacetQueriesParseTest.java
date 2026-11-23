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

package com.dlap.catalog.service;

import static org.assertj.core.api.Assertions.assertThat;

import org.hibernate.SessionFactory;
import org.hibernate.boot.MetadataSources;
import org.hibernate.boot.registry.StandardServiceRegistry;
import org.hibernate.boot.registry.StandardServiceRegistryBuilder;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import com.dlap.common.model.entity.Asset;
import com.dlap.common.model.entity.AssetLabelAssignment;
import com.dlap.common.model.entity.Label;
import com.dlap.common.model.entity.User;

import jakarta.persistence.EntityManager;

/**
 * UI-01: compiles the catalog-filter facet queries against the real entity model. Hibernate
 * parses and type-checks HQL when the query is created, so a wrong attribute path or a bad
 * GROUP BY fails here without a database or Docker. Execution is covered by the mocked-count
 * tests in {@link AssetServiceStoredDataTest}.
 */
class CatalogFacetQueriesParseTest {

    private static StandardServiceRegistry registry;
    private static SessionFactory sessionFactory;

    @BeforeAll
    static void bootHibernateWithoutADatabase() {
        registry = new StandardServiceRegistryBuilder()
                .applySetting("hibernate.dialect", "org.hibernate.dialect.PostgreSQLDialect")
                .applySetting("hibernate.boot.allow_jdbc_metadata_access", "false")
                .applySetting("hibernate.hbm2ddl.auto", "none")
                .build();
        sessionFactory = new MetadataSources(registry)
                .addAnnotatedClass(Asset.class)
                .addAnnotatedClass(Label.class)
                .addAnnotatedClass(AssetLabelAssignment.class)
                .addAnnotatedClass(User.class)
                .buildMetadata()
                .buildSessionFactory();
    }

    @AfterAll
    static void shutDown() {
        if (sessionFactory != null) {
            sessionFactory.close();
        }
        if (registry != null) {
            StandardServiceRegistryBuilder.destroy(registry);
        }
    }

    private void assertParses(String jpql) {
        try (EntityManager em = sessionFactory.createEntityManager()) {
            assertThat(em.createQuery(jpql, Object[].class)).isNotNull();
        }
    }

    @Test
    void assetTypeFacetQueryParses() {
        assertParses(AssetService.FACET_ASSET_TYPE_JPQL);
    }

    @Test
    void cloudProviderFacetQueryParses() {
        assertParses(AssetService.FACET_CLOUD_PROVIDER_JPQL);
    }

    @Test
    void labelFacetQueryParses() {
        assertParses(AssetService.FACET_LABEL_JPQL);
    }
}
