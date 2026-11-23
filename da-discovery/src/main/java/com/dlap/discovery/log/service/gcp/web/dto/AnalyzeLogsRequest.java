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

package com.dlap.discovery.log.service.gcp.web.dto;

import com.dlap.discovery.log.service.ILogAnalyzer.AnalysisOptions;
import java.time.ZonedDateTime;

public class AnalyzeLogsRequest {

    private String provider;
    private String accountId;
    private ZonedDateTime startTime;
    private ZonedDateTime endTime;
    private AnalysisOptions options;

    public AnalyzeLogsRequest() {}

    public AnalyzeLogsRequest(String provider, String accountId, ZonedDateTime startTime, ZonedDateTime endTime, AnalysisOptions options) {
        this.provider = provider;
        this.accountId = accountId;
        this.startTime = startTime;
        this.endTime = endTime;
        this.options = options;
    }

    public String getProvider() {
        return provider;
    }

    public void setProvider(String provider) {
        this.provider = provider;
    }

    public String getAccountId() {
        return accountId;
    }

    public void setAccountId(String accountId) {
        this.accountId = accountId;
    }

    public ZonedDateTime getStartTime() {
        return startTime;
    }

    public void setStartTime(ZonedDateTime startTime) {
        this.startTime = startTime;
    }

    public ZonedDateTime getEndTime() {
        return endTime;
    }

    public void setEndTime(ZonedDateTime endTime) {
        this.endTime = endTime;
    }

    public AnalysisOptions getOptions() {
        return options;
    }

    public void setOptions(AnalysisOptions options) {
        this.options = options;
    }
}
