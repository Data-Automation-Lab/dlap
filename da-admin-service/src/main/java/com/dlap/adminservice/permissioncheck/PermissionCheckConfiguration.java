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

import java.nio.file.Path;
import java.util.List;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.dlap.adminservice.audit.export.HttpCalls;

/**
 * Wires the permission self-check: the trace source, one shared HTTP client and the service over
 * every {@link PermissionProbe} bean. The probes are built by the provider packages.
 */
@Configuration
@EnableConfigurationProperties(PermissionCheckProperties.class)
public class PermissionCheckConfiguration {

    /**
     * The HTTP client the probes share: no redirects, the configured timeout.
     *
     * @param properties the settings
     * @return the client
     */
    @Bean
    public HttpCalls permissionCheckHttp(PermissionCheckProperties properties) {
        return new HttpCalls(properties.getTimeout());
    }

    /**
     * The service.
     *
     * @param properties the settings
     * @param probes     every probe bean
     * @return the service
     */
    @Bean
    public PermissionCheckService permissionCheckService(PermissionCheckProperties properties,
            List<PermissionProbe> probes) {
        String dir = properties.getTraceDir();
        return new PermissionCheckService(new TraceSource(dir == null || dir.isBlank() ? null : Path.of(dir)), probes);
    }
}
