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

package com.dlap.adminservice.audit;

import java.time.Clock;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.EnableScheduling;

import com.dlap.adminservice.audit.export.CheckpointSink;
import com.dlap.adminservice.audit.export.CheckpointSinks;
import com.dlap.common.audit.AuditChainVerifier;
import com.dlap.common.audit.AuditLogReader;

/**
 * Wires the audit verifier and checkpoint signer (docs/specs/audit-log.md, section "Operating").
 *
 * <p>Without any {@code dlap.audit} setting the service starts as before: the verify API reads
 * {@code dlap_common} next to the service database, and no checkpoint is signed. With
 * {@code dlap.audit.checkpoint.enabled=true} the signing key and an export sink are required, and
 * a missing one stops the service at startup (fail closed).
 */
@Configuration
@EnableConfigurationProperties(AuditProperties.class)
public class AuditConfiguration {

    private static final Logger LOG = LoggerFactory.getLogger(AuditConfiguration.class);

    private final Clock clock = Clock.systemUTC();

    /**
     * The {@code dlap_common} pool.
     *
     * @param properties the audit settings
     * @param env        the environment, for the {@code spring.datasource.*} fallbacks
     * @return the pool, which connects lazily
     */
    @Bean(destroyMethod = "close")
    public AuditDatabase auditDatabase(AuditProperties properties, Environment env) {
        AuditProperties.Datasource ds = properties.getDatasource();
        String url = isBlank(ds.getUrl())
                ? AuditDatabase.commonUrlNextTo(env.getProperty("spring.datasource.url"))
                : ds.getUrl();
        String user = isBlank(ds.getUsername()) ? env.getProperty("spring.datasource.username", "") : ds.getUsername();
        String password = isBlank(ds.getPassword()) ? env.getProperty("spring.datasource.password", "") : ds.getPassword();
        return new AuditDatabase(url, user, password, ds.getMaximumPoolSize());
    }

    /**
     * The audit log reader.
     *
     * @param db the pool
     * @return the reader
     */
    @Bean
    public AuditLogReader auditLogReader(AuditDatabase db) {
        return new AuditLogReader(db.dataSource());
    }

    /**
     * The checkpoint table.
     *
     * @param jdbc the service database
     * @return the store
     */
    @Bean
    public CheckpointStore auditCheckpointStore(JdbcTemplate jdbc) {
        return new CheckpointStore(jdbc);
    }

    /**
     * The signing and trusted keys.
     *
     * @param properties the audit settings
     * @return the keys
     */
    @Bean
    public CheckpointKeys auditCheckpointKeys(AuditProperties properties) {
        CheckpointKeys keys = CheckpointKeys.load(properties.getSigning());
        if (properties.getCheckpoint().isEnabled() && keys.signing().isEmpty()) {
            throw new IllegalStateException("dlap.audit.checkpoint.enabled=true needs dlap.audit.signing.private-key-file"
                    + " and public-key-file");
        }
        return keys;
    }

    /**
     * The checkpoint signer.
     *
     * @param properties the audit settings
     * @param db         the pool
     * @param reader     the reader
     * @param store      the checkpoint table
     * @param keys       the keys
     * @return the service
     */
    @Bean
    public CheckpointService auditCheckpointService(AuditProperties properties, AuditDatabase db, AuditLogReader reader,
            CheckpointStore store, CheckpointKeys keys) {
        CheckpointSink sink = CheckpointSinks.create(properties.getExport(), System::getenv, clock);
        if (properties.getCheckpoint().isEnabled() && sink == null) {
            throw new IllegalStateException("dlap.audit.checkpoint.enabled=true needs dlap.audit.export.sink"
                    + " (S3, GCS, AZURE, or FILESYSTEM for development)");
        }
        if (!properties.getCheckpoint().isEnabled()) {
            LOG.warn("Audit checkpoints are off (dlap.audit.checkpoint.enabled=false): the audit log is hash-chained "
                    + "but no signed checkpoint anchors it outside the database.");
        }
        return new CheckpointService(reader, db.dataSource(), store, keys, sink, new AuditChainVerifier(), clock);
    }

    /**
     * The verifier behind the API.
     *
     * @param reader the reader
     * @param store  the checkpoint table
     * @param keys   the keys
     * @return the service
     */
    @Bean
    public AuditVerificationService auditVerificationService(AuditLogReader reader, CheckpointStore store,
            CheckpointKeys keys) {
        return new AuditVerificationService(reader, store, keys, new AuditChainVerifier(), clock);
    }

    /** Turns scheduling on, only for the checkpoint job. */
    @Configuration
    @EnableScheduling
    @ConditionalOnProperty(prefix = "dlap.audit.checkpoint", name = "enabled", havingValue = "true")
    static class CheckpointScheduling {

        /**
         * The scheduled job.
         *
         * @param service the signer
         * @return the job
         */
        @Bean
        CheckpointScheduler auditCheckpointScheduler(CheckpointService service) {
            return new CheckpointScheduler(service);
        }
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }
}
