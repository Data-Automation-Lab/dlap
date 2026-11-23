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

package com.dlap.logingest.parse.core.contract;

import com.dlap.fixtures.FixtureCatalog;
import com.dlap.logingest.parse.core.ParserRegistry;
import com.dlap.logingest.parse.core.mapping.MappingDrivenParser;
import com.dlap.logingest.parse.core.mapping.MappingSpec;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.file.Path;
import java.util.Optional;

/**
 * Runs the contract base with its default projection (expected events written in
 * {@code DlapLogEvent} proto3 JSON, as family packages write them) on a small corpus kept next to
 * this test: canonical asset ids, a newer minor, a fallback clock, a failure, json-object-array
 * framing, a declared skip, and a quarantine with a pass-through event.
 */
class ContractDemoParserContractTest extends AbstractParserContractTest {

    static final String MAPPING = "com/dlap/logingest/parse/core/contract/contract-demo-mapping.yaml";

    @Override
    protected String family() {
        return "contract-demo";
    }

    @Override
    protected String sourceKind() {
        return "DLAP_CONTRACT_DEMO";
    }

    @Override
    protected Optional<MappingSpec> mapping() {
        return Optional.of(mappingResource(MAPPING, "contract-demo"));
    }

    @Override
    protected ParserRegistry registry() {
        MappingSpec spec = mapping().orElseThrow();
        return registryOf(spec, MappingDrivenParser.forEnvelope(spec, "generic-json", "contract-demo-parser", "1.0.0"));
    }

    @Override
    protected FixtureCatalog corpus() {
        return FixtureCatalog.load(corpusRoot());
    }

    static Path corpusRoot() {
        URL url = ContractDemoParserContractTest.class.getResource("corpus");
        if (url == null) {
            throw new IllegalStateException("contract-demo corpus missing from the test classpath");
        }
        try {
            return Path.of(url.toURI());
        } catch (URISyntaxException e) {
            throw new IllegalStateException(e);
        }
    }
}
