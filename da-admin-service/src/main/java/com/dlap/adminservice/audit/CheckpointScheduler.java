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

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;

/**
 * Runs {@link CheckpointService#run()} on {@code dlap.audit.checkpoint.interval} (default one
 * hour). Replicas are serialised by the service's advisory lock; a failed run is logged and the
 * next one tries again.
 */
public class CheckpointScheduler {

    private static final Logger LOG = LoggerFactory.getLogger(CheckpointScheduler.class);

    private final CheckpointService service;

    /**
     * Creates the job.
     *
     * @param service the signer
     */
    public CheckpointScheduler(CheckpointService service) {
        this.service = service;
    }

    /** One scheduled run. */
    @Scheduled(fixedDelayString = "${dlap.audit.checkpoint.interval:PT1H}",
            initialDelayString = "${dlap.audit.checkpoint.initial-delay:PT2M}")
    public void runScheduled() {
        try {
            CheckpointService.CheckpointRun run = service.run();
            if (run.skipped() != null) {
                LOG.info("Audit checkpoint run skipped: {}", run.skipped());
                return;
            }
            long signed = run.streams().stream().filter(s -> s.status() == CheckpointService.StreamStatus.SIGNED).count();
            long broken = run.streams().stream().filter(s -> s.status() == CheckpointService.StreamStatus.BROKEN).count();
            LOG.info("Audit checkpoint run: streams={} signed={} broken={} exported={} exportFailures={} pending={}",
                    run.streams().size(), signed, broken, run.exported(), run.exportFailures(), run.exportPending());
        } catch (RuntimeException e) {
            LOG.error("Audit checkpoint run failed: {}", e.getMessage(), e);
        }
    }
}
