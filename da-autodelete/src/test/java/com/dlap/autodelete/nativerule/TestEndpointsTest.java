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

package com.dlap.autodelete.nativerule;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.URI;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.BindException;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

/**
 * Q-713 (RET-08, decision D38): the retire appliers' test-only endpoints. Empty by default (every applier talks to
 * its cloud), path-style for S3 by default, and only absolute http or https URIs (https only for Azure).
 */
class TestEndpointsTest {

    private static TestEndpoints bind(Map<String, String> properties) {
        Binder binder = new Binder(new MapConfigurationPropertySource(properties));
        return binder.bindOrCreate("dlap.autodelete.test-endpoint", TestEndpoints.class);
    }

    /** Q-713: nothing set means no test endpoint anywhere, and path-style is on for when S3 gets one. */
    @Test
    void theDefaultIsEmptyWithPathStyleForS3() {
        TestEndpoints defaults = bind(Map.of());

        assertThat(defaults).isEqualTo(TestEndpoints.none());
        assertThat(defaults.s3Endpoint()).isEmpty();
        assertThat(defaults.gcsHost()).isEmpty();
        assertThat(defaults.azureResourceManager()).isEmpty();
        assertThat(defaults.s3PathStyle()).isTrue();
    }

    /** Q-713: the settings bind from the environment-style names the harness uses. */
    @Test
    void theEndpointsBindFromTheirProperties() {
        TestEndpoints set = bind(Map.of(
                "dlap.autodelete.test-endpoint.s3", "http://localstack:4566",
                "dlap.autodelete.test-endpoint.s3-path-style", "false",
                "dlap.autodelete.test-endpoint.gcs", "http://fake-gcs:4443",
                "dlap.autodelete.test-endpoint.azure", "https://arm-stub:8443/"));

        assertThat(set.s3Endpoint()).contains(URI.create("http://localstack:4566"));
        assertThat(set.s3PathStyle()).isFalse();
        assertThat(set.gcsHost()).contains("http://fake-gcs:4443");
        assertThat(set.azureResourceManager()).contains("https://arm-stub:8443/");
    }

    /** Q-713: a value that is not an absolute http or https URI stops the service at start, naming the property. */
    @Test
    void aMalformedEndpointIsRefusedWithItsPropertyName() {
        assertThatThrownBy(() -> new TestEndpoints("localstack:4566", true, "", ""))
                .hasMessageContaining("dlap.autodelete.test-endpoint.s3");
        assertThatThrownBy(() -> new TestEndpoints("", true, "file:///tmp/x", ""))
                .hasMessageContaining("dlap.autodelete.test-endpoint.gcs");
        assertThatThrownBy(() -> new TestEndpoints("", true, "", "http://arm-stub:8080/"))
                .as("the Azure SDK sends its token only over https")
                .hasMessageContaining("dlap.autodelete.test-endpoint.azure").hasMessageContaining("https");
        assertThatThrownBy(() -> bind(Map.of("dlap.autodelete.test-endpoint.s3", "not a uri")))
                .isInstanceOf(BindException.class);
    }

    /** Q-713: blanks are trimmed to "not set". */
    @Test
    void blankValuesMeanNotSet() {
        assertThat(new TestEndpoints("  ", true, null, " ")).isEqualTo(TestEndpoints.none());
    }

    /** Q-713: the start-up warning names the platform, the endpoint and that it is never for production. */
    @Test
    void theWarningSaysTestOnly() {
        assertThat(TestEndpoints.warning(NativeRulePlatform.S3_LIFECYCLE, "http://localstack:4566"))
                .contains("S3_LIFECYCLE").contains("TEST-ONLY").contains("http://localstack:4566")
                .contains("Never set this in production");
    }
}
