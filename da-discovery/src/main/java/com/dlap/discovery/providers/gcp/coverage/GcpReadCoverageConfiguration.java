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

package com.dlap.discovery.providers.gcp.coverage;

import java.util.List;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.dlap.discovery.coverage.CoverageHttp;
import com.dlap.discovery.coverage.ReadCoverageProperties;
import com.dlap.discovery.providers.gcp.auth.GCPAuthenticationServiceImpl;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Wires the GCP read-coverage collector when {@code dlap.discovery.coverage.enabled} and
 * {@code dlap.discovery.coverage.gcp.enabled} are true. Credentials are the discovery service's
 * application-default credentials ({@link GCPAuthenticationServiceImpl}); the projects are
 * {@code dlap.discovery.coverage.gcp.projects}, or the discovery project {@code gcp.project.id} when
 * that list is empty.
 */
@Configuration
@ConditionalOnProperty(name = {"dlap.discovery.coverage.enabled", "dlap.discovery.coverage.gcp.enabled"},
        havingValue = "true")
public class GcpReadCoverageConfiguration {

    /**
     * The collector.
     *
     * @param auth           the GCP credentials
     * @param properties     the coverage settings
     * @param mapper         the JSON mapper
     * @param defaultProject the discovery project
     * @return the collector
     */
    @Bean
    public GcpReadCoverageCollector gcpReadCoverageCollector(GCPAuthenticationServiceImpl auth,
            ReadCoverageProperties properties, ObjectMapper mapper,
            @Value("${gcp.project.id:}") String defaultProject) {
        ReadCoverageProperties.Gcp gcp = properties.getGcp();
        List<String> projects = gcp.getProjects().stream().map(String::strip).filter(p -> !p.isEmpty()).toList();
        if (projects.isEmpty() && !defaultProject.isBlank()) {
            projects = List.of(defaultProject.strip());
        }
        GcpCoverageRestClient client = new GcpCoverageRestClient(new CoverageHttp(mapper), auth::getCredentials,
                gcp.getResourceManagerEndpoint(), gcp.getStorageEndpoint());
        return new GcpReadCoverageCollector(client, projects, gcp.isBigqueryCrossProjectReadsVerified());
    }
}
