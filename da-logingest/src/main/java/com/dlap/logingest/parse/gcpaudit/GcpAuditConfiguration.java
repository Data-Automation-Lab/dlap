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

package com.dlap.logingest.parse.gcpaudit;

import com.dlap.logingest.parse.core.mapping.MappingSpec;
import com.dlap.logingest.parse.core.mapping.MappingSpecs;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Registers the {@code GCP_CLOUD_AUDIT} family (GCP-01): one parser bean per envelope and the code
 * detector of the {@code bq-sink-row} envelope. The {@code api-proto} and {@code json} envelopes get
 * their declarative detectors from {@code dlap/log-formats/gcpaudit/mapping.yaml}. The registry
 * collects the beans like any family's (ADR-0054 decision 2); they are inert on the legacy
 * pipeline.
 */
@Configuration(proxyBeanMethods = false)
public class GcpAuditConfiguration {

    /**
     * The parser of the Logging API client envelope.
     *
     * @param specs the family mappings
     * @return the parser
     */
    @Bean
    public GcpAuditParser gcpAuditApiProtoParser(MappingSpecs specs) {
        return new GcpAuditParser(spec(specs), GcpAuditFormat.API_PROTO);
    }

    /**
     * The parser of the REST JSON and Cloud Storage shard envelope.
     *
     * @param specs the family mappings
     * @return the parser
     */
    @Bean
    public GcpAuditParser gcpAuditJsonParser(MappingSpecs specs) {
        return new GcpAuditParser(spec(specs), GcpAuditFormat.JSON);
    }

    /**
     * The parser of the BigQuery sink row envelope.
     *
     * @param specs the family mappings
     * @return the parser
     */
    @Bean
    public GcpAuditParser gcpAuditBqSinkRowParser(MappingSpecs specs) {
        return new GcpAuditParser(spec(specs), GcpAuditFormat.BQ_SINK_ROW);
    }

    /**
     * The detector of the BigQuery sink row envelope.
     *
     * @return the detector
     */
    @Bean
    public BqSinkRowDetector gcpAuditBqSinkRowDetector() {
        return new BqSinkRowDetector();
    }

    private static MappingSpec spec(MappingSpecs specs) {
        MappingSpec spec = specs.family(GcpAuditFormat.FAMILY).orElseThrow(() -> new IllegalStateException(
                "the " + GcpAuditFormat.FAMILY + " mapping (dlap/log-formats/" + GcpAuditFormat.FAMILY
                        + "/mapping.yaml) is not on the class path"));
        // Fail at startup, not on the first legacy BigQuery record, when the AuditData descriptors are broken.
        AuditDataDecoder.descriptor();
        return spec;
    }
}
