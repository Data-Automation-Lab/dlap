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

package com.dlap.catalog.kafka;

import java.time.Clock;

import org.springframework.beans.factory.InitializingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Wiring of the catalog event consumers: the injected {@link Clock} (plan rule R6: no
 * {@code Instant.now()} for any time the consumers write), scheduling for the
 * processed-event pruner, and a fail-fast check of {@code dlap.catalog.ingest.*}.
 */
@Configuration
@EnableScheduling
public class CatalogIngestConfiguration {

    /**
     * The catalog's time source. Business times never come from it; it stamps operational
     * times only ({@code processed_at}, {@code last_scanned_at}, {@code indexed_at}).
     *
     * @return the UTC system clock
     */
    @Bean
    @ConditionalOnMissingBean(Clock.class)
    public Clock catalogClock() {
        return Clock.systemUTC();
    }

    /**
     * Rejects invalid ingest settings at startup (a governed prefix that is not an object-URI
     * prefix, a non-positive retention or batch size).
     *
     * @param properties the settings
     * @return the check, run when the context starts
     */
    @Bean
    public InitializingBean catalogIngestPropertiesCheck(CatalogIngestProperties properties) {
        return properties::validate;
    }
}
