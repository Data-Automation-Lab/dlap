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

import com.dlap.logingest.parse.core.ParserRegistry;
import com.dlap.logingest.parse.core.contract.AbstractParserContractTest;
import com.dlap.logingest.parse.core.mapping.MappingSpec;
import java.util.List;
import java.util.Optional;

/**
 * The frozen parser contract (ADR-0054) on every case of the {@code bqjobs} fixture family: outcome,
 * events, wire contract, determinism, unknown-field tolerance and totality.
 */
class BqJobsContractTest extends AbstractParserContractTest {

    private final MappingSpec mapping = classpathMapping(BqJobsFormat.FAMILY);

    @Override
    protected String family() {
        return BqJobsFormat.FAMILY;
    }

    @Override
    protected String sourceKind() {
        return BqJobsFormat.SOURCE_KIND;
    }

    @Override
    protected Optional<MappingSpec> mapping() {
        return Optional.of(mapping);
    }

    @Override
    protected ParserRegistry registry() {
        return registryOf(mapping, List.of(new BqJobsParser(mapping)));
    }
}
