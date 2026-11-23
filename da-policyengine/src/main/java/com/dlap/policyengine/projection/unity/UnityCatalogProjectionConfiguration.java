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

package com.dlap.policyengine.projection.unity;

import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.dlap.policyengine.projection.PolicyProjectionSink;

/**
 * Wires the Unity Catalog push-down projection sink (ADR-0040 push-down mode)
 * when {@code dlap.policy.projection.unity.enabled=true}. Because this registers
 * a {@link PolicyProjectionSink} bean, {@code PolicyProjectionConfiguration}'s
 * {@code @ConditionalOnMissingBean} default (the logging sink) yields to it.
 *
 * <p>The {@link UnityCatalogGrantClient} defaults to the offline
 * {@link StubUnityCatalogGrantClient} ({@code @ConditionalOnMissingBean}); a
 * deployment overrides it with a live JDBC/REST client to actually enforce.
 */
@Configuration
@ConditionalOnProperty(name = "dlap.policy.projection.unity.enabled", havingValue = "true")
@EnableConfigurationProperties(UnityCatalogGrantProperties.class)
public class UnityCatalogProjectionConfiguration {

    @Bean
    @ConditionalOnMissingBean(UnityCatalogGrantClient.class)
    public UnityCatalogGrantClient unityCatalogGrantClient() {
        return new StubUnityCatalogGrantClient();
    }

    @Bean
    public PolicyProjectionSink unityCatalogPolicyProjectionSink(UnityCatalogGrantClient client,
                                                                 UnityCatalogGrantProperties props) {
        return new UnityCatalogPolicyProjectionSink(client, props);
    }
}
