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

package com.dlap.logingest.parse.dlapnormalized;

import com.dlap.logingest.parse.core.EnvelopeKey;
import com.dlap.logingest.parse.core.FormatKey;
import com.dlap.logingest.parse.core.RawRecord;
import com.dlap.logingest.parse.core.VersionDetector;
import com.dlap.proto.log.v1.SourceProvider;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.Objects;
import java.util.Optional;

/**
 * Recognizes a normalized record of one provider: a JSON object with a textual
 * {@code sourceProvider}. The shape has no version field; it is version {@code v1}.
 */
public final class DlapNormalizedDetector implements VersionDetector {

    private final EnvelopeKey key;

    /**
     * Creates the detector of one provider's source kind.
     *
     * @param provider the provider
     */
    public DlapNormalizedDetector(SourceProvider provider) {
        this.key = DlapNormalizedFormat.envelopeKey(Objects.requireNonNull(provider, "provider"));
    }

    @Override
    public EnvelopeKey envelopeKey() {
        return key;
    }

    @Override
    public Optional<FormatKey> detect(RawRecord record) {
        JsonNode body = record.body();
        if (body == null || !body.isObject() || !body.path("sourceProvider").isTextual()) {
            return Optional.empty();
        }
        return Optional.of(key.at(DlapNormalizedFormat.VERSION));
    }
}
