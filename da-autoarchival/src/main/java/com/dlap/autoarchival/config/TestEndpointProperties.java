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

package com.dlap.autoarchival.config;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The test-only endpoint settings of the archive executor (EXEC-01, Q-740, Q-745) and the deprecated names they
 * replace.
 *
 * <p>One scheme in both retire executors: {@code dlap.<service>.test-endpoint.s3}, {@code .s3-path-style},
 * {@code .gcs} and {@code .azure}, empty by default, TEST ONLY, refused under the prod profile
 * ({@link EmulatorEndpointGuard}). The RET-03 names of da-autoarchival keep working for one release as deprecated
 * aliases: each {@code @Value} expression below reads the new name first and falls back to the old one, so a
 * deployment that still sets an old name keeps its emulator, and the guard logs a WARN naming the new property.
 */
public final class TestEndpointProperties {

    /** The S3 emulator endpoint; the old name is the fallback. */
    public static final String S3 =
            "${dlap.autoarchival.test-endpoint.s3:${dlap.autoarchival.aws.endpoint-override:}}";

    /** Path-style requests against the S3 test endpoint; true by default. */
    public static final String S3_PATH_STYLE = "${dlap.autoarchival.test-endpoint.s3-path-style:true}";

    /** The GCS emulator host; the old name is the fallback. */
    public static final String GCS =
            "${dlap.autoarchival.test-endpoint.gcs:${dlap.autoarchival.gcs.endpoint-override:}}";

    /** The Azure blob endpoint of a stub (https only). There is no old name. */
    public static final String AZURE = "${dlap.autoarchival.test-endpoint.azure:}";

    /** Deprecated RET-03 property to what replaces it (a property name or a description), in a stable order. */
    public static final Map<String, String> DEPRECATED_ALIASES = aliases();

    private TestEndpointProperties() {
    }

    private static Map<String, String> aliases() {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("dlap.autoarchival.aws.endpoint-override", "dlap.autoarchival.test-endpoint.s3");
        m.put("dlap.autoarchival.gcs.endpoint-override", "dlap.autoarchival.test-endpoint.gcs");
        m.put("dlap.autoarchival.aws.endpoint-access-key",
                "the default credentials chain (AWS_ACCESS_KEY_ID; the test endpoint signs with it, else anonymously)");
        m.put("dlap.autoarchival.aws.endpoint-secret-key",
                "the default credentials chain (AWS_SECRET_ACCESS_KEY)");
        return Collections.unmodifiableMap(m);
    }
}
