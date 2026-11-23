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

import java.time.Clock;

import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * The policy engine's business clock (plan rule R6, POL-01). Policy evaluation, action
 * {@code firedAt} and evaluation records read time from this bean, never from the wall clock
 * directly, so a test pins it and a replay keeps its own instants. The UTC system clock unless a
 * test or a deployment supplies its own {@link Clock} bean.
 */
@Configuration(proxyBeanMethods = false)
public class PolicyClockConfiguration {

    /**
     * The business clock.
     *
     * @return the UTC system clock
     */
    @Bean
    @ConditionalOnMissingBean(Clock.class)
    Clock policyEngineClock() {
        return Clock.systemUTC();
    }
}
