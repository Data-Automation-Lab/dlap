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

import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;

import com.dlap.adminservice.client.dto.FeignTaskListResponses.ArchivalTaskListResponse;

/**
 * Feign client for Autoarchival Task API (canonical path: /api/v1/archival/tasks).
 */
@FeignClient(name = "da-autoarchival", url = "${services.da-autoarchival.url:http://localhost:8086}")
public interface IAutoarchivalTaskApiClient {

    @GetMapping("/api/v1/archival/tasks")
    ArchivalTaskListResponse getArchivalTasks(@RequestParam(name = "size", defaultValue = "100") int size);
} 