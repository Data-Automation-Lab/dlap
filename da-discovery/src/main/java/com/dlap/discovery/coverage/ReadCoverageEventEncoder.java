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

package com.dlap.discovery.coverage;

import java.time.Instant;
import java.util.List;

import com.dlap.common.logevent.DlapLogEventCodec;
import com.dlap.common.logevent.LogEventIds;
import com.dlap.common.logevent.SourceFormats;
import com.dlap.proto.log.v1.DlapLogEvent;
import com.dlap.proto.log.v1.Origin;
import com.dlap.proto.log.v1.ParseStatus;
import com.dlap.proto.log.v1.ResultStatus;
import com.dlap.proto.log.v1.SourceProvider;
import com.dlap.proto.log.v1.TargetResource;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.protobuf.Timestamp;

/**
 * Writes a {@link ReadCoverageObservation} as a {@code DlapLogEvent} for the normalized spine
 * (COV-01; the wire form is in {@code docs/specs/asset-descriptor.md}, "Read coverage", and read
 * by da-catalog's {@code ReadCoverageWire}).
 *
 * <p>{@code change_type} stays unspecified, so the router acknowledges the event without routing
 * it and no lifecycle consumer changes asset state from it (log-event.md section 7). The event id
 * is {@code LogEventIds.eventId(provider, "DLAP_READ_COVERAGE|<root>|<observedAt>")}, so a
 * re-send of the same observation has the same id.
 */
public final class ReadCoverageEventEncoder {

    /** Source kind of an observation. */
    public static final String SOURCE_KIND = "DLAP_READ_COVERAGE";
    /** Envelope of an observation. */
    public static final String ENVELOPE = "config-observation";
    /** {@code target_resource.kind}. */
    public static final String TARGET_KIND = "DLAP_READ_COVERAGE";
    /** Operation of every observation. */
    public static final String OPERATION = "READ_COVERAGE";
    /** Level key. */
    public static final String KEY_LEVEL = "readCoverage.level";
    /** Reasons key: a JSON array of strings. */
    public static final String KEY_REASONS = "readCoverage.reasons";
    /** Service types key: comma-separated. */
    public static final String KEY_APPLIES_TO = "readCoverage.appliesTo";
    /** Channel key. */
    public static final String KEY_CHANNEL = "readCoverage.channel";
    /** Scope key. */
    public static final String KEY_SCOPE = "readCoverage.scope";

    private final ObjectMapper mapper;

    /**
     * Creates the encoder.
     *
     * @param mapper writes the reasons array
     */
    public ReadCoverageEventEncoder(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    /**
     * Builds the event.
     *
     * @param observation the observation
     * @param observedAt  when the configuration was read, by the collector's clock
     * @return the event
     */
    public DlapLogEvent toEvent(ReadCoverageObservation observation, Instant observedAt) {
        SourceProvider provider = SourceProvider.valueOf(observation.provider());
        CoverageVerdict verdict = observation.verdict();
        Timestamp at = Timestamp.newBuilder()
                .setSeconds(observedAt.getEpochSecond()).setNanos(observedAt.getNano()).build();
        String nativeKey = LogEventIds.namespacedNativeKey(SOURCE_KIND, observation.rootUri(), observedAt.toString());
        TargetResource target = TargetResource.newBuilder()
                .setKind(TARGET_KIND)
                .setId(observation.rootUri())
                .setProject(observation.scopeId())
                .putTechnicalMetadata(KEY_LEVEL, verdict.known() ? verdict.level().name() : CoverageLevel.UNKNOWN)
                .putTechnicalMetadata(KEY_REASONS, json(verdict.reasons()))
                .putTechnicalMetadata(KEY_APPLIES_TO, String.join(",", observation.appliesTo()))
                .putTechnicalMetadata(KEY_CHANNEL, observation.channel())
                .putTechnicalMetadata(KEY_SCOPE, observation.scopeId())
                .build();
        return DlapLogEvent.newBuilder()
                .setEventId(LogEventIds.eventId(provider, nativeKey))
                .setNativeEventId(nativeKey)
                .setEventTime(at)
                .setIngestTime(at)
                .setSourceProvider(provider)
                .setSourceService("dlap-discovery")
                .setOperation(OPERATION)
                .setResultStatus(ResultStatus.SUCCESS)
                .setTargetResource(target)
                .setSchemaVersion(DlapLogEventCodec.SCHEMA_VERSION)
                .setSourceFormat(SourceFormats.of(SOURCE_KIND, ENVELOPE))
                .setParserVersion(observation.collector())
                .setOrigin(Origin.ORIGIN_CRAWLER)
                .setParseStatus(ParseStatus.PARSE_STATUS_FULL)
                .build();
    }

    private String json(List<String> reasons) {
        try {
            return mapper.writeValueAsString(reasons);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("cannot write read-coverage reasons", e);
        }
    }
}
