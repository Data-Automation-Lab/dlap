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

package com.dlap.autocompliance.signal;

import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Registers the fallback {@link ComplianceSignalProvider} (no-op) for the
 * compliance-via-labels pattern (ADR-0035). A deployment overrides it by supplying its own
 * bean - the {@code @ConditionalOnMissingBean} guard ensures the default yields.
 *
 * <p>The default {@link ComplianceLabelSink} is {@link CatalogEventComplianceLabelSink}
 * (a {@code @Component}), which writes labels through DLAP's authoritative addLabel pipeline.
 * {@link LoggingComplianceLabelSink} is retained as an opt-in "observe-only" alternative a
 * deployment can register instead.
 */
@Configuration
public class ComplianceSignalConfiguration {

    @Bean
    @ConditionalOnMissingBean(ComplianceSignalProvider.class)
    public ComplianceSignalProvider noopComplianceSignalProvider() {
        return new NoopComplianceSignalProvider();
    }
}
