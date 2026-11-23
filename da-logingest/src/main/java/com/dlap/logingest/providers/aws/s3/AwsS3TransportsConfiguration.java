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

package com.dlap.logingest.providers.aws.s3;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Registers the two S3 log transports ({@value CloudTrailTrailFileTransport#ID},
 * {@value S3ServerAccessLogTransport#ID}) when {@value #ENABLED} is {@code true}
 * ({@code DLAP_LOGINGEST_TRANSPORTS_AWS_ENABLED}); off by default.
 *
 * <p>Turning them on registers the transports with {@code TransportRegistry}; nothing is read until
 * the bindings file names a stream with one of them and {@code dlap.logingest.pipeline=registry}.
 * With the switch off, such a stream fails validation as {@code UNKNOWN_TRANSPORT}. No client is
 * created at startup: a client is built on a stream's first poll. The legacy LookupEvents source
 * ({@code dlap.logingest.aws.enabled}) is separate and unchanged.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = AwsS3TransportsConfiguration.ENABLED, havingValue = "true")
public class AwsS3TransportsConfiguration {

    /** The switch of the AWS S3 log transports. */
    public static final String ENABLED = "dlap.logingest.transports.aws.enabled";

    /**
     * The S3 client access, closed with the context.
     *
     * @return the store
     */
    @Bean
    SdkS3LogStore awsS3LogStore() {
        return new SdkS3LogStore();
    }

    /**
     * The CloudTrail trail-file transport.
     *
     * @param store the client access
     * @return the transport
     */
    @Bean
    public CloudTrailTrailFileTransport awsTrailFileTransport(SdkS3LogStore store) {
        return new CloudTrailTrailFileTransport(store);
    }

    /**
     * The S3 server access log transport.
     *
     * @param store the client access
     * @return the transport
     */
    @Bean
    public S3ServerAccessLogTransport awsServerAccessLogTransport(SdkS3LogStore store) {
        return new S3ServerAccessLogTransport(store);
    }
}
