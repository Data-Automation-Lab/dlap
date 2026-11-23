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

package com.dlap.autodelete.retire;

import com.dlap.autodelete.domain.SoftDeleteRecordEntity;
import com.dlap.autodelete.repository.DeletionTaskRepository;
import com.dlap.autodelete.repository.SoftDeleteRecordRepository;
import java.time.Clock;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The retire sweeper: one job, a cluster-wide singleton, that moves due soft-delete rows and
 * pending task approvals forward (RET-02, Q-548).
 *
 * <p>Two locks, so two replicas never act twice:
 * <ol>
 *   <li><b>ShedLock</b> ({@value #LOCK_NAME}, database time, Flyway V5 table): one replica runs a
 *       sweep at a time (plan rule R7).</li>
 *   <li><b>{@code SELECT ... FOR UPDATE SKIP LOCKED}</b>: each row is claimed and handled in its own
 *       transaction that holds the row lock, so a replica whose ShedLock lease ran out, or a restore
 *       that races the sweep, never acts on a row another transaction holds.</li>
 * </ol>
 * A row that cannot be handled (an unexpected error, an audit append that failed) is rolled back
 * and ends the sweep; the next sweep tries it again.
 */
@Component
public class RetireSweeper {

    private static final Logger LOG = LoggerFactory.getLogger(RetireSweeper.class);

    /** The ShedLock name. */
    public static final String LOCK_NAME = "autodelete-retire-sweep";

    private final SoftDeleteRecordRepository records;
    private final DeletionTaskRepository tasks;
    private final HardDeleteExecutor executor;
    private final TaskApprovalProcessor taskProcessor;
    private final RetireProperties properties;
    private final Clock clock;
    private final TransactionTemplate tx;

    /**
     * Creates the sweeper.
     *
     * @param records       the soft-delete rows
     * @param tasks         the tasks
     * @param executor      the hard-delete executor
     * @param taskProcessor the task approval processor
     * @param properties    the settings
     * @param clock         the clock
     * @param txManager     the transaction manager
     */
    public RetireSweeper(SoftDeleteRecordRepository records, DeletionTaskRepository tasks, HardDeleteExecutor executor,
            TaskApprovalProcessor taskProcessor, RetireProperties properties, Clock clock,
            PlatformTransactionManager txManager) {
        this.records = records;
        this.tasks = tasks;
        this.executor = executor;
        this.taskProcessor = taskProcessor;
        this.properties = properties;
        this.clock = clock;
        this.tx = new TransactionTemplate(txManager);
    }

    /**
     * The scheduled sweep: every {@code dlap.autodelete.sweep.interval} (default one minute), on one
     * replica at a time.
     */
    @Scheduled(fixedDelayString = "${dlap.autodelete.sweep.interval:PT1M}",
            initialDelayString = "${dlap.autodelete.sweep.initial-delay:PT1M}")
    @SchedulerLock(name = LOCK_NAME,
            lockAtMostFor = "${dlap.autodelete.sweep.lock-at-most-for:PT10M}",
            lockAtLeastFor = "PT5S")
    public void scheduledSweep() {
        sweep();
    }

    /**
     * One sweep: due soft-delete rows first, then pending task approvals, each at most
     * {@code dlap.autodelete.sweep.batch-size}.
     *
     * @return the number of soft-delete rows and tasks handled
     */
    public int sweep() {
        int handled = 0;
        for (int i = 0; i < properties.sweep().batchSize(); i++) {
            AtomicReference<UUID> claimed = new AtomicReference<>();
            Optional<UUID> done;
            try {
                done = tx.execute(status -> {
                    Optional<SoftDeleteRecordEntity> row = records.claimNextDue(clock.instant());
                    if (row.isEmpty()) {
                        return Optional.<UUID>empty();
                    }
                    claimed.set(row.get().getId());
                    executor.handle(row.get());
                    return Optional.of(row.get().getId());
                });
            } catch (RuntimeException e) {
                LOG.error("Retire sweep: record {} failed and was rolled back; it is retried after {}: {}",
                        claimed.get(), properties.sweep().retryBackoff(), e.toString());
                if (claimed.get() == null) {
                    break;
                }
                defer(claimed.get());
                continue;
            }
            if (done == null || done.isEmpty()) {
                break;
            }
            handled++;
        }
        for (int i = 0; i < properties.sweep().batchSize(); i++) {
            Boolean any;
            try {
                any = tx.execute(status -> tasks.claimNextPendingApproval(clock.instant())
                        .map(task -> {
                            taskProcessor.process(task);
                            return Boolean.TRUE;
                        }).orElse(Boolean.FALSE));
            } catch (RuntimeException e) {
                LOG.error("Retire sweep: a task failed and was rolled back; it is retried next sweep: {}",
                        e.toString());
                break;
            }
            if (!Boolean.TRUE.equals(any)) {
                break;
            }
            handled++;
        }
        if (handled > 0) {
            LOG.info("Retire sweep handled {} row(s) and task(s) (dryRun={})", handled, properties.dryRunCloudDelete());
        }
        return handled;
    }

    private void defer(UUID id) {
        if (id == null) {
            return;
        }
        try {
            tx.executeWithoutResult(status -> records.deferNextCheck(id,
                    clock.instant().plus(properties.sweep().retryBackoff())));
        } catch (RuntimeException e) {
            LOG.error("Retire sweep: could not defer record {}: {}", id, e.toString());
        }
    }
}
