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

package com.dlap.adminservice.client;

import com.dlap.adminservice.dto.JobStatusDTO;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;

import java.util.List;

// The name "discovery-service" should match a Feign client configuration in application.properties
// e.g., feign.client.config.discovery-service.url=http://da-discovery-service-host:port/api/v1/discovery
// The path here is relative to the URL configured for the Feign client.
@FeignClient(name = "discovery-service", url = "${services.da-discovery.url:http://localhost:8081}/api/v1/discovery")
public interface IDiscoveryJobApiClient {

    // Assuming da-discovery exposes an endpoint like /api/v1/discovery/jobs that returns a list of its jobs
    // and that its Job DTO is compatible or can be mapped to our common JobStatusDTO
    @GetMapping("/jobs") // This path is relative to the Feign client's configured URL
    List<JobStatusDTO> getDiscoveryJobs();

    @GetMapping("/jobs/{jobId}")
    JobStatusDTO getDiscoveryJobById(@PathVariable("jobId") String jobId);
} 