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

package com.dlap.logingest.parse.s3accesslog;

import com.dlap.logingest.parse.core.mapping.MappingSpec;
import com.dlap.logingest.parse.core.mapping.MappingSpecs;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Registers the {@code AWS_S3_SERVER_ACCESS_LOG} parser. The detector of the {@code cwl-json}
 * envelope comes from {@code dlap/log-formats/s3accesslog/mapping.yaml}; the registry collects the
 * bean like any family's (parser contract, rule 2), and it is inert on the legacy pipeline.
 */
@Configuration(proxyBeanMethods = false)
public class S3AccessLogConfiguration {

    /**
     * The parser of the CloudWatch Logs JSON envelope.
     *
     * @param specs the loaded family mappings
     * @return the parser
     * @throws IllegalStateException when the family mapping is not on the class path
     */
    @Bean
    public S3AccessLogParser s3AccessLogParser(MappingSpecs specs) {
        MappingSpec spec = specs.family(S3AccessLogFormat.FAMILY).orElseThrow(() -> new IllegalStateException(
                "no mapping for family " + S3AccessLogFormat.FAMILY
                        + ": dlap/log-formats/s3accesslog/mapping.yaml is missing"));
        return new S3AccessLogParser(spec);
    }
}
