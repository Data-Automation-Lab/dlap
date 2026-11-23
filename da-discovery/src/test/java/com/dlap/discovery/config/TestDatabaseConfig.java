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

package com.dlap.discovery.config;

import java.util.Properties;
import javax.sql.DataSource;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.orm.jpa.LocalContainerEntityManagerFactoryBean;
import org.springframework.orm.jpa.vendor.HibernateJpaVendorAdapter;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;

/**
 * Database configuration for test profile.
 * Enables JPA repositories and entity scanning for integration tests.
 * Disables schema validation to avoid JSONB vs JSON type conflicts with H2.
 */
@Configuration
@Profile("test")
@EnableJpaRepositories(
    basePackages = {
        "com.dlap.discovery.catalog.model.repository",
        "com.dlap.discovery.common.model.repository",
        "com.dlap.discovery.crawler.model.repository",
        "com.dlap.discovery.log.service.gcp.persistence.repository",
    }
)
@EntityScan(
    basePackages = {
        "com.dlap.discovery.catalog.model",
        "com.dlap.discovery.common.model",
        "com.dlap.discovery.crawler.model",
        "com.dlap.discovery.log.service.gcp.persistence.entity",
    }
)
@EnableTransactionManagement
public class TestDatabaseConfig {

    /**
     * Creates a test-specific entity manager factory with disabled schema validation.
     * This avoids JSONB vs JSON type conflicts when using H2 for testing.
     */
    @Bean
    public LocalContainerEntityManagerFactoryBean testEntityManagerFactory(DataSource dataSource) {
        LocalContainerEntityManagerFactoryBean em = new LocalContainerEntityManagerFactoryBean();
        em.setDataSource(dataSource);
        em.setPackagesToScan(
            "com.dlap.discovery.catalog.model",
            "com.dlap.discovery.common.model",
            "com.dlap.discovery.crawler.model",
            "com.dlap.discovery.log.service.gcp.persistence.entity"
        );

        HibernateJpaVendorAdapter vendorAdapter = new HibernateJpaVendorAdapter();
        vendorAdapter.setGenerateDdl(false); // We use Liquibase for DDL
        em.setJpaVendorAdapter(vendorAdapter);

        // Configure JPA properties for testing
        Properties properties = new Properties();

        // Use H2 dialect
        properties.setProperty("hibernate.dialect", "org.hibernate.dialect.H2Dialect");

        // CRITICAL: Disable schema validation to avoid JSONB vs JSON conflicts
        properties.setProperty("hibernate.hbm2ddl.auto", "none");
        properties.setProperty("jakarta.persistence.schema-generation.database.action", "none");
        properties.setProperty("hibernate.temp.use_jdbc_metadata_defaults", "false");

        // Disable SQL logging for cleaner test output
        properties.setProperty("hibernate.show_sql", "false");
        properties.setProperty("hibernate.format_sql", "false");

        // Set timezone
        properties.setProperty("hibernate.jdbc.time_zone", "UTC");

        em.setJpaProperties(properties);
        return em;
    }

    /**
     * Creates the transaction manager for tests.
     */
    @Bean
    public PlatformTransactionManager testTransactionManager(LocalContainerEntityManagerFactoryBean testEntityManagerFactory) {
        JpaTransactionManager transactionManager = new JpaTransactionManager();
        transactionManager.setEntityManagerFactory(testEntityManagerFactory.getObject());
        return transactionManager;
    }
}
