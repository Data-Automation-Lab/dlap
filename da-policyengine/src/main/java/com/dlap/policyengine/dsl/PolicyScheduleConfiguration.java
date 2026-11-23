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

package com.dlap.policyengine.dsl;

import java.util.regex.Pattern;

import javax.sql.DataSource;

import net.javacrumbs.shedlock.core.LockProvider;
import net.javacrumbs.shedlock.provider.jdbctemplate.JdbcTemplateLockProvider;
import net.javacrumbs.shedlock.spring.annotation.EnableSchedulerLock;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Wiring of the scheduled sweep (POL-01): the ShedLock JDBC lock provider for the singleton job
 * (plan rule R7; the lock table is in Flyway V6) and the {@code dlap.policyengine.schedule.*}
 * settings. The provider uses the database's clock ({@code usingDbTime()}), so clock skew between
 * replicas cannot shorten or extend a lock.
 */
@Configuration(proxyBeanMethods = false)
@EnableSchedulerLock(defaultLockAtMostFor = "PT1H")
@EnableConfigurationProperties(PolicyScheduleProperties.class)
public class PolicyScheduleConfiguration {

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
    @ConditionalOnMissingBean(LockProvider.class)
    LockProvider policyEngineLockProvider(DataSource dataSource,
            @Value("${spring.jpa.properties.hibernate.default_schema:dlap_policyengine}") String schema) {
        return new JdbcTemplateLockProvider(JdbcTemplateLockProvider.Configuration.builder()
                .withJdbcTemplate(new JdbcTemplate(dataSource))
                .withTableName(qualified(schema, "shedlock"))
                .usingDbTime()
                .build());
    }

    /**
     * A schema-qualified table name, after checking the schema is a plain SQL identifier.
     *
     * @param schema the schema
     * @param table  the table
     * @return {@code schema.table}
     */
    static String qualified(String schema, String table) {
        if (schema == null || !SQL_IDENTIFIER.matcher(schema).matches()) {
            throw new IllegalArgumentException("Invalid schema name for the policy-engine tables: " + schema);
        }
        return schema + "." + table;
    }
}
