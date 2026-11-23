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

package com.dlap.logingest.config.sources;

import com.dlap.logingest.model.BindingState;
import com.dlap.logingest.model.ConfigOrigin;
import com.dlap.logingest.model.SourceBindingEntity;
import com.dlap.logingest.repository.SourceBindingRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Mirrors the effective configuration into {@code dlap_logingest.source_binding} at startup
 * (design B2 "Where the config lives"): one row per binding with its effective configuration, its
 * SHA-256 hash, the deployment hash and its state. Cursors, observations, quarantine records and
 * backfill jobs reference these rows.
 *
 * <p>The file stays authoritative. A {@code FILE} or {@code BUILTIN} row that is no longer
 * configured becomes {@code REMOVED} (never deleted, because other tables reference it);
 * {@code MIGRATED} rows from Flyway V4 are left alone. A binding that is configured again later is
 * revived with its original {@code first_synced_at}.
 */
@Component
public class LogSourceConfigSynchronizer implements SmartInitializingSingleton {

    private static final Logger log = LoggerFactory.getLogger(LogSourceConfigSynchronizer.class);

    private final EffectiveLogSources sources;
    private final SourceBindingRepository repository;
    private final TransactionTemplate tx;
    private final Clock clock;

    /**
     * What one synchronization changed.
     *
     * @param created bindings seen for the first time
     * @param updated bindings rewritten
     * @param removed bindings no longer configured
     */
    public record Result(int created, int updated, int removed) {
    }

    /**
     * Creates the synchronizer.
     *
     * @param sources    the effective configuration
     * @param repository the mirror table
     * @param txManager  the transaction manager
     * @param clock      the application clock when unique, else the UTC system clock
     */
    public LogSourceConfigSynchronizer(EffectiveLogSources sources, SourceBindingRepository repository,
                                       PlatformTransactionManager txManager, ObjectProvider<Clock> clock) {
        this.sources = sources;
        this.repository = repository;
        this.tx = new TransactionTemplate(txManager);
        this.clock = clock.getIfUnique(Clock::systemUTC);
    }

    @Override
    public void afterSingletonsInstantiated() {
        Result r = sync(sources);
        log.info("Log sources mirrored to source_binding: {} created, {} updated, {} removed (configHash={})",
                r.created(), r.updated(), r.removed(), sources.configHash());
    }

    /**
     * Writes the mirror in one transaction.
     *
     * @param effective the configuration to mirror
     * @return what changed
     */
    public Result sync(EffectiveLogSources effective) {
        return tx.execute(status -> {
            Instant now = clock.instant();
            int created = 0;
            int updated = 0;
            Set<String> present = new HashSet<>();
            for (EffectiveBinding b : effective.bindings()) {
                present.add(b.id());
                SourceBindingEntity row = repository.findById(b.id()).orElse(null);
                if (row == null) {
                    row = new SourceBindingEntity();
                    row.setBindingId(b.id());
                    row.setFirstSyncedAt(now);
                    created++;
                } else {
                    updated++;
                }
                row.setSourceId(b.sourceId());
                row.setSourceKind(b.sourceKind());
                row.setConfigOrigin(b.origin());
                row.setState(b.state(effective.pipeline()));
                row.setEnabled(b.enabled());
                row.setScope(new LinkedHashMap<>(b.scope()));
                row.setEffectiveConfig(new LinkedHashMap<>(b.canonical()));
                row.setConfigHash(b.configHash());
                row.setDeploymentConfigHash(effective.configHash());
                row.setLastSyncedAt(now);
                row.setRemovedAt(null);
                repository.save(row);
            }
            int removed = 0;
            for (SourceBindingEntity row : repository.findByConfigOriginIn(
                    EnumSet.of(ConfigOrigin.FILE, ConfigOrigin.BUILTIN))) {
                if (!present.contains(row.getBindingId()) && row.getState() != BindingState.REMOVED) {
                    row.setState(BindingState.REMOVED);
                    row.setRemovedAt(now);
                    row.setLastSyncedAt(now);
                    row.setDeploymentConfigHash(effective.configHash());
                    repository.save(row);
                    removed++;
                }
            }
            return new Result(created, updated, removed);
        });
    }
}
