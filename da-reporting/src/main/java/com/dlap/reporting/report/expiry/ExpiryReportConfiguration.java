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

package com.dlap.reporting.report.expiry;

import java.time.Clock;
import java.time.Duration;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.dlap.reporting.export.ExportManifestRegistry;

/**
 * Wiring of the Asset Activity and Expiry report. Defaults live here, in code, so a deployment
 * that sets nothing gets a working report.
 *
 * <p>Settings, all optional:
 * <ul>
 *   <li>{@code dlap.mode}: deployment mode label of the report, default {@code live};
 *       {@code shadow} marks a deployment that only observes.</li>
 *   <li>{@code dlap.reporting.expiry.max-page-size}: the largest page, 1 to 200 (the catalog
 *       limit), default 200.</li>
 *   <li>{@code dlap.reporting.expiry.export.max-rows}: the largest CSV export, default 250000.</li>
 *   <li>{@code dlap.reporting.expiry.manifest-ttl}: how long a manifest can be fetched, default PT24H.</li>
 *   <li>{@code dlap.reporting.expiry.manifest-max-entries}: how many manifests are kept, default 500.</li>
 * </ul>
 */
@Configuration
public class ExpiryReportConfiguration {

    /**
     * The registry of recent export manifests.
     *
     * @param clocks     the application clock when one is defined, else the system UTC clock
     * @param ttl        how long a manifest stays available
     * @param maxEntries the most manifests kept
     * @return the registry
     */
    @Bean
    public ExportManifestRegistry exportManifestRegistry(ObjectProvider<Clock> clocks,
            @Value("${dlap.reporting.expiry.manifest-ttl:PT24H}") Duration ttl,
            @Value("${dlap.reporting.expiry.manifest-max-entries:500}") int maxEntries) {
        return new ExportManifestRegistry(clocks.getIfAvailable(Clock::systemUTC), ttl, maxEntries);
    }
}
