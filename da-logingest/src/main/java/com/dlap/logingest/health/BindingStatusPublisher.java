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

package com.dlap.logingest.health;

import com.dlap.logingest.config.sources.BuiltinBindings;
import com.dlap.logingest.metrics.ParseTelemetry;
import com.dlap.logingest.parse.core.ConditionalOnRegistryPipeline;
import com.dlap.proto.log.v1.LogBindingStatus;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Publishes the status of every binding to {@code dlap.logingest.binding.status} on a schedule, so
 * the catalog's retention sweep (CAT-03) and operators learn which bindings capture data reads, how
 * far each cursor has come and whether a binding is degraded.
 *
 * <p>A cluster-wide singleton (ShedLock JDBC on database time, plan rule R7): with several replicas
 * one of them publishes per interval. The counts of a status are the publishing instance's own view
 * of the window since its last publication ({@code ParseTelemetry#drain}); the cursors, the observed
 * versions and the configuration hash come from the database and the configuration, so they are the
 * same whichever replica publishes. One binding failing to encode or send does not stop the others.
 * Active only with the registry pipeline and {@code dlap.logingest.health.status-enabled=true}.
 */
@Component
@ConditionalOnRegistryPipeline
@ConditionalOnProperty(name = "dlap.logingest.health.status-enabled", havingValue = "true", matchIfMissing = true)
public class BindingStatusPublisher {

    /** Name of the ShedLock row that serializes publication across replicas. */
    public static final String LOCK_NAME = "logingest-binding-status";

    /** Longest a run may hold the lock: far above twice a run, which is a few small sends (plan rule R7). */
    public static final String LOCK_AT_MOST_FOR = "PT10M";

    private static final Logger log = LoggerFactory.getLogger(BindingStatusPublisher.class);

    private final HealthSnapshotService snapshots;
    private final ParseTelemetry telemetry;
    private final BindingStatusTopicPublisher topic;
    private final BindingStatusAssembler assembler;
    private final ParserSelfTest selfTest;
    private final Clock clock;

    /**
     * Creates the publisher.
     *
     * @param snapshots the health snapshot
     * @param telemetry the in-memory signals
     * @param topic     the topic publisher
     * @param props     the health settings
     * @param selfTest  the startup self-test
     * @param clock     the application clock when unique, else the UTC system clock
     */
    public BindingStatusPublisher(HealthSnapshotService snapshots, ParseTelemetry telemetry,
                                  BindingStatusTopicPublisher topic, LogHealthProperties props,
                                  ParserSelfTest selfTest, ObjectProvider<Clock> clock) {
        this.snapshots = snapshots;
        this.telemetry = telemetry;
        this.topic = topic;
        this.assembler = new BindingStatusAssembler(props);
        this.selfTest = selfTest;
        this.clock = clock.getIfUnique(Clock::systemUTC);
    }

    /** Scheduled entry point, a cluster-wide singleton. */
    @Scheduled(initialDelayString = "${dlap.logingest.health.status-initial-delay:PT30S}",
            fixedDelayString = "${dlap.logingest.health.status-interval:PT1M}")
    @SchedulerLock(name = LOCK_NAME, lockAtMostFor = LOCK_AT_MOST_FOR, lockAtLeastFor = "PT5S")
    public void scheduledPublish() {
        try {
            publishAll();
        } catch (RuntimeException e) {
            // Status is telemetry; it must never take the service down. The next interval retries.
            log.warn("Binding status publication failed: {}", e.toString());
        }
    }

    /**
     * Builds and publishes the status of every configured binding; a built-in binding only once it carried a record.
     *
     * @return the statuses that were sent
     */
    public List<LogBindingStatus> publishAll() {
        HealthSnapshot snapshot = snapshots.current();
        List<LogBindingStatus> sent = new ArrayList<>();
        for (HealthSnapshot.BindingSnapshot b : snapshot.bindings()) {
            // The built-in REST and replay bindings (one per provider) report only once they carried a record.
            if (BuiltinBindings.isReservedId(b.bindingId()) && telemetry.lastRecordAt(b.bindingId()).isEmpty()) {
                continue;
            }
            try {
                LogBindingStatus status = assembler.assemble(b, telemetry.drain(b.bindingId()),
                        snapshots.readCoverage(b), selfTest.passed(), clock.instant());
                topic.publish(status);
                sent.add(status);
            } catch (RuntimeException e) {
                log.warn("Could not publish the status of binding {}: {}", b.bindingId(), e.toString());
            }
        }
        return sent;
    }
}
