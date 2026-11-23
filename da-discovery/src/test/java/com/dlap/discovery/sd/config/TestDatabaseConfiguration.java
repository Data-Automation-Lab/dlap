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

package com.dlap.discovery.sd.config;

import java.util.HashMap;
import java.util.Map;
import javax.sql.DataSource;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.jpa.EntityManagerFactoryBuilder;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.orm.jpa.LocalContainerEntityManagerFactoryBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;

/**
 * Provides the EntityManagerFactory and TransactionManager for tests,
 * relying on application-test.yml for DataSource and most JPA properties.
 */
@TestConfiguration
@EnableJpaRepositories(
    basePackages = {
        "com.dlap.discovery.common.model.repository",
        "com.dlap.discovery.sd.domain.repository",
        "com.dlap.discovery.catalog.repository",
        "com.dlap.discovery.config.repository",
        "com.dlap.discovery.config.model",
    },
    entityManagerFactoryRef = "entityManagerFactory",
    transactionManagerRef = "transactionManager"
)
@EnableTransactionManagement
public class TestDatabaseConfiguration {

    @SuppressWarnings("rawtypes")
    @Bean(name = "entityManagerFactory")
    @Primary
    public LocalContainerEntityManagerFactoryBean entityManagerFactory(
        EntityManagerFactoryBuilder builder,
        @Qualifier("dataSource") DataSource dataSource
    ) {
        Map<String, Object> jpaProperties = new HashMap<>();
        // Explicitly set H2 dialect for this EntityManagerFactory
        jpaProperties.put("hibernate.dialect", "org.hibernate.dialect.H2Dialect");
        // ddl-auto should be picked from application-test.yml (create-drop)
        // Other properties like show-sql, format_sql also from application-test.yml

        return builder
            .dataSource(dataSource)
            .packages(
                "com.dlap.discovery.common.model.entity",
                "com.dlap.discovery.common.model",
                "com.dlap.discovery.catalog.model",
                "com.dlap.discovery.config.model",
                "com.dlap.discovery.sd.model",
                "com.dlap.discovery.sd.repository",
                "com.dlap.discovery.crawler.model",
                "com.dlap.discovery.domain.model",
                "com.dlap.discovery.log.service.gcp.persistence.entity"
            )
            .persistenceUnit("testPU")
            .properties(jpaProperties) // Set explicit properties
            .build();
    }

    @Bean(name = "transactionManager")
    @Primary
    public PlatformTransactionManager transactionManager(
        @Qualifier("entityManagerFactory") LocalContainerEntityManagerFactoryBean entityManagerFactory
    ) {
        return new JpaTransactionManager(entityManagerFactory.getObject());
    }
}
