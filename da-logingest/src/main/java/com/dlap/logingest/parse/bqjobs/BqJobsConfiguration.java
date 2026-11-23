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

package com.dlap.logingest.parse.bqjobs;

import com.dlap.logingest.parse.core.mapping.MappingSpec;
import com.dlap.logingest.parse.core.mapping.MappingSpecs;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Registers the {@code GCP_BQ_JOBS_VIEW} parser. The detector comes from the family mapping, which
 * {@code ParserContractConfiguration} loads from {@code dlap/log-formats/bqjobs/mapping.yaml}; no
 * central switch names the family (parser contract, rule 2).
 */
@Configuration(proxyBeanMethods = false)
public class BqJobsConfiguration {

    /**
     * The parser of the {@code jobs-view-row} envelope.
     *
     * @param specs the loaded family mappings
     * @return the parser
     * @throws IllegalStateException when the family mapping is not on the classpath
     */
    @Bean
    public BqJobsParser bqJobsParser(MappingSpecs specs) {
        MappingSpec spec = specs.family(BqJobsFormat.FAMILY).orElseThrow(() -> new IllegalStateException(
                "no mapping for family " + BqJobsFormat.FAMILY + ": dlap/log-formats/bqjobs/mapping.yaml is missing"));
        return new BqJobsParser(spec);
    }
}
