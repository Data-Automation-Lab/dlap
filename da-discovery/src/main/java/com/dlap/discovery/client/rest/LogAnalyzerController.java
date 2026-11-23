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

package com.dlap.discovery.client.rest;

import com.dlap.discovery.common.model.enums.CloudProvider;
import com.dlap.discovery.log.service.ILogAnalyzer;
import com.dlap.discovery.log.service.gcp.web.dto.AnalyzeLogsRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/v1/logs")
public class LogAnalyzerController {

    private static final Logger log = LoggerFactory.getLogger(LogAnalyzerController.class);

    private final ILogAnalyzer logAnalyzer;

    @Autowired
    public LogAnalyzerController(@Qualifier("gcpLogAnalyzer") ILogAnalyzer logAnalyzer) {
        this.logAnalyzer = logAnalyzer;
    }

    // Add methods to handle log analysis requests here
    // For example:
    // @GetMapping("/analyze")
    // public ResponseEntity<AnalysisResult> analyzeLogs(@RequestBody
    // LogAnalysisRequest request) {
    // // Call logAnalyzer to perform analysis
    // AnalysisResult result = logAnalyzer.analyze(request);
    // return ResponseEntity.ok(result);
    // }

    @PreAuthorize("hasAnyAuthority('ROLE_ADMIN', 'ROLE_POLICY_MANAGER', 'ROLE_DIRECTOR')")
    @PostMapping("/analyze")
    public void analyzeLogs(@RequestBody AnalyzeLogsRequest request) {
        // Placeholder for log analysis logic
        // This method should call the logAnalyzer to perform the analysis
        // and return the result.
        log.info(
            "Analyzing logs for account: {}, provider: {}, startTime: {}, endTime: {}",
            request.getAccountId(),
            request.getProvider(),
            request.getStartTime(),
            request.getEndTime()
        );

        CloudProvider cloudProvider;
        try {
            cloudProvider = CloudProvider.valueOf(request.getProvider().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid cloud provider: " + request.getProvider());
        }

        if (request.getAccountId() == null || request.getAccountId().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Account ID is required");
        }

        logAnalyzer.triggerLogAnalysisAsync(request.getAccountId(), request.getStartTime(), request.getEndTime(), request.getOptions());
    }
}
