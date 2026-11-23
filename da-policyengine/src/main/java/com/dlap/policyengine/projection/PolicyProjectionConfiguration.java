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

package com.dlap.policyengine.projection;

import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Registers the fallback {@link PolicyProjectionSink} for the push-down
 * projection feature (ADR-0040 push-down mode; mirrors ADR-0035's
 * {@code ComplianceSignalConfiguration}).
 *
 * <p>The default is {@link LoggingPolicyProjectionSink} (observe-only). A
 * deployment overrides it by supplying its own bean - the
 * {@code @ConditionalOnMissingBean} guard ensures the default yields to, e.g.,
 * {@link UnityCatalogPolicyProjectionSink} when
 * {@code dlap.policy.projection.unity.enabled=true}.
 */
@Configuration
@EnableConfigurationProperties(PolicyProjectionProperties.class)
public class PolicyProjectionConfiguration {

    @Bean
    @ConditionalOnMissingBean(PolicyProjectionSink.class)
    public PolicyProjectionSink loggingPolicyProjectionSink() {
        return new LoggingPolicyProjectionSink();
    }
}
