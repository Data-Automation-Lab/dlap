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

package com.dlap.discovery.providers.azure.coverage;

import java.util.List;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.dlap.discovery.coverage.CoverageHttp;
import com.dlap.discovery.coverage.ReadCoverageProperties;
import com.dlap.discovery.providers.azure.auth.AzureAuthenticationService;
import com.dlap.discovery.providers.azure.config.AzureProperties;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Wires the Azure read-coverage collector when {@code dlap.discovery.coverage.enabled},
 * {@code dlap.discovery.coverage.azure.enabled} and {@code cloud.provider.azure.enabled} are all
 * true. The subscriptions are {@code dlap.discovery.coverage.azure.subscriptions}, or the discovery
 * subscription when that list is empty.
 */
@Configuration
@ConditionalOnProperty(name = {"dlap.discovery.coverage.enabled", "dlap.discovery.coverage.azure.enabled",
        "cloud.provider.azure.enabled"}, havingValue = "true")
public class AzureReadCoverageConfiguration {

    /**
     * The collector.
     *
     * @param auth       the Azure credential
     * @param azure      the discovery Azure settings
     * @param properties the coverage settings
     * @param mapper     the JSON mapper
     * @return the collector
     */
    @Bean
    public AzureReadCoverageCollector azureReadCoverageCollector(AzureAuthenticationService auth, AzureProperties azure,
            ReadCoverageProperties properties, ObjectMapper mapper) {
        List<String> subscriptions = properties.getAzure().getSubscriptions().stream()
                .map(String::strip).filter(s -> !s.isEmpty()).toList();
        if (subscriptions.isEmpty() && azure.getSubscriptionId() != null && !azure.getSubscriptionId().isBlank()) {
            subscriptions = List.of(azure.getSubscriptionId().strip());
        }
        AzureCoverageRestClient client = new AzureCoverageRestClient(new CoverageHttp(mapper),
                auth::getTokenCredential,
                properties.getAzure().getManagementEndpoint());
        return new AzureReadCoverageCollector(client, subscriptions);
    }
}
