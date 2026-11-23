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

package com.dlap.discovery.coverage;

import java.time.Clock;
import java.util.List;
import java.util.regex.Pattern;

import javax.sql.DataSource;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.KafkaTemplate;

import com.fasterxml.jackson.databind.ObjectMapper;

import io.micrometer.core.instrument.MeterRegistry;
import net.javacrumbs.shedlock.core.DefaultLockingTaskExecutor;
import net.javacrumbs.shedlock.core.LockingTaskExecutor;
import net.javacrumbs.shedlock.provider.jdbctemplate.JdbcTemplateLockProvider;

/**
 * Wires the read-coverage job (COV-01) when {@code dlap.discovery.coverage.enabled=true}. The
 * collectors are separate beans under {@code providers/<cloud>/coverage}, each behind its own
 * {@code dlap.discovery.coverage.<cloud>.enabled} switch.
 *
 * <p>The lock provider is created here and not exposed as a bean: the scheduled discovery trigger
 * registers its own {@code LockProvider} bean for {@code @SchedulerLock}, and a second bean of that
 * type would make its lookup ambiguous. Both use the same {@code shedlock} table.
 */
@Configuration
@ConditionalOnProperty(name = "dlap.discovery.coverage.enabled", havingValue = "true")
@EnableConfigurationProperties(ReadCoverageProperties.class)
public class ReadCoverageConfiguration {

    private static final Pattern SQL_IDENTIFIER = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*");

    /**
     * The observation encoder.
     *
     * @param mapper the JSON mapper
     * @return the encoder
     */
    @Bean
    public ReadCoverageEventEncoder readCoverageEventEncoder(ObjectMapper mapper) {
        return new ReadCoverageEventEncoder(mapper);
    }

    /**
     * The publisher, on the String-valued template (single encoding).
     *
     * @param template   the {@code stringKafkaTemplate} bean
     * @param encoder    the encoder
     * @param properties the settings
     * @return the publisher
     */
    @Bean
    public ReadCoveragePublisher readCoveragePublisher(
            @Qualifier("stringKafkaTemplate") KafkaTemplate<String, String> template,
            ReadCoverageEventEncoder encoder, ReadCoverageProperties properties) {
        return new ReadCoveragePublisher(template, encoder, properties.getSendTimeout());
    }

    /**
     * The job.
     *
     * @param collectors the enabled collectors
     * @param publisher  the publisher
     * @param dataSource the service datasource, for the lock table
     * @param schema     the schema of the {@code shedlock} table
     * @param properties the settings
     * @param meters     the meter registry
     * @param clock      a clock bean, or the system UTC clock
     * @return the job
     */
    @Bean
    public ReadCoverageCollectionJob readCoverageCollectionJob(List<ReadCoverageCollector> collectors,
            ReadCoveragePublisher publisher, DataSource dataSource,
            @Value("${spring.jpa.properties.hibernate.default_schema:dlap_discovery}") String schema,
            ReadCoverageProperties properties, MeterRegistry meters, ObjectProvider<Clock> clock) {
        return new ReadCoverageCollectionJob(collectors, publisher, lockExecutor(dataSource, schema), properties,
                meters, clock.getIfUnique(Clock::systemUTC));
    }

    /**
     * A locking executor on the {@code shedlock} table, on database time.
     *
     * @param dataSource the datasource
     * @param schema     the schema of the table
     * @return the executor
     */
    static LockingTaskExecutor lockExecutor(DataSource dataSource, String schema) {
        if (schema == null || !SQL_IDENTIFIER.matcher(schema).matches()) {
            throw new IllegalArgumentException("Invalid schema name for the ShedLock table: " + schema);
        }
        return new DefaultLockingTaskExecutor(new JdbcTemplateLockProvider(
                JdbcTemplateLockProvider.Configuration.builder()
                        .withJdbcTemplate(new JdbcTemplate(dataSource))
                        .withTableName(schema + ".shedlock")
                        .usingDbTime()
                        .build()));
    }
}
