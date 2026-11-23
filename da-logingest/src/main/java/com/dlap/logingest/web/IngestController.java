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

package com.dlap.logingest.web;

import com.dlap.common.constants.DlapConstants;
import com.dlap.logingest.config.sources.BuiltinBindings;
import com.dlap.logingest.dto.IngestRequestDTO;
import com.dlap.logingest.service.ILogIngestService;
import com.dlap.logingest.service.registry.RecordReport;
import com.dlap.logingest.service.registry.RegistryIngestService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * HTTP entry point for da-logingest. Used by tests, by operators
 * replaying events, and (optionally) by per-cloud adapters that prefer
 * an HTTP push over Kafka publish.
 *
 * <p>The cloud-native subscribers (GCP Audit Log via Pub/Sub, AWS
 * CloudTrail in v0.2.0, Azure Activity in v0.3.0) call
 * {@link ILogIngestService#ingest} directly without going through this
 * controller. The controller exists so external producers and tests
 * have a uniform synchronous path that returns a stable
 * {@code event_id} for assertions.
 *
 * <p>Pipelines (ADR-0060). With {@code dlap.logingest.pipeline=legacy}, the
 * default, the request goes to {@link ILogIngestService} exactly as before and
 * the answer is always {@code 202 PUBLISHED}. With {@code registry}, the
 * request is a {@code DLAP_NORMALIZED_<PROVIDER>} record of the built-in
 * binding {@code dlap-rest-<provider>} and takes the parser registry path; the
 * answer reports what really happened: {@code 202} with status
 * {@code PUBLISHED} or {@code DUPLICATE} (already stored, not published again),
 * or {@code 422} with status {@code QUARANTINED} (with its quarantine id) or
 * {@code SKIPPED}. The event id is the same on both pipelines.
 */
@RestController
@RequestMapping("/api/v1/logingest")
@Tag(name = "Log Ingest", description = "Normalize and publish cloud audit-log events")
@Slf4j
public class IngestController {

    private static final String TOPIC = DlapConstants.KafkaTopic.LOG_EVENT_NORMALIZED;

    private final ILogIngestService logIngestService;
    private final Optional<RegistryIngestService> registry;

    /**
     * Creates the controller.
     *
     * @param logIngestService the legacy ingest pipeline
     * @param registry         the registry ingest path; present only with
     *                         {@code dlap.logingest.pipeline=registry}
     */
    public IngestController(ILogIngestService logIngestService, Optional<RegistryIngestService> registry) {
        this.logIngestService = logIngestService;
        this.registry = registry;
    }

    /**
     * Normalize, persist, and publish a single event. Synchronous:
     * returns the assigned {@code event_id}.
     *
     * <p>Authorization: any authenticated principal with
     * {@code ROLE_ADMIN} or {@code ROLE_DATA_ENGINEER}. Cloud
     * subscribers run as service accounts that map to
     * {@code ROLE_DATA_ENGINEER} via Keycloak realm roles.
     *
     * @param request the normalized event
     * @return {@code 202} with the event id and status; on the registry
     *         pipeline {@code 422} when the record is quarantined or skipped
     */
    @PostMapping("/events")
    @PreAuthorize("hasAnyAuthority('ROLE_ADMIN', 'ROLE_DATA_ENGINEER')")
    @Operation(summary = "Ingest a single normalized cloud audit-log event")
    public ResponseEntity<Map<String, Object>> ingest(@Valid @RequestBody IngestRequestDTO request) {
        log.info("REST request to ingest event provider={} operation={} target={}",
                request.getSourceProvider(), request.getOperation(),
                request.getTargetResourceId());
        if (registry.isEmpty()) {
            String eventId = logIngestService.ingest(request);
            return ResponseEntity.status(HttpStatus.ACCEPTED).body(Map.of(
                    "eventId", eventId,
                    "status", "PUBLISHED",
                    "topic", TOPIC
            ));
        }
        RecordReport report = registry.get().ingestDto(request, BuiltinBindings.Channel.REST,
                RegistryIngestService.REST_SOURCE_URI, -1, false);
        return respond(report);
    }

    /**
     * The answer of the registry path.
     *
     * @param report the record's outcome
     * @return the response
     */
    static ResponseEntity<Map<String, Object>> respond(RecordReport report) {
        Map<String, Object> body = new LinkedHashMap<>();
        report.primaryEventId().ifPresent(id -> body.put("eventId", id));
        body.put("outcome", report.outcomeCode());
        if (report.quarantined()) {
            body.put("status", "QUARANTINED");
            report.quarantineId().ifPresent(id -> body.put("quarantineId", id));
            return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY).body(body);
        }
        if (report.skipped()) {
            body.put("status", "SKIPPED");
            return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY).body(body);
        }
        body.put("status", report.primaryDisposition().map(Enum::name).orElse("PUBLISHED"));
        body.put("topic", TOPIC);
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(body);
    }
}
