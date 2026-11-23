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

package com.dlap.logingest.backfill;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

/**
 * One backfill job, as the API returns it.
 *
 * @param id          the job id
 * @param bindingId   the binding whose history is read
 * @param streamId    the stream of that binding that is read
 * @param from        inclusive start of the event-time window
 * @param to          exclusive end of the event-time window
 * @param status      the state
 * @param progress    what the job has done
 * @param requestedBy who asked (a name, never a token)
 * @param requestedAt when the job was accepted
 * @param startedAt   when it first ran, or {@code null}
 * @param finishedAt  when it reached a terminal state, or {@code null}
 * @param errorDetail why it failed or was cancelled, never a provider value
 */
public record BackfillJob(UUID id, String bindingId, String streamId, Instant from, Instant to, BackfillStatus status,
                          BackfillProgress progress, String requestedBy, Instant requestedAt, Instant startedAt,
                          Instant finishedAt, String errorDetail) {

    /**
     * Freezes the job.
     *
     * @param id          see the record description
     * @param bindingId   see the record description
     * @param streamId    see the record description
     * @param from        see the record description
     * @param to          see the record description
     * @param status      see the record description
     * @param progress    see the record description
     * @param requestedBy see the record description
     * @param requestedAt see the record description
     * @param startedAt   see the record description
     * @param finishedAt  see the record description
     * @param errorDetail see the record description
     */
    public BackfillJob {
        progress = progress == null ? BackfillProgress.EMPTY : progress;
        errorDetail = errorDetail == null ? "" : errorDetail;
    }

    /**
     * Roughly how much of the window has been read: the mean over the known partitions of how far each
     * low watermark has travelled, 1.0 when the job succeeded. A partition not yet read counts as 0.
     *
     * @return a number between 0 and 1
     */
    @JsonProperty("fractionComplete")
    public double fractionComplete() {
        if (status == BackfillStatus.SUCCEEDED) {
            return 1.0;
        }
        long span = Duration.between(from, to).toMillis();
        if (span <= 0 || progress.partitions().isEmpty()) {
            return 0.0;
        }
        double sum = 0;
        for (BackfillProgress.Partition p : progress.partitions()) {
            if (p.done()) {
                sum += 1.0;
            } else if (p.low() != null) {
                double travelled = Duration.between(from, p.low().isBefore(from) ? from : p.low()).toMillis();
                sum += Math.min(1.0, Math.max(0.0, travelled / span));
            }
        }
        return Math.round(sum / progress.partitions().size() * 10_000d) / 10_000d;
    }
}
