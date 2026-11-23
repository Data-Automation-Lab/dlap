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

package com.dlap.policyengine.dsl;

import java.time.Duration;
import java.time.ZoneId;
import java.util.List;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * {@code dlap.policyengine.schedule.*}: the scheduled policy sweep (POL-01).
 *
 * <p>The tick interval, initial delay and lock bound are read by the {@code @Scheduled} and
 * {@code @SchedulerLock} annotations of {@link ScheduledPolicySweeper} directly
 * ({@code dlap.policyengine.schedule.tick}, {@code .initial-delay}, {@code .lock-at-most-for});
 * they are listed in {@code application.properties} with their defaults.
 *
 * @param enabled           whether the sweeper bean exists at all
 * @param zone              time zone the cron expressions are read in
 * @param pageSize          catalog activity page size (the catalog caps it at 200)
 * @param maxAssetsPerSweep upper bound of assets one sweep reads; the sweep stops there with a
 *                          warning and still records the slot
 * @param statuses          lifecycle statuses a sweep evaluates; deleted and pending-deletion
 *                          assets are never acted on by a schedule fire
 * @param sendTimeout       how long a sweep waits, after its last page, for the broker to
 *                          acknowledge the actions it published (Q-439); an action not acknowledged
 *                          in time counts as failed and keeps the slot open
 */
@ConfigurationProperties(prefix = "dlap.policyengine.schedule")
public record PolicyScheduleProperties(
        @DefaultValue("true") boolean enabled,
        @DefaultValue("UTC") String zone,
        @DefaultValue("200") int pageSize,
        @DefaultValue("100000") int maxAssetsPerSweep,
        @DefaultValue({"ACTIVE", "ARCHIVED"}) List<String> statuses,
        @DefaultValue("PT60S") Duration sendTimeout) {

    /**
     * Validates the settings.
     */
    public PolicyScheduleProperties {
        if (pageSize < 1 || pageSize > 200) {
            throw new IllegalArgumentException("dlap.policyengine.schedule.page-size must be 1 to 200, was " + pageSize);
        }
        if (maxAssetsPerSweep < 1) {
            throw new IllegalArgumentException("dlap.policyengine.schedule.max-assets-per-sweep must be positive");
        }
        statuses = statuses == null || statuses.isEmpty() ? List.of("ACTIVE", "ARCHIVED") : List.copyOf(statuses);
        sendTimeout = sendTimeout == null ? Duration.ofSeconds(60) : sendTimeout;
        if (sendTimeout.isNegative()) {
            throw new IllegalArgumentException("dlap.policyengine.schedule.send-timeout must not be negative");
        }
        ZoneId.of(zone);
    }

    /**
     * The cron time zone.
     *
     * @return the zone
     */
    public ZoneId zoneId() {
        return ZoneId.of(zone);
    }
}
