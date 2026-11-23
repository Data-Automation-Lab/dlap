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

import com.dlap.logingest.parse.core.ParserRegistry;
import com.dlap.logingest.parse.core.mapping.MappingDrivenParser;
import com.dlap.logingest.parse.core.mapping.MappingSpec;
import com.dlap.proto.log.v1.DlapLogEvent;
import com.dlap.proto.log.v1.ResultStatus;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.Instant;
import java.util.Locale;
import java.util.Optional;

/**
 * Runs the contract base on the real {@code da-log-fixtures} corpus: the {@code selftest} family,
 * parsed by a {@link MappingDrivenParser} built only from the sample family mapping
 * ({@code selftest-mapping.yaml}). This proves the YAML model, the declarative detector, the
 * registry, the mapper and the base work together on shared corpus bytes, before any vendor family
 * exists.
 *
 * <p>The selftest {@code expected.json} files predate the contract and list a small invented
 * projection ({@code eventTime}, {@code operation}, {@code target}, {@code actor},
 * {@code result}), so this test maps wire events onto it. Families use the default projection.
 */
class SelftestParserContractTest extends AbstractParserContractTest {

    static final String MAPPING = "com/dlap/logingest/parse/core/contract/selftest-mapping.yaml";

    @Override
    protected String family() {
        return "selftest";
    }

    @Override
    protected String sourceKind() {
        return "DLAP_SELFTEST";
    }

    @Override
    protected Optional<MappingSpec> mapping() {
        return Optional.of(mappingResource(MAPPING, "selftest"));
    }

    @Override
    protected ParserRegistry registry() {
        MappingSpec spec = mapping().orElseThrow();
        return registryOf(spec, MappingDrivenParser.forEnvelope(spec, "generic-json", "selftest-reference", "1.0.0"));
    }

    @Override
    protected JsonNode projectEvent(DlapLogEvent event) {
        ObjectNode n = JsonNodeFactory.instance.objectNode();
        n.put("eventTime", Instant.ofEpochSecond(event.getEventTime().getSeconds(), event.getEventTime().getNanos())
                .toString());
        n.put("operation", event.getOperation().toUpperCase(Locale.ROOT));
        n.put("target", event.getTargetResource().getId());
        n.put("actor", event.getPrincipal().getId());
        n.put("result", event.getResultStatus() == ResultStatus.SUCCESS ? "SUCCESS" : event.getResultStatus().name());
        return n;
    }
}
