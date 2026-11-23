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

import com.dlap.logingest.parse.UnclaimedCellsContractTest;
import com.dlap.logingest.parse.core.ParserRegistry;
import com.dlap.logingest.parse.core.mapping.MappingSpec;
import java.util.List;
import java.util.Optional;

/**
 * The parser contract on every case of the {@code s3accesslog} fixture family (PARSE-02): lifecycle
 * expirations, delete markers, transitions and aborted uploads, ordinary requests and a record without
 * an operation. The family has no independent coverage spec yet, so its cell stays unclaimed and the
 * cases run through {@link UnclaimedCellsContractTest}.
 */
class S3AccessLogContractTest extends UnclaimedCellsContractTest {

    private final MappingSpec mapping = classpathMapping(S3AccessLogFormat.FAMILY);

    @Override
    protected String family() {
        return S3AccessLogFormat.FAMILY;
    }

    @Override
    protected String sourceKind() {
        return S3AccessLogFormat.SOURCE_KIND;
    }

    @Override
    protected Optional<MappingSpec> mapping() {
        return Optional.of(mapping);
    }

    @Override
    protected ParserRegistry registry() {
        return registryOf(mapping, List.of(new S3AccessLogParser(mapping)));
    }
}
