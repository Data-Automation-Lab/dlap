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

package com.dlap.logingest.service.registry;

import com.dlap.logingest.config.sources.BuiltinBindings;
import com.dlap.logingest.dto.IngestRequestDTO;
import com.dlap.logingest.parse.core.RawRecord;
import com.dlap.logingest.parse.core.RawRef;
import com.dlap.logingest.parse.dlapnormalized.DlapNormalizedFormat;
import com.dlap.proto.log.v1.SourceProvider;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import java.time.Instant;

/**
 * Turns an {@link IngestRequestDTO} into the raw record of its built-in binding: the DTO's JSON
 * (absent fields omitted, {@code eventTime} as RFC 3339), source kind
 * {@code DLAP_NORMALIZED_<PROVIDER>}, envelope {@code ingest-dto}, and the channel's ingest mode.
 */
public final class DtoRecords {

    private static final ObjectMapper JSON = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
            .setSerializationInclusion(JsonInclude.Include.NON_NULL);

    private DtoRecords() {
    }

    /**
     * Reads the provider a DTO names.
     *
     * @param dto the DTO
     * @return the provider
     * @throws IllegalArgumentException when {@code sourceProvider} is not a known provider
     */
    public static SourceProvider provider(IngestRequestDTO dto) {
        return DlapNormalizedFormat.provider(dto.getSourceProvider()).orElseThrow(() -> new IllegalArgumentException(
                "sourceProvider must be one of " + DlapNormalizedFormat.providers()));
    }

    /**
     * Builds the record.
     *
     * @param dto         the DTO
     * @param channel     the channel it arrived on
     * @param sourceUri   where it came from: the endpoint, or the fixture file
     * @param recordIndex its index in the fixture file, or -1
     * @param fetchedAt   the service clock's time
     * @return the record
     * @throws IllegalArgumentException when the DTO names no known provider
     */
    public static RawRecord toRecord(IngestRequestDTO dto, BuiltinBindings.Channel channel, String sourceUri,
                                     long recordIndex, Instant fetchedAt) {
        SourceProvider provider = provider(dto);
        ObjectNode body = JSON.valueToTree(dto);
        return RawRecord.builder()
                .bindingId(channel.bindingId(provider))
                .sourceKind(DlapNormalizedFormat.sourceKind(provider))
                .envelope(DlapNormalizedFormat.ENVELOPE)
                .ref(RawRef.of(channel.transport(), sourceUri, recordIndex))
                .mode(channel.mode())
                .fetchedAt(fetchedAt)
                .json(body)
                .build();
    }
}
