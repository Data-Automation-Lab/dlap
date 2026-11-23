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

import com.dlap.logingest.metrics.LogIngestMetrics;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Registers the settings of the backfill jobs and of the source re-read behind quarantine replay,
 * {@code dlap.logingest.backfill.*}. The beans themselves are components: the job table, service
 * and controller always exist, while the runner, the scheduler and the source re-read need the
 * registry pipeline.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(BackfillProperties.class)
public class BackfillConfiguration {

    /**
     * The backfill meters (Q-558) and the job-status gauges. The meters exist whatever the pipeline,
     * so a dashboard reads zeros instead of a missing series while backfill is off.
     *
     * @param registry the meter registry
     * @param logging  the log-layer meters, for the bounded {@code binding} label
     * @param jobs     the job table
     * @param clock    the application clock when unique, else the UTC system clock
     * @return the meters
     */
    @Bean
    public BackfillMetrics backfillMetrics(MeterRegistry registry, LogIngestMetrics logging, BackfillJobs jobs,
                                           ObjectProvider<Clock> clock) {
        BackfillMetrics metrics = new BackfillMetrics(registry, logging::bindingLabel);
        metrics.bindJobGauges(jobs, clock.getIfUnique(Clock::systemUTC));
        return metrics;
    }
}
