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

package com.dlap.reporting.identity;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Applies the identity map seed file ({@value #SEED_FILE_PROPERTY}, env
 * {@code DLAP_REPORTING_IDENTITY_MAP_SEED_FILE}) at start-up.
 *
 * <p>The file owns every key it names: those entries are inserted or rewritten with source
 * {@link PrincipalIdentityMapping.Source#SEED}, and earlier {@code SEED} rows the file no longer
 * names are removed. {@code MANUAL} rows (written with SQL) with other keys are left alone. The
 * sync is one transaction; when another replica seeds at the same moment and the insert hits the
 * unique key, it runs once more over the rows that replica committed.
 *
 * <p>With the property unset nothing happens. With it set, a missing or invalid file fails the
 * start: a map that silently maps nobody would turn every read into an unenforced-read finding.
 */
@Component
public class IdentityMapSeeder implements ApplicationRunner {

    /** The property naming the seed file. Empty (the default) disables seeding. */
    public static final String SEED_FILE_PROPERTY = "dlap.reporting.identity-map.seed-file";

    private static final Logger log = LoggerFactory.getLogger(IdentityMapSeeder.class);

    /** The counts of one sync. */
    record SyncResult(int inserted, int updated, int removed, int unchanged) {
    }

    private final PrincipalIdentityMapRepository repository;
    private final TransactionTemplate transactions;
    private final Clock clock;
    private final String seedFile;

    /**
     * Creates the seeder.
     *
     * @param repository         the map store
     * @param transactionManager the transaction manager of the store
     * @param clocks             the clock for the row times; the system UTC clock when none is defined
     * @param seedFile           the seed file path, or empty
     */
    public IdentityMapSeeder(PrincipalIdentityMapRepository repository,
                             PlatformTransactionManager transactionManager,
                             ObjectProvider<Clock> clocks,
                             @Value("${" + SEED_FILE_PROPERTY + ":}") String seedFile) {
        this.repository = repository;
        this.transactions = new TransactionTemplate(transactionManager);
        this.clock = clocks.getIfAvailable(Clock::systemUTC);
        this.seedFile = seedFile == null ? "" : seedFile.strip();
    }

    /**
     * Seeds the map when a file is configured.
     *
     * @param args the application arguments (unused)
     */
    @Override
    public void run(ApplicationArguments args) {
        if (seedFile.isEmpty()) {
            log.info("Identity map seeding disabled ({} is not set)", SEED_FILE_PROPERTY);
            return;
        }
        List<IdentityMapSeedFile.Entry> entries = IdentityMapSeedFile.parse(read(Path.of(seedFile)));
        SyncResult result;
        try {
            result = transactions.execute(status -> sync(entries));
        } catch (DataIntegrityViolationException e) {
            log.info("Identity map seed collided with a concurrent writer; applying it once more");
            result = transactions.execute(status -> sync(entries));
        }
        SyncResult r = Objects.requireNonNull(result);
        log.info("Identity map seeded from {}: {} entries ({} inserted, {} updated, {} removed, {} unchanged)",
                seedFile, entries.size(), r.inserted(), r.updated(), r.removed(), r.unchanged());
    }

    /**
     * Makes the stored map match the entries. Package-private for the tests.
     *
     * @param entries the parsed seed file
     * @return the counts
     */
    SyncResult sync(List<IdentityMapSeedFile.Entry> entries) {
        Instant now = clock.instant();
        Map<String, PrincipalIdentityMapping> stored = new HashMap<>();
        for (PrincipalIdentityMapping row : repository.findAll()) {
            stored.put(id(row.getProvider(), row.getPrincipalKey()), row);
        }
        Set<String> named = new HashSet<>();
        int inserted = 0;
        int updated = 0;
        int unchanged = 0;
        for (IdentityMapSeedFile.Entry entry : entries) {
            String id = id(entry.provider(), entry.principalKey());
            named.add(id);
            PrincipalIdentityMapping row = stored.get(id);
            if (row == null) {
                row = new PrincipalIdentityMapping();
                row.setProvider(entry.provider());
                row.setPrincipalKey(entry.principalKey());
                row.setSubjectId(entry.subjectId());
                row.setSource(PrincipalIdentityMapping.Source.SEED);
                row.setCreatedAt(now);
                row.setUpdatedAt(now);
                repository.save(row);
                inserted++;
            } else if (!entry.subjectId().equals(row.getSubjectId())
                    || row.getSource() != PrincipalIdentityMapping.Source.SEED) {
                row.setSubjectId(entry.subjectId());
                row.setSource(PrincipalIdentityMapping.Source.SEED);
                row.setUpdatedAt(now);
                repository.save(row);
                updated++;
            } else {
                unchanged++;
            }
        }
        int removed = 0;
        for (Map.Entry<String, PrincipalIdentityMapping> e : stored.entrySet()) {
            if (e.getValue().getSource() == PrincipalIdentityMapping.Source.SEED && !named.contains(e.getKey())) {
                repository.delete(e.getValue());
                removed++;
            }
        }
        repository.flush();
        return new SyncResult(inserted, updated, removed, unchanged);
    }

    private static String id(IdentityProviderScope provider, String key) {
        return provider + "\n" + key;
    }

    private static String read(Path path) {
        try {
            return Files.readString(path, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot read the identity map seed file " + path
                    + " (" + SEED_FILE_PROPERTY + ")", e);
        }
    }
}
