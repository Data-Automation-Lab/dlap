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

package com.dlap.autocompliance.signal;

import java.util.List;
import java.util.regex.Pattern;

import javax.sql.DataSource;

import net.javacrumbs.shedlock.core.LockProvider;
import net.javacrumbs.shedlock.provider.jdbctemplate.JdbcTemplateLockProvider;
import net.javacrumbs.shedlock.spring.annotation.EnableSchedulerLock;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/**
 * Composes the compliance-via-labels pattern (ADR-0035): pulls
 * {@link ComplianceSignal}s from the active {@link ComplianceSignalProvider}, maps
 * each to a canonical label via {@link ComplianceLabelMapper}, and applies it
 * through the {@link ComplianceLabelSink}. Policies then enforce lifecycle actions
 * on those labels.
 *
 * <p>Disabled by default: created only when
 * {@code dlap.compliance.signal-sync.enabled=true}, so the built-in findings
 * roll-up stays the zero-config default. When enabled it runs on a fixed delay
 * ({@code dlap.compliance.signal-sync.interval-ms}, default 15 min) and can also be
 * invoked directly.
 *
 * <p>The scheduled run is a cluster-wide singleton (plan rule R7, HA-01). Every replica fires its
 * own timer; {@link SchedulerLock} takes the {@value #LOCK_NAME} row of the {@code shedlock} table
 * (Flyway V5) on the database clock, so one replica runs a given interval and the others skip it.
 * {@code lockAtLeastFor} defaults to the interval itself: a second replica whose timer fires part
 * way through the interval is skipped, so the cluster syncs about once per interval and not once
 * per replica. {@code lockAtMostFor} bounds a crashed run; it defaults to 30 minutes, which is
 * above twice the expected run (one HTTP fetch with a 5 s connect and 15 s read timeout, then one
 * Kafka publish per signal). A direct call of {@link #sync()} takes no lock. The first run starts
 * after {@code dlap.compliance.signal-sync.initial-delay} (ISO-8601, default none).
 */
@Service
@ConditionalOnProperty(name = "dlap.compliance.signal-sync.enabled", havingValue = "true")
@EnableScheduling
public class ComplianceSignalSyncService {

    /** Name of this job's row in the {@code shedlock} table (the column holds 64 characters). */
    public static final String LOCK_NAME = "autocompliance-signal-sync";

    private static final Logger log = LoggerFactory.getLogger(ComplianceSignalSyncService.class);

    private static final Pattern SQL_IDENTIFIER = Pattern.compile("[a-z_][a-z0-9_]{0,62}");

    private final ComplianceSignalProvider provider;
    private final ComplianceLabelMapper mapper;
    private final ComplianceLabelSink sink;

    public ComplianceSignalSyncService(ComplianceSignalProvider provider,
                                       ComplianceLabelMapper mapper,
                                       ComplianceLabelSink sink) {
        this.provider = provider;
        this.mapper = mapper;
        this.sink = sink;
    }

    /**
     * Scheduled entry point. Runs under the cluster-wide lock, so with several replicas only one of
     * them syncs a given interval.
     */
    @Scheduled(fixedDelayString = "${dlap.compliance.signal-sync.interval-ms:900000}",
            initialDelayString = "${dlap.compliance.signal-sync.initial-delay:PT0S}")
    @SchedulerLock(name = LOCK_NAME,
            lockAtMostFor = "${dlap.compliance.signal-sync.lock-at-most-for:PT30M}",
            lockAtLeastFor = "${dlap.compliance.signal-sync.lock-at-least-for:${dlap.compliance.signal-sync.interval-ms:900000}}")
    public void scheduledSync() {
        sync();
    }

    /**
     * Fetch, map, and apply one round of compliance signals.
     *
     * @return the number of labels applied.
     */
    public int sync() {
        if (!provider.isEnabled()) {
            log.debug("Compliance signal sync enabled but the active provider reports disabled; skipping.");
            return 0;
        }
        List<ComplianceSignal> signals = provider.fetchSignals();
        if (signals == null || signals.isEmpty()) {
            return 0;
        }
        int applied = 0;
        for (ComplianceSignal signal : signals) {
            String label = mapper.toLabelName(signal);
            if (label == null || signal.assetUri() == null || signal.assetUri().isBlank()) {
                continue;
            }
            try {
                sink.applyLabel(signal.assetUri(), label, signal.detail());
                applied++;
            } catch (Exception e) {
                log.warn("Failed to apply compliance label '{}' to {}: {}", label, signal.assetUri(), e.getMessage());
            }
        }
        log.info("Compliance signal sync: applied {} label(s) from {} signal(s).", applied, signals.size());
        return applied;
    }

    /**
     * Builds the JDBC lock provider used by {@link SchedulerLock}.
     *
     * <p>Uses the database clock ({@code usingDbTime()}) so replica clock skew cannot shorten or
     * extend a lock, and addresses the table by schema because the lock provider issues plain JDBC
     * rather than going through Hibernate's default schema.
     *
     * @param dataSource the service datasource
     * @param schema     schema that holds the {@code shedlock} table (Flyway V5)
     * @return the lock provider
     * @throws IllegalArgumentException if {@code schema} is not a plain SQL identifier
     */
    static LockProvider createLockProvider(DataSource dataSource, String schema) {
        if (schema == null || !SQL_IDENTIFIER.matcher(schema).matches()) {
            throw new IllegalArgumentException("Invalid schema name for the ShedLock table: " + schema);
        }
        return new JdbcTemplateLockProvider(JdbcTemplateLockProvider.Configuration.builder()
                .withJdbcTemplate(new JdbcTemplate(dataSource))
                .withTableName(schema + ".shedlock")
                .usingDbTime()
                .build());
    }

    /** Enables {@link SchedulerLock} and supplies the lock provider, only when the sync exists. */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnProperty(name = "dlap.compliance.signal-sync.enabled", havingValue = "true")
    @EnableSchedulerLock(defaultLockAtMostFor = "PT30M")
    static class ShedLockConfiguration {

        @Bean
        LockProvider autocomplianceLockProvider(DataSource dataSource,
                @Value("${spring.jpa.properties.hibernate.default_schema:dlap_autocompliance}") String schema) {
            return createLockProvider(dataSource, schema);
        }
    }
}
