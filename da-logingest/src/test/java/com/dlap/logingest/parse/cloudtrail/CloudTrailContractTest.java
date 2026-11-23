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

import com.dlap.logingest.parse.UnclaimedCellsContractTest;
import com.dlap.logingest.parse.core.ParserRegistry;
import com.dlap.logingest.parse.core.mapping.MappingSpec;
import java.util.Optional;

/**
 * The parser contract on every case of the {@code cloudtrail} fixture family (PARSE-02): the
 * seed corpus (FIX-02) and the S3 lifecycle cases, through the registered parser and the family
 * mapping. The catalog cells stay unclaimed until CT-01 reaches the coverage gate, so the cases run
 * through {@link UnclaimedCellsContractTest}.
 */
class CloudTrailContractTest extends UnclaimedCellsContractTest {

    private final MappingSpec mapping = classpathMapping(CloudTrailFormat.FAMILY);

    @Override
    protected String family() {
        return CloudTrailFormat.FAMILY;
    }

    @Override
    protected String sourceKind() {
        return CloudTrailFormat.SOURCE_KIND;
    }

    @Override
    protected Optional<MappingSpec> mapping() {
        return Optional.of(mapping);
    }

    @Override
    protected ParserRegistry registry() {
        return registryOf(mapping, CloudTrailParser.forSpec(mapping));
    }
}
