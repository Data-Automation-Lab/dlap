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

package com.dlap.logingest.providers.gcp.logging;

import java.time.Clock;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Registers the three GCP transports as beans; {@code TransportRegistry} finds them by id. No client
 * is created at startup: a client is built on a stream's first poll, from its credentials, so the
 * beans are harmless on the legacy pipeline and without GCP credentials.
 */
@Configuration(proxyBeanMethods = false)
public class GcpLoggingTransportsConfiguration {

    /**
     * The Cloud Logging client access, closed with the context.
     *
     * @return the pager
     */
    @Bean
    GapicEntriesPager gcpLoggingEntriesPager() {
        return new GapicEntriesPager(System::getenv);
    }

    /**
     * The Cloud Storage client access, closed with the context.
     *
     * @return the store
     */
    @Bean
    StorageShardStore gcpSinkShardStore() {
        return StorageShardStore.fromEnvironment(System::getenv);
    }

    /**
     * The BigQuery client access.
     *
     * @return the access
     */
    @Bean
    BigQuerySinkTables gcpSinkTables() {
        return new BigQuerySinkTables(System::getenv);
    }

    /**
     * The Cloud Logging API transport, with one 60-requests-per-minute bucket per project.
     *
     * @param pager the client access
     * @param clock the application clock when unique, else UTC
     * @return the transport
     */
    @Bean
    public LoggingApiTransport gcpLoggingApiTransport(GapicEntriesPager pager, ObjectProvider<Clock> clock) {
        return new LoggingApiTransport(pager, RequestRateLimiter.forLogging(clock.getIfUnique(Clock::systemUTC)));
    }

    /**
     * The Cloud Storage sink transport.
     *
     * @param store the client access
     * @return the transport
     */
    @Bean
    public GcsSinkTransport gcpGcsSinkTransport(StorageShardStore store) {
        return new GcsSinkTransport(store);
    }

    /**
     * The BigQuery sink transport.
     *
     * @param tables the client access
     * @return the transport
     */
    @Bean
    public BqSinkTransport gcpBqSinkTransport(BigQuerySinkTables tables) {
        return new BqSinkTransport(tables);
    }
}
