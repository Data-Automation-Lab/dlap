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

package com.dlap.logingest.parse.cloudtrail;

import com.dlap.logingest.parse.core.mapping.MappingSpec;
import com.dlap.logingest.parse.core.mapping.MappingSpecs;
import java.util.List;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Registers the {@code AWS_CLOUDTRAIL} parser. The detector of the {@code s3-file} envelope comes
 * from {@code dlap/log-formats/cloudtrail/mapping.yaml}; the registry collects the bean like any
 * family's (parser contract, rule 2), and it is inert on the legacy pipeline.
 */
@Configuration(proxyBeanMethods = false)
public class CloudTrailConfiguration {

    /**
     * The parser of the trail file envelope (CloudTrail {@code eventVersion} 1.0 to 1.11).
     *
     * @param specs the loaded family mappings
     * @return the parser
     * @throws IllegalStateException when the family mapping is not on the class path, or does not
     *                               declare exactly one version range
     */
    @Bean
    public CloudTrailParser cloudTrailS3FileParser(MappingSpecs specs) {
        MappingSpec spec = specs.family(CloudTrailFormat.FAMILY).orElseThrow(() -> new IllegalStateException(
                "no mapping for family " + CloudTrailFormat.FAMILY + ": dlap/log-formats/cloudtrail/mapping.yaml is missing"));
        List<CloudTrailParser> parsers = CloudTrailParser.forSpec(spec);
        if (parsers.size() != 1) {
            throw new IllegalStateException("the cloudtrail mapping must declare one major version, found "
                    + parsers.size() + " ranges");
        }
        return parsers.get(0);
    }
}
