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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.dlap.logingest.transport.LogTransport;
import com.dlap.logingest.transport.TransportRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/**
 * The AWS S3 log transports are off by default and turned on by one switch; the legacy LookupEvents
 * source keeps its own switch. With the switch on, the bindings validator knows both transports.
 */
class AwsS3TransportsConfigurationTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(AwsS3TransportsConfiguration.class);

    @Test
    void withoutTheSwitchNoTransportOrClientIsRegistered() {
        runner.run(ctx -> {
            assertThat(ctx).doesNotHaveBean(CloudTrailTrailFileTransport.class);
            assertThat(ctx).doesNotHaveBean(S3ServerAccessLogTransport.class);
            assertThat(ctx).doesNotHaveBean(SdkS3LogStore.class);
        });
        runner.withPropertyValues(AwsS3TransportsConfiguration.ENABLED + "=false")
                .run(ctx -> assertThat(ctx).doesNotHaveBean(LogTransport.class));
        runner.withPropertyValues("dlap.logingest.aws.enabled=true")
                .run(ctx -> assertThat(ctx).as("the legacy LookupEvents switch does not turn them on")
                        .doesNotHaveBean(LogTransport.class));
    }

    @Test
    void theSwitchRegistersBothTransportsOverOneStore() {
        runner.withPropertyValues(AwsS3TransportsConfiguration.ENABLED + "=true").run(ctx -> {
            assertThat(ctx).hasSingleBean(SdkS3LogStore.class);
            assertThat(ctx).hasSingleBean(CloudTrailTrailFileTransport.class);
            assertThat(ctx).hasSingleBean(S3ServerAccessLogTransport.class);
            TransportRegistry registry = new TransportRegistry(ctx.getBeansOfType(LogTransport.class).values());
            assertThat(registry.ids()).containsExactly("S3_SERVER_ACCESS_LOGS", "S3_TRAIL_FILES");
            assertThat(registry.requirements("S3_TRAIL_FILES")).hasValueSatisfying(r ->
                    assertThat(r.required()).containsExactly("bucket"));
        });
    }

    @Test
    void theEnvironmentVariableNameOfTheSwitchIsStable() {
        assertThat(AwsS3TransportsConfiguration.ENABLED).isEqualTo("dlap.logingest.transports.aws.enabled");
        assertThatThrownBy(() -> new CloudTrailTrailFileTransport(null)).isInstanceOf(NullPointerException.class);
    }
}
