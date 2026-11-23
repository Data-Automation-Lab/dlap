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

package com.dlap.catalog.retention;

import java.util.regex.Pattern;

import javax.sql.DataSource;

import net.javacrumbs.shedlock.core.LockProvider;
import net.javacrumbs.shedlock.provider.jdbctemplate.JdbcTemplateLockProvider;
import net.javacrumbs.shedlock.spring.annotation.EnableSchedulerLock;

import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Wiring of the retention machinery: the ShedLock JDBC lock provider for the singleton jobs (plan
 * rule R7; the lock table is in Flyway V4) and a fail-fast check of
 * {@code dlap.catalog.retention.*}.
 *
 * <p>The lock provider uses the database's clock ({@code usingDbTime()}), so clock skew between
 * replicas cannot shorten or extend a lock. {@code defaultLockAtMostFor} is only the fallback for
 * a lock without its own bound; both jobs name theirs.
 */
@Configuration(proxyBeanMethods = false)
@EnableSchedulerLock(defaultLockAtMostFor = "PT30M")
public class RetentionLockConfiguration {

    private static final Pattern SQL_IDENTIFIER = Pattern.compile("[a-z_][a-z0-9_]{0,62}");

    /**
     * The lock provider: database time, table addressed by schema because the provider issues
     * plain JDBC.
     *
     * @param dataSource the service data source
     * @param schema     the service schema
     * @return the provider
     */
    @Bean
    LockProvider catalogLockProvider(DataSource dataSource,
            @Value("${spring.jpa.properties.hibernate.default_schema:dlap_catalog}") String schema) {
        if (schema == null || !SQL_IDENTIFIER.matcher(schema).matches()) {
            throw new IllegalArgumentException("Invalid schema name for the ShedLock table: " + schema);
        }
        return new JdbcTemplateLockProvider(JdbcTemplateLockProvider.Configuration.builder()
                .withJdbcTemplate(new JdbcTemplate(dataSource))
                .withTableName(schema + ".shedlock")
                .usingDbTime()
                .build());
    }

    /**
     * Rejects invalid retention settings at startup.
     *
     * @param properties the settings
     * @return the check, run when the context starts
     */
    @Bean
    InitializingBean retentionPropertiesCheck(RetentionProperties properties) {
        return properties::validate;
    }
}
