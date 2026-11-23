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

package com.dlap.adminservice.audit.web;

import java.security.PublicKey;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import com.dlap.adminservice.audit.AuditVerificationService;
import com.dlap.adminservice.audit.CheckpointKeys;
import com.dlap.adminservice.audit.CheckpointService;
import com.dlap.adminservice.audit.CheckpointStore;
import com.dlap.adminservice.audit.StoredCheckpoint;
import com.dlap.common.audit.AuditEntry;
import com.dlap.common.audit.AuditException;
import com.dlap.common.audit.AuditLogReader;
import com.dlap.common.audit.AuditStream;
import com.dlap.common.audit.AuditStreams;
import com.dlap.common.audit.CheckpointSignatures;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

/**
 * The audit log API (docs/specs/audit-log.md, section "API"): read the streams and their entries,
 * verify a stream, list its signed checkpoints and the trusted public keys, and run the checkpoint
 * job now. Reading is for the Executive tier and the auditor (ADMIN, CDO, AUDITOR); running the job
 * is ADMIN only. Nothing here changes an audit row.
 */
@RestController
@RequestMapping("/api/v1/audit")
@Tag(name = "Audit Log", description = "Tamper-evident audit streams, verification and signed checkpoints")
public class AuditController {

    /** The roles that may read the audit log. */
    static final String READERS = "hasAnyRole('ADMIN', 'CDO', 'AUDITOR')";

    /** Largest page of entries. */
    static final int MAX_ENTRIES = 500;

    /** Largest page of checkpoints. */
    static final int MAX_CHECKPOINTS = 500;

    private static final Logger LOG = LoggerFactory.getLogger(AuditController.class);

    private final AuditLogReader reader;
    private final CheckpointStore checkpoints;
    private final CheckpointKeys keys;
    private final AuditVerificationService verification;
    private final CheckpointService checkpointService;

    /**
     * Creates the controller.
     *
     * @param reader            the audit log reader
     * @param checkpoints       the checkpoint table
     * @param keys              the trusted keys
     * @param verification      the verifier
     * @param checkpointService the signer
     */
    public AuditController(AuditLogReader reader, CheckpointStore checkpoints, CheckpointKeys keys,
            AuditVerificationService verification, CheckpointService checkpointService) {
        this.reader = reader;
        this.checkpoints = checkpoints;
        this.keys = keys;
        this.verification = verification;
        this.checkpointService = checkpointService;
    }

    /**
     * One stream with its head and newest checkpoint.
     *
     * @param streamId                the stream
     * @param writerRole              its database writer role
     * @param description             what it records
     * @param headSeq                 the newest position, 0 when empty
     * @param headHash                the newest hash, {@code null} when empty
     * @param latestCheckpointSeq     the newest checkpoint position, or {@code null}
     * @param latestCheckpointAt      when it was signed, or {@code null}
     * @param latestCheckpointExported whether it has a write-once copy
     */
    public record StreamView(String streamId, String writerRole, String description, long headSeq, String headHash,
            Long latestCheckpointSeq, Instant latestCheckpointAt, boolean latestCheckpointExported) {
    }

    /**
     * A trusted public key.
     *
     * @param keyId        the key id
     * @param publicKeyPem the X.509 PEM
     * @param signing      whether this instance signs with it
     */
    public record TrustedKey(String keyId, String publicKeyPem, boolean signing) {
    }

    /**
     * Every registered stream.
     *
     * @return the streams with their heads
     */
    @GetMapping("/streams")
    @PreAuthorize(READERS)
    @Operation(summary = "List the audit streams with their heads and newest checkpoints")
    public List<StreamView> streams() {
        List<StreamView> out = new ArrayList<>();
        for (AuditStream s : reader.streams()) {
            Optional<AuditEntry> head = reader.head(s.streamId());
            Optional<StoredCheckpoint> latest = checkpoints.latest(s.streamId());
            out.add(new StreamView(s.streamId(), s.writerRole(), s.description(), head.map(AuditEntry::seq).orElse(0L),
                    head.map(AuditEntry::entryHash).orElse(null), latest.map(StoredCheckpoint::seq).orElse(null),
                    latest.map(StoredCheckpoint::signedAt).orElse(null),
                    latest.map(StoredCheckpoint::isExported).orElse(false)));
        }
        return out;
    }

    /**
     * Entries of a stream in order; the record is the exact stored text.
     *
     * @param streamId the stream
     * @param afterSeq the last position already read, 0 to start
     * @param limit    1 to 500
     * @return the entries
     */
    @GetMapping("/streams/{streamId}/entries")
    @PreAuthorize(READERS)
    @Operation(summary = "Read entries of an audit stream, oldest first")
    public List<AuditEntry> entries(@PathVariable String streamId,
            @RequestParam(defaultValue = "0") long afterSeq, @RequestParam(defaultValue = "100") int limit) {
        requireStream(streamId);
        if (afterSeq < 0 || limit < 1 || limit > MAX_ENTRIES) {
            throw new IllegalArgumentException("afterSeq must be 0 or more and limit 1 to " + MAX_ENTRIES);
        }
        return reader.page(streamId, afterSeq, limit);
    }

    /**
     * Verifies a stream.
     *
     * @param streamId the stream
     * @return the report; status BROKEN names the first problem
     */
    @GetMapping("/streams/{streamId}/verification")
    @PreAuthorize(READERS)
    @Operation(summary = "Recompute the hash chain of a stream and check it against its signed checkpoints")
    public AuditVerificationService.Report verify(@PathVariable String streamId) {
        return verification.verify(streamId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "no audit stream " + streamId));
    }

    /**
     * The newest checkpoints of a stream.
     *
     * @param streamId the stream
     * @param limit    1 to 500
     * @return newest first, with payload and signature for offline verification
     */
    @GetMapping("/streams/{streamId}/checkpoints")
    @PreAuthorize(READERS)
    @Operation(summary = "List the signed checkpoints of a stream, newest first")
    public List<StoredCheckpoint> checkpoints(@PathVariable String streamId,
            @RequestParam(defaultValue = "50") int limit) {
        requireStream(streamId);
        if (limit < 1 || limit > MAX_CHECKPOINTS) {
            throw new IllegalArgumentException("limit must be 1 to " + MAX_CHECKPOINTS);
        }
        return checkpoints.recent(streamId, limit);
    }

    /**
     * The public keys checkpoints are verified with.
     *
     * @return the keys, the signing key first
     */
    @GetMapping("/keys")
    @PreAuthorize(READERS)
    @Operation(summary = "List the trusted checkpoint public keys")
    public List<TrustedKey> trustedKeys() {
        String signingId = keys.signing().map(CheckpointKeys.SigningKey::keyId).orElse(null);
        List<TrustedKey> out = new ArrayList<>();
        for (Map.Entry<String, PublicKey> k : keys.trusted().entrySet()) {
            out.add(new TrustedKey(k.getKey(), CheckpointSignatures.toPem(k.getValue()), k.getKey().equals(signingId)));
        }
        return out;
    }

    /**
     * Runs the checkpoint job now.
     *
     * @return what the run did
     */
    @PostMapping("/checkpoints")
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(summary = "Sign and export checkpoints now")
    public CheckpointService.CheckpointRun runCheckpoints() {
        if (!checkpointService.isConfigured()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "checkpoints are not configured: set dlap.audit.signing.* and dlap.audit.export.sink");
        }
        return checkpointService.run();
    }

    /**
     * The audit database is not reachable: 503, without the driver's message.
     *
     * @param e the failure
     * @return the error body
     */
    @ExceptionHandler(AuditException.class)
    public ResponseEntity<Map<String, Object>> auditUnavailable(AuditException e) {
        LOG.warn("Audit database call failed: {}", e.getMessage());
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status", HttpStatus.SERVICE_UNAVAILABLE.value());
        body.put("error", HttpStatus.SERVICE_UNAVAILABLE.getReasonPhrase());
        body.put("message", "The audit database (dlap_common) is not reachable");
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(body);
    }

    private void requireStream(String streamId) {
        AuditStreams.requireValid(streamId);
        if (reader.streams().stream().noneMatch(s -> s.streamId().equals(streamId))) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "no audit stream " + streamId);
        }
    }
}
