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

package com.dlap.autoarchival.reconcile;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Settings of the archive rule reconciliation (Q-607), prefix {@code dlap.autoarchival.reconcile}.
 *
 * @param enabled      whether the job runs at all
 * @param maxApplied   a rule {@code APPLIED} for longer than this, with no provider event proving a
 *                     transition, is listed. A rule on a prefix where no object is old enough yet legitimately
 *                     stays unproven for its whole age setting, so this is a list to look at, not a failure
 * @param claimTimeout a row {@code EXECUTING} for longer than this lost its worker (a crash between the claim
 *                     and the outcome) and is listed; the job never runs it again
 * @param logLimit     how many rows of each list are written to the log per run (the metric counts all)
 */
@ConfigurationProperties(prefix = "dlap.autoarchival.reconcile")
public record ArchiveReconcileProperties(
        @DefaultValue("true") boolean enabled,
        @DefaultValue("P7D") Duration maxApplied,
        @DefaultValue("PT30M") Duration claimTimeout,
        @DefaultValue("50") int logLimit) {

    /**
     * Normalises the values: a missing or non-positive duration or limit takes its default.
     */
    public ArchiveReconcileProperties {
        maxApplied = maxApplied == null || maxApplied.isZero() || maxApplied.isNegative() ? Duration.ofDays(7)
                : maxApplied;
        claimTimeout = claimTimeout == null || claimTimeout.isZero() || claimTimeout.isNegative()
                ? Duration.ofMinutes(30) : claimTimeout;
        logLimit = logLimit <= 0 ? 50 : Math.min(logLimit, 1000);
    }
}
