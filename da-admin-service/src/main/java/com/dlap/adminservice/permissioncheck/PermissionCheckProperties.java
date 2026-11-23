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

package com.dlap.adminservice.permissioncheck;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Settings shared by the permission self-check, {@code dlap.permission-check.*}. Each cloud has its
 * own block ({@code dlap.permission-check.aws}, {@code .gcp}, {@code .azure}) read by its provider
 * package. Every default lives in code, so the service starts without any key; a cloud with no
 * resource configured reports {@code NOT_CONFIGURED}.
 */
@ConfigurationProperties(prefix = "dlap.permission-check")
public class PermissionCheckProperties {

    private String traceDir = "";
    private Duration timeout = Duration.ofSeconds(10);

    /** @return a directory laid out like {@code deployment/iam}; blank reads the copy in the jar */
    public String getTraceDir() {
        return traceDir;
    }

    /** @param traceDir a directory laid out like {@code deployment/iam}, or blank */
    public void setTraceDir(String traceDir) {
        this.traceDir = traceDir;
    }

    /** @return the connect and request timeout of every provider call */
    public Duration getTimeout() {
        return timeout;
    }

    /** @param timeout the connect and request timeout */
    public void setTimeout(Duration timeout) {
        this.timeout = timeout;
    }
}
