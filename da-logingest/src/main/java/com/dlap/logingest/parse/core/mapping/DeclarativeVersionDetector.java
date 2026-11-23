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

package com.dlap.logingest.parse.core.mapping;

import com.dlap.logingest.parse.core.EnvelopeKey;
import com.dlap.logingest.parse.core.FormatKey;
import com.dlap.logingest.parse.core.FormatNames;
import com.dlap.logingest.parse.core.RawRecord;
import com.dlap.logingest.parse.core.VersionDetector;
import com.dlap.logingest.parse.core.VersionScheme;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.Objects;
import java.util.Optional;

/**
 * A {@link VersionDetector} built from an envelope's {@code version-detection} section.
 *
 * <ul>
 *   <li>{@code major-minor}: the version is the text at {@code pointer} (CloudTrail
 *       {@code /eventVersion}), or an integral JSON number there. A non-integral number is not
 *       read, because {@code 1.10} as a number is {@code 1.1}.</li>
 *   <li>{@code named}: the first rule whose conditions all hold names the version (GCP
 *       {@code protoPayload.metadata.@type} is {@code ...BigQueryAuditMetadata} gives
 *       {@code bq-auditmetadata}).</li>
 * </ul>
 * A body that is not a JSON object, or that no rule matches, is not a record of the envelope.
 */
public final class DeclarativeVersionDetector implements VersionDetector {

    private final EnvelopeKey key;
    private final MappingSpec.Detection detection;

    /**
     * Creates the detector.
     *
     * @param key       the envelope it serves
     * @param detection the YAML section
     */
    public DeclarativeVersionDetector(EnvelopeKey key, MappingSpec.Detection detection) {
        this.key = Objects.requireNonNull(key, "key");
        this.detection = Objects.requireNonNull(detection, "detection");
    }

    @Override
    public EnvelopeKey envelopeKey() {
        return key;
    }

    @Override
    public Optional<FormatKey> detect(RawRecord record) {
        RawRecord.Decoded decoded = record.decode();
        if (!decoded.ok() || !decoded.body().isObject()) {
            return Optional.empty();
        }
        JsonNode body = decoded.body();
        Optional<String> version = detection.scheme() == VersionScheme.MAJOR_MINOR
                ? detection.pointer().first(body).flatMap(DeclarativeVersionDetector::versionText)
                : detection.rules().stream()
                        .filter(r -> Condition.allMatch(r.when(), body))
                        .map(MappingSpec.NamedRule::version)
                        .findFirst();
        return version.filter(FormatNames::isVersion).map(key::at);
    }

    private static Optional<String> versionText(JsonNode node) {
        if (node.isTextual()) {
            return Optional.of(node.asText().strip());
        }
        if (node.isIntegralNumber() && node.canConvertToLong()) {
            return Optional.of(Long.toString(node.asLong()));
        }
        return Optional.empty();
    }

    @Override
    public String toString() {
        return "DeclarativeVersionDetector[" + key + " " + detection.scheme() + "]";
    }
}
