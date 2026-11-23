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

package com.dlap.adminservice.permissioncheck.providers.aws;

import java.net.URI;
import java.time.Clock;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.dlap.adminservice.audit.export.HttpCalls;
import com.dlap.adminservice.audit.providers.aws.AwsCredentialsSource;
import com.dlap.adminservice.permissioncheck.PermissionProbe;

/** Wires the AWS probe of the permission self-check. */
@Configuration
@EnableConfigurationProperties(AwsPermissionCheckProperties.class)
public class AwsPermissionCheckConfiguration {

    /**
     * The AWS probe. Credentials come from the environment or the instance profile
     * ({@link AwsCredentialsSource}), the same chain the audit export uses.
     *
     * @param config the AWS block
     * @param http   the shared HTTP client
     * @return the probe
     */
    @Bean
    public PermissionProbe awsPermissionProbe(AwsPermissionCheckProperties config, HttpCalls http) {
        Clock clock = Clock.systemUTC();
        AwsCredentialsSource source = new AwsCredentialsSource(System::getenv, http,
                URI.create(config.getImdsEndpoint()), clock);
        return new AwsPolicySimulatorProbe(config, http, source::get, clock);
    }
}
